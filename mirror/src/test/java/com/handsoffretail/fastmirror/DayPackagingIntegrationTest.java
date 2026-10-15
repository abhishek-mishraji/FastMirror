package com.handsoffretail.fastmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.support.AgentHarness;
import com.handsoffretail.fastmirror.support.MutableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Copy and package end to end, through the real composition root: the clock is moved to 08:00 UTC
 * the next day, the zips appear at their outbox paths, and each holds exactly its mirror folder.
 */
class DayPackagingIntegrationTest {

    private static final String STORE = "wolf_800_NY-13208";

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path baseDirectory;
    @TempDir Path outputs;

    private Map<String, String> packaging() {
        final Map<String, String> settings = new HashMap<>();
        settings.put("PACKAGE_ENABLED", "true");
        settings.put("STORE_FOLDER", STORE);
        settings.put("OUTBOX_FOLDER", outputs.resolve("outbox").toString());
        return settings;
    }

    /** A Passport store: XML from BOOutBox by content, PDFs from PassportReports by print time. */
    private Map<String, String> wolf(Path reports) {
        final Map<String, String> settings = packaging();
        settings.put("INCLUDE_PREFIXES", "MCM,PJR");
        settings.put("DATE_SOURCE", "CONTENT");
        settings.put("CONTENT_DATE_FIELDS", "BusinessDate");
        settings.put("INCLUDE_SUBFOLDERS", reports.toString());
        settings.put("SUBFOLDER_DATE_SOURCE", "FILENAME");
        settings.put("SUBFOLDER_FILENAME_DATE_PATTERN", "*yyyyMMddHHmmss.pdf");
        settings.put("BUSINESS_DAY_STARTS_AT", "03:00");
        settings.put("EXCLUDE_PATTERNS", "~$*,*.tmp,*.fmtmp,UNREADEMAIL*");
        return settings;
    }

