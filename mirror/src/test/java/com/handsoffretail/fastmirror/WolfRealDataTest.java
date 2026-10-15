package com.handsoffretail.fastmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.handsoffretail.fastmirror.config.EnvFileReader;
import com.handsoffretail.fastmirror.entity.PackageManifest;
import com.handsoffretail.fastmirror.entity.PackagedFile;
import com.handsoffretail.fastmirror.support.AgentHarness;
import com.handsoffretail.fastmirror.support.MutableClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance criterion 1, on the real Wolf files: every file whose business date is 2026-09-24
 * ends up in 8 XML zips plus 1 PDF zip, each holding exactly its type's files of that day.
 *
 * <p>The XML comes from two exports, because the day's close reports were written after midnight
 * on the 25th. The expected counts are worked out here independently of the agent: the business
 * date read straight from each XML file, and each PDF dated by its print time and the 03:00 start
 * of the business day. Skipped where the sample files are not present.
 */
class WolfRealDataTest {

    private static final Path WOLF = Path.of(System.getProperty("user.home"), "OneDrive", "HOR-XML", "Wolf");
    private static final List<Path> XML_EXPORTS =
            List.of(
                    WOLF.resolve("XML_0923_0924").resolve("XML_0923_0924"),
                    WOLF.resolve("XML_0925_0926").resolve("XML_0925_0926"));
    private static final Path PDFS = WOLF.resolve("PassportReports");
    private static final Path WOLF_ENV = Path.of("..", "packaging", "env-examples", "passport-wolf.env");

    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);
    private static final String STORE = "wolf_800_NY-13208";
    private static final Pattern BUSINESS_DATE =
            Pattern.compile("<(?:\\w+:)?BusinessDate>(\\d{4}-\\d{2}-\\d{2})</");
    private static final Pattern PRINTED = Pattern.compile("(\\d{14})\\.pdf$", Pattern.CASE_INSENSITIVE);

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path baseDirectory;
    @TempDir Path outputs;

    @Test
    @DisplayName("Wolf 2026-09-24 from the real files: 8 XML zips + 1 PDF zip, each exactly that day's files")
    void wolfBusinessDay() throws Exception {
        assumeTrue(
                XML_EXPORTS.stream().allMatch(Files::isDirectory) && Files.isDirectory(PDFS),
                "the Wolf sample files are not on this PC");

        // A copy of the real files, so the test never depends on what else reads them.
        final Path outBox = Files.createDirectories(sourceRoot.resolve("BOOutBox"));
        final Path reports = Files.createDirectories(sourceRoot.resolve("PassportReports"));
        for (final Path export : XML_EXPORTS) {
            copyAll(export, outBox);
        }
        copyAll(PDFS, reports);

        // The shipped Wolf .env, with this PC's folders.
        final Map<String, String> settings = shippedWolfSettings();
        settings.put("SOURCE_FOLDER", outBox.toString());
        settings.put("MIRROR_FOLDER", mirrorRoot.toString());
        settings.put("INCLUDE_SUBFOLDERS", reports.toString());
        settings.put("PACKAGE_ENABLED", "true");
        settings.put("STORE_FOLDER", STORE);
        settings.put("OUTBOX_FOLDER", outputs.resolve("outbox").toString());
        settings.remove("PACKAGE_STAGING_FOLDER");
        settings.remove("PACKAGE_FROM_DATE");
        final List<String> prefixes = List.of(settings.get("INCLUDE_PREFIXES").split(","));

        final Map<String, Set<String>> expected = expectedFiles(outBox, reports, prefixes);
        assertEquals(9, expected.size(), "8 XML types and the PDFs carry 2026-09-24: " + expected.keySet());

        final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T07:00:00Z"), ZoneOffset.UTC);
        try (AgentHarness agent = AgentHarness.start(outBox, mirrorRoot, baseDirectory, settings, clock)) {
            agent.copyStableFiles();
            clock.advance(Duration.between(clock.instant(), Instant.parse("2026-09-25T08:00:00Z")));
            agent.packageNow();
        }

        final Path day = outputs.resolve("outbox").resolve(STORE).resolve("2026/september/2026-09-24");
        final Map<String, Set<String>> zipped = new TreeMap<>();
        try (Stream<Path> types = Files.list(day)) {
            for (final Path type : types.toList()) {
                final String name = type.getFileName().toString();
                final Path zip = type.resolve(STORE + "_" + name + "_2026-09-24_v1.zip");
                zipped.put(name, entries(zip, name));
            }
        }

        assertEquals(expected, zipped);
    }

    private Map<String, String> shippedWolfSettings() throws IOException {
        final Path envBase = Files.createDirectories(baseDirectory.resolve("env"));
        Files.copy(WOLF_ENV, envBase.resolve(".env"));
        return new HashMap<>(new EnvFileReader(envBase).fileValues());
    }

    /** Each type's file names for 2026-09-24, worked out without the agent. */
    private static Map<String, Set<String>> expectedFiles(Path outBox, Path reports, List<String> prefixes)
            throws IOException {
        final Map<String, Set<String>> expected = new TreeMap<>();
        try (Stream<Path> files = Files.list(outBox)) {
            for (final Path file : files.toList()) {
                final String name = file.getFileName().toString();
                final String prefix = name.substring(0, 3).toUpperCase(Locale.ROOT);
                if (!prefixes.contains(prefix) || !name.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    continue;
                }
                final Matcher date = BUSINESS_DATE.matcher(head(file));
                if (date.find() && LocalDate.parse(date.group(1)).equals(DAY)) {
                    expected.computeIfAbsent(prefix, key -> new TreeSet<>()).add(name);
                }
            }
        }
        final DateTimeFormatter printed = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        try (Stream<Path> files = Files.list(reports)) {
            for (final Path file : files.toList()) {
                final String name = file.getFileName().toString();
                final Matcher time = PRINTED.matcher(name);
                if (name.startsWith("UNREADEMAIL") || !time.find()) {
                    continue;
                }
                final LocalDateTime at = LocalDateTime.parse(time.group(1), printed);
                final LocalDate businessDay =
                        at.toLocalTime().isBefore(LocalTime.of(3, 0)) ? at.toLocalDate().minusDays(1) : at.toLocalDate();
                if (businessDay.equals(DAY)) {
                    expected.computeIfAbsent("PassportReports", key -> new TreeSet<>()).add(name);
                }
            }
        }
        return expected;
    }

    /** A zip's file names, after checking every entry against its manifest. */
    private static Set<String> entries(Path zip, String type) throws IOException {
        final Set<String> names = new TreeSet<>();
        PackageManifest manifest = null;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                in.transferTo(bytes);
                if (entry.getName().equals(PackageManifest.ENTRY_NAME)) {
                    manifest = PackageManifest.parse(bytes.toString(StandardCharsets.UTF_8)).orElseThrow();
                } else {
                    assertEquals("2026/september/2026-09-24/" + type, entry.getName().substring(0, entry.getName().lastIndexOf('/')));
                    names.add(entry.getName().substring(entry.getName().lastIndexOf('/') + 1));
                }
            }
        }
        assertEquals(DAY, manifest.folder().businessDate());
        assertEquals(type, manifest.folder().type());
        assertEquals(names, new TreeSet<>(manifest.packagedFiles().stream().map(PackagedFile::name).toList()));
        return names;
    }

    private static String head(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(65_536), StandardCharsets.UTF_8);
        }
    }

    private static void copyAll(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.list(from)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                Files.copy(file, to.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