    private static MutableClock clockAt(String instant) {
        return new MutableClock(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static void moveTo(MutableClock clock, String instant) {
        clock.advance(Duration.between(clock.instant(), Instant.parse(instant)));
    }

    /** Every zip in the outbox, as a path relative to the outbox, sorted. */
    private List<String> outbox() throws IOException {
        final Path outbox = outputs.resolve("outbox");
        try (Stream<Path> walk = Files.walk(outbox)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> outbox.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    /** The entry names of a zip, without manifest.json. */
    private List<String> entries(String outboxRelative) throws IOException {
        final List<String> names = new ArrayList<>();
        try (ZipInputStream in =
                new ZipInputStream(
                        Files.newInputStream(outputs.resolve("outbox").resolve(outboxRelative)),
                        StandardCharsets.UTF_8)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (!entry.getName().equals("manifest.json")) {
                    names.add(entry.getName());
                }
            }
        }
        return names.stream().sorted().toList();
    }

    /** The mirror files of one {@code <yyyy>/<month>/<date>/<TYPE>} folder, as zip entry names. */
    private List<String> mirrorFolder(AgentHarness agent, String folder) throws IOException {
        return agent.mirroredFiles().stream().filter(path -> path.startsWith(folder + "/")).toList();
    }

    @Test
    @DisplayName("Wolf-style: XML and PDFs of 15 Oct are zipped by type at 08:00Z on the 16th, not before")
    void wolfStyle() throws Exception {
        final Path outBox = Files.createDirectories(sourceRoot.resolve("BOOutBox"));
        final Path reports = Files.createDirectories(sourceRoot.resolve("PassportReports"));
        final MutableClock clock = clockAt("2026-10-16T07:30:00Z");

        try (AgentHarness agent = AgentHarness.start(outBox, mirrorRoot, baseDirectory, wolf(reports), clock)) {
            agent.write("PJR3402610150912001533001.xml", "<SaleEvent><BusinessDate>2026-10-15</BusinessDate>");
            agent.write("PJR3402610152310001533002.xml", "<SaleEvent><BusinessDate>2026-10-15</BusinessDate>");
            // The close report, written after midnight, for the 15th.
            agent.write("MCM3402610160009421533209.xml", "<MovementHeader><BusinessDate>2026-10-15</BusinessDate>");
            // Today's sale: never zipped today.
            agent.write("PJR3402610160701001533003.xml", "<SaleEvent><BusinessDate>2026-10-16</BusinessDate>");
            Files.writeString(reports.resolve("ShiftClose20261015131541.pdf"), "%PDF midday shift");
            Files.writeString(reports.resolve("StoreClose20261016001017.pdf"), "%PDF store close of the 15th");
            Files.writeString(reports.resolve("UNREADEMAIL20261016000823.pdf"), "%PDF excluded");
            agent.copyStableFiles();

            assertEquals(0, agent.packageNow(), "not before 08:00Z");
            assertEquals(List.of(), outbox());

            moveTo(clock, "2026-10-16T08:00:00Z");
            agent.packageNow();

            final String day = STORE + "/2026/october/2026-10-15/";
            assertEquals(
                    List.of(
                            day + "MCM/" + STORE + "_MCM_2026-10-15_v1.zip",
                            day + "PJR/" + STORE + "_PJR_2026-10-15_v1.zip",
                            day + "PassportReports/" + STORE + "_PassportReports_2026-10-15_v1.zip"),
                    outbox());
            for (final String type : List.of("MCM", "PJR", "PassportReports")) {
                assertEquals(
                        mirrorFolder(agent, "2026/october/2026-10-15/" + type),
                        entries(day + type + "/" + STORE + "_" + type + "_2026-10-15_v1.zip"),
                        type + " zip holds exactly its mirror folder");
            }
            assertEquals(
                    List.of(
                            "2026/october/2026-10-15/PassportReports/ShiftClose20261015131541.pdf",
                            "2026/october/2026-10-15/PassportReports/StoreClose20261016001017.pdf"),
                    entries(day + "PassportReports/" + STORE + "_PassportReports_2026-10-15_v1.zip"));
            assertEquals(3L, agent.stats().packaged(), "PACKAGED counts the zips");

            // A late file for the 15th: v2 with every file, v1 untouched.
            agent.write("PJR3402610160805001533004.xml", "<SaleEvent><BusinessDate>2026-10-15</BusinessDate>");
            agent.copyStableFiles();
            agent.packageNow();

            assertTrue(outbox().contains(day + "PJR/" + STORE + "_PJR_2026-10-15_v2.zip"), outbox().toString());
            assertEquals(3, entries(day + "PJR/" + STORE + "_PJR_2026-10-15_v2.zip").size());
            assertEquals(2, entries(day + "PJR/" + STORE + "_PJR_2026-10-15_v1.zip").size());
            assertEquals(0, agent.packageNow(), "nothing changed since");
        }
    }

    @Test
    @DisplayName("Tiki-style: each report and the CPJ land in their own day/type folder and their own zip")
    void tikiStyle() throws Exception {
        final Map<String, String> settings = packaging();
        settings.put("STORE_FOLDER", "Tiki_8730_IN-47872");
        settings.put("INCLUDE_PREFIXES", "CPJ,SUM,MSM");
        settings.put("DATE_SOURCE", "CONTENT");
        settings.put("CONTENT_DATE_FIELDS", "periodEndDate,closedTime");
        final MutableClock clock = clockAt("2026-10-16T02:00:00Z");

        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory, settings, clock)) {
            // The day closed at 21:40 New York, 01:40 UTC on the 16th: still the 15th's.
            agent.write(
                    "CPJ2026-10-15-600.xml",
                    "<transSet><openedTime>2026-10-14T21:40:31-04:00</openedTime>"
                            + "<closedTime>2026-10-15T21:40:45-04:00</closedTime>");
            agent.write("SUM2026-10-15-252.xml", "<vs:period periodEndDate=\"2026-10-15T21:40:44-04:00\" />");
            agent.write("MSM2026-10-15-253.xml", "<vs:period periodEndDate=\"2026-10-15T21:40:44-04:00\" />");
            agent.copyStableFiles();

            moveTo(clock, "2026-10-16T08:00:00Z");
            agent.packageNow();

            final String day = "Tiki_8730_IN-47872/2026/october/2026-10-15/";
            assertEquals(
                    List.of(
                            day + "CPJ/Tiki_8730_IN-47872_CPJ_2026-10-15_v1.zip",
                            day + "MSM/Tiki_8730_IN-47872_MSM_2026-10-15_v1.zip",
                            day + "SUM/Tiki_8730_IN-47872_SUM_2026-10-15_v1.zip"),
                    outbox());
            assertEquals(
                    List.of("2026/october/2026-10-15/CPJ/CPJ2026-10-15-600.xml"),
                    entries(day + "CPJ/Tiki_8730_IN-47872_CPJ_2026-10-15_v1.zip"));
        }
    }

    @Test
    @DisplayName("a packaging problem is logged and never stops copying")
    void packagingFailureIsolated() throws Exception {
        final Map<String, String> settings = packaging();
        settings.put("INCLUDE_PREFIXES", "CPJ,SUM");
        settings.put("DATE_SOURCE", "CONTENT");
        settings.put("CONTENT_DATE_FIELDS", "periodEndDate,closedTime");
        final MutableClock clock = clockAt("2026-10-16T02:00:00Z");
        // Something that is not a FastMirror zip already sits where CPJ v1 belongs.
        final Path squatter =
                outputs.resolve("outbox/" + STORE + "/2026/october/2026-10-15/CPJ/" + STORE + "_CPJ_2026-10-15_v1.zip");
        Files.createDirectories(squatter.getParent());
        Files.writeString(squatter, "not a zip");

        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory, settings, clock)) {
            agent.write("CPJ2026-10-15-600.xml", "<closedTime>2026-10-15T21:40:45-04:00</closedTime>");
            agent.write("SUM2026-10-15-252.xml", "<x periodEndDate=\"2026-10-15T21:40:44-04:00\" />");
            agent.copyStableFiles();
            moveTo(clock, "2026-10-16T08:00:00Z");
            agent.packageNow();

            assertTrue(
                    outbox().contains(STORE + "/2026/october/2026-10-15/SUM/" + STORE + "_SUM_2026-10-15_v1.zip"),
                    "other types are still zipped: " + outbox());
            assertEquals("not a zip", Files.readString(squatter), "a file in the outbox is never overwritten");
            assertTrue(
                    agent.problemLines().stream().anyMatch(line -> line.contains("not a complete FastMirror zip")),
                    agent.problemLines().toString());

            agent.write("CPJ2026-10-16-601.xml", "<closedTime>2026-10-16T21:40:45-04:00</closedTime>");
            agent.copyStableFiles();
            assertTrue(
                    agent.mirroredFiles().contains("2026/october/2026-10-16/CPJ/CPJ2026-10-16-601.xml"),
                    "copying carries on: " + agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("with packaging off, nothing is zipped and no outbox is created")
    void offByDefault() throws Exception {
        final MutableClock clock = clockAt("2026-10-16T02:00:00Z");
        try (AgentHarness agent =
                AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory, Map.of("INCLUDE_PREFIXES", "CPJ"), clock)) {
            agent.write("CPJ1.xml", "payload");
            agent.copyStableFiles();
            moveTo(clock, "2026-10-20T08:00:00Z");

            assertEquals(0, agent.packageNow());
            assertTrue(Files.notExists(outputs.resolve("outbox")));
        }
    }
}
