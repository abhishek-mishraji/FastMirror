package com.handsoffretail.fastmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.support.AgentHarness;
import com.handsoffretail.fastmirror.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * PL-01 to PL-11 — the POS Link Archive behaviour end to end, through the real composition root.
 *
 * <p>Time is driven by a {@link MutableClock}, so the 3-minute stability wait costs nothing.
 */
class PosLinkArchiveIntegrationTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path baseDirectory;

    private static final LocalDateTime OCT_15 = LocalDateTime.of(2026, 10, 15, 10, 0);

    private AgentHarness start() {
        return AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory);
    }

    private AgentHarness start(Map<String, String> overrides) {
        return AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory, overrides);
    }

    /** An extra folder outside the source folder, as INCLUDE_SUBFOLDERS requires. */
    private Path journals() throws java.io.IOException {
        return Files.createDirectories(baseDirectory.resolve("Journals"));
    }

    private static Path writeTo(Path folder, String relative, String content, LocalDateTime created)
            throws java.io.IOException {
        final Path file = folder.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        AgentHarness.setCreated(file, created);
        return file;
    }

    @Test
    @DisplayName("PL-01: only selected prefixes are copied, into their date folders, after the wait")
    void pl01_backlogOfSelectedPrefixes() throws Exception {
        for (final String prefix : List.of("CPJ", "SUM", "MCM", "ISM", "MSM", "HRS", "FGM", "FCF", "LYT", "TPM")) {
            Files.writeString(sourceRoot.resolve(prefix + "1.xml"), prefix + " payload");
            AgentHarness.setCreated(sourceRoot.resolve(prefix + "1.xml"), OCT_15);
        }

        try (AgentHarness agent = start(Map.of("INCLUDE_PREFIXES", "CPJ,HRS,LYT"))) {
            agent.scanNow();
            agent.awaitIdle();
            assertEquals(List.of(), agent.mirroredFiles(), "nothing may be copied before it is stable");

            agent.advancePastStability();
            agent.scanNow();
            agent.awaitIdle();

            assertEquals(
                    List.of(
                            "CPJ/2026/october/2026-10-15/CPJ1.xml",
                            "HRS/2026/october/2026-10-15/HRS1.xml",
                            "LYT/2026/october/2026-10-15/LYT1.xml"),
                    agent.mirroredFiles());
            agent.awaitMirrored("CPJ/2026/october/2026-10-15/CPJ1.xml", "CPJ payload");
            assertEquals(3L, agent.count(CopyOutcome.COPIED));
        }
    }

    @Test
    @DisplayName("PL-02: a file still being written is copied only once it has been quiet for 3 minutes")
    void pl02_growingFileWaitsForQuiet() throws Exception {
        try (AgentHarness agent = start()) {
            final Path file = agent.write("CPJ7.xml", "row0", OCT_15);
            agent.scanNow();

            // Appended every minute for five minutes: never 3 quiet minutes in a row.
            for (int minute = 1; minute <= 5; minute++) {
                agent.advance(Duration.ofMinutes(1));
                Files.writeString(file, "-row" + minute, StandardOpenOption.APPEND);
                agent.scanNow();
                agent.awaitIdle();
                assertEquals(List.of(), agent.mirroredFiles(), "copied while still being written, minute " + minute);
            }

            agent.advance(Duration.ofMinutes(2));
            agent.scanNow();
            agent.awaitIdle();
            assertEquals(List.of(), agent.mirroredFiles(), "only 2 quiet minutes so far");

            agent.advance(Duration.ofMinutes(1).plusSeconds(1));
            agent.scanNow();
            agent.awaitIdle();

            agent.awaitMirrored("CPJ/2026/october/2026-10-15/CPJ7.xml", "row0-row1-row2-row3-row4-row5");
        }
    }

    @Test
    @DisplayName("PL-03: a file removed from the destination by the upload agent is never copied again")
    void pl03_destinationDeleteDoesNotRecopy() throws Exception {
        try (AgentHarness agent = start()) {
            agent.write("CPJ1.xml", "uploaded once", OCT_15);
            agent.copyStableFiles();
            final Path target = agent.target("CPJ/2026/october/2026-10-15/CPJ1.xml");
            assertTrue(Files.exists(target));

            Files.delete(target); // what the S3 agent or a retention job does
            agent.copyStableFiles();
            agent.copyStableFiles();

            assertFalse(Files.exists(target), "the ledger must stop a second upload");
            assertEquals(1L, agent.count(CopyOutcome.COPIED));
        }
    }

    @Test
    @DisplayName("PL-04: after a restart, nothing already in the ledger is copied again")
    void pl04_restartDoesNotRecopy() throws Exception {
        try (AgentHarness agent = start()) {
            agent.write("CPJ1.xml", "first run", OCT_15);
            agent.copyStableFiles();
            assertEquals(1L, agent.count(CopyOutcome.COPIED));
        }
        Files.delete(mirrorRoot.resolve("CPJ/2026/october/2026-10-15/CPJ1.xml"));

        try (AgentHarness restarted = start()) {
            restarted.write("CPJ2.xml", "second run", OCT_15);
            restarted.copyStableFiles();

            assertEquals(List.of("CPJ/2026/october/2026-10-15/CPJ2.xml"), restarted.mirroredFiles());
            assertEquals(1L, restarted.count(CopyOutcome.COPIED));
        }
    }

    @Test
    @DisplayName("PL-05: COPY_FROM_DATE skips files created before it, and includes the day itself")
    void pl05_copyFromDate() throws Exception {
        try (AgentHarness agent = start(Map.of("COPY_FROM_DATE", "2026-10-01"))) {
            agent.write("CPJ1.xml", "september", LocalDateTime.of(2026, 9, 30, 23, 59));
            agent.write("CPJ2.xml", "first of october", LocalDateTime.of(2026, 10, 1, 0, 0));
            agent.write("CPJ3.xml", "later", OCT_15);
            agent.copyStableFiles();

            assertEquals(
                    List.of(
                            "CPJ/2026/october/2026-10-01/CPJ2.xml",
                            "CPJ/2026/october/2026-10-15/CPJ3.xml"),
                    agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("PL-06: every file goes into its day folder, across a year end")
    void pl06_dayFolders() throws Exception {
        try (AgentHarness agent = start()) {
            agent.write("CPJ432.xml", "daily", OCT_15);
            agent.write("HRS9.xml", "january", LocalDateTime.of(2027, 1, 2, 8, 0));
            agent.copyStableFiles();

            assertEquals(
                    List.of("CPJ/2026/october/2026-10-15/CPJ432.xml", "HRS/2027/january/2027-01-02/HRS9.xml"),
                    agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("PL-07: an included folder copies only its pdf files, into their day folders")
    void pl07_subfolderPdfs() throws Exception {
        final Path journals = journals();
        try (AgentHarness agent = start(Map.of("INCLUDE_SUBFOLDERS", journals.toString()))) {
            writeTo(journals, "journal_1015.pdf", "pdf bytes", OCT_15);
            writeTo(journals, "CPJ1.xml", "an xml in the extra folder", OCT_15);
            writeTo(journals, "deeper/nested.pdf", "too deep", OCT_15);
            agent.write("Other/ignored.pdf", "not included", OCT_15);
            agent.write("CPJ1.xml", "root file", OCT_15);
            agent.copyStableFiles();

            assertEquals(
                    List.of(
                            "CPJ/2026/october/2026-10-15/CPJ1.xml",
                            "Journals/2026/october/2026-10-15/journal_1015.pdf"),
                    agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("PL-08: a new file with the same name in the same folder overwrites the old copy")
    void pl08_sameNameOverwrites() throws Exception {
        try (AgentHarness agent = start()) {
            final Path first = agent.write("CPJ1.xml", "morning", OCT_15);
            agent.copyStableFiles();

            Files.delete(first);
            agent.write("CPJ1.xml", "afternoon - new content", OCT_15.plusHours(5));
            agent.copyStableFiles();

            agent.awaitMirrored("CPJ/2026/october/2026-10-15/CPJ1.xml", "afternoon - new content");
            assertEquals(1L, agent.count(CopyOutcome.COPIED));
            assertEquals(1L, agent.count(CopyOutcome.REPLACED));
        }
    }

    @Test
    @DisplayName("PL-09: prefixes match ignoring case; other names and extensions are ignored")
    void pl09_nameMatching() throws Exception {
        try (AgentHarness agent = start(Map.of("INCLUDE_PREFIXES", "CPJ"))) {
            agent.write("cpj432.xml", "lower case", OCT_15);
            agent.write("CPJ433.XML", "upper extension", OCT_15);
            agent.write("CPJ434.txt", "wrong extension", OCT_15);
            agent.write("report.txt", "unrelated", OCT_15);
            agent.write("ABC1.xml", "unselected prefix", OCT_15);
            agent.write("CP.xml", "too short", OCT_15);
            agent.write("CPJ435.tmp", "temporary", OCT_15);
            agent.copyStableFiles();

            assertEquals(
                    List.of(
                            "CPJ/2026/october/2026-10-15/CPJ433.XML",
                            "CPJ/2026/october/2026-10-15/cpj432.xml"),
                    agent.mirroredFiles());
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("PL-10: a locked source is LOCKED, not recorded, and copied by a later scan")
    void pl10_lockedSourceIsRetried() throws Exception {
        try (AgentHarness agent = start(Map.of("OPEN_RETRY_ATTEMPTS", "0"))) {
            final Path file = agent.write("CPJ1.xml", "held by someone", OCT_15);
            agent.scanNow();
            agent.advancePastStability();

            // Another process opens the file with FileShare.None, as an exclusive writer would.
            final String script =
                    "$f=[System.IO.File]::Open('" + file.toString().replace("'", "''")
                            + "','Open','Read','None'); Write-Output locked; Start-Sleep -Seconds 4;"
                            + " $f.Close()";
            final Process holder =
                    new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                            .redirectErrorStream(true)
                            .start();
            final String firstLine =
                    new java.io.BufferedReader(
                                    new java.io.InputStreamReader(
                                            holder.getInputStream(), StandardCharsets.UTF_8))
                            .readLine();
            assertTrue(
                    firstLine != null && firstLine.contains("locked"),
                    "precondition: the file is exclusively held, got: " + firstLine);

            agent.scanNow();
            agent.awaitIdle();
            assertEquals(1L, agent.count(CopyOutcome.LOCKED));
            assertEquals(List.of(), agent.mirroredFiles());
            assertFalse(
                    Files.exists(agent.ledgerFile()) && Files.size(agent.ledgerFile()) > 0,
                    "a locked file must not be recorded as copied");

            assertTrue(holder.waitFor(30, TimeUnit.SECONDS));
            agent.scanNow();
            agent.awaitIdle();

            agent.awaitMirrored("CPJ/2026/october/2026-10-15/CPJ1.xml", "held by someone");
        }
    }

    @Test
    @DisplayName("PL-11: a copy that never reached the ledger is copied again, to the same place")
    void pl11_crashBeforeLedgerWriteRecopies() throws Exception {
        try (AgentHarness agent = start()) {
            agent.write("CPJ1.xml", "payload", OCT_15);
            agent.copyStableFiles();
        }
        // Simulates a crash after the publish but before the ledger line was written.
        Files.delete(baseDirectory.resolve("state").resolve("copied.ledger"));

        try (AgentHarness restarted = start()) {
            restarted.copyStableFiles();

            assertEquals(1L, restarted.count(CopyOutcome.REPLACED));
            assertEquals(List.of("CPJ/2026/october/2026-10-15/CPJ1.xml"), restarted.mirroredFiles());
            restarted.awaitMirrored("CPJ/2026/october/2026-10-15/CPJ1.xml", "payload");
        }
    }

    // ------------------------------------------------------------------ keeps running

    @Test
    @DisplayName("a source folder that is missing at startup is picked up when it appears")
    void startsWithoutSourceAndWaits() throws Exception {
        final Path archive = sourceRoot.resolve("Archive");
        try (AgentHarness agent =
                AgentHarness.start(archive, mirrorRoot, baseDirectory)) {
            agent.copyStableFiles();
            agent.copyStableFiles();
            assertEquals(
                    1L,
                    agent.problemLines().stream().filter(line -> line.contains("Source folder not available")).count(),
                    "the outage is logged once, not on every scan");

            agent.write("CPJ1.xml", "arrived late", OCT_15);
            agent.copyStableFiles();

            assertEquals(List.of("CPJ/2026/october/2026-10-15/CPJ1.xml"), agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("the source folder disappearing and coming back does not stop the agent")
    void survivesSourceOutage() throws Exception {
        final Path archive = Files.createDirectories(sourceRoot.resolve("Archive"));
        final Path parked = sourceRoot.resolve("Archive-offline");
        try (AgentHarness agent = AgentHarness.start(archive, mirrorRoot, baseDirectory)) {
            agent.write("CPJ1.xml", "before", OCT_15);
            agent.copyStableFiles();

            Files.move(archive, parked); // the share drops out
            agent.copyStableFiles();
            agent.copyStableFiles();

            Files.move(parked, archive); // and comes back
            agent.write("CPJ2.xml", "after", OCT_15);
            agent.copyStableFiles();

            assertEquals(
                    List.of(
                            "CPJ/2026/october/2026-10-15/CPJ1.xml",
                            "CPJ/2026/october/2026-10-15/CPJ2.xml"),
                    agent.mirroredFiles());
            assertEquals(2L, agent.count(CopyOutcome.COPIED), "nothing copied twice across the outage");
        }
    }

    // ------------------------------------------------------------------ date from the file name

    private static final Map<String, String> STORE_ONE =
            Map.of(
                    "INCLUDE_PREFIXES", "CPJ,FGM",
                    "DATE_SOURCE", "FILENAME",
                    "FILENAME_DATE_PATTERN", "???yyyy-MM-dd*");

    @Test
    @DisplayName("a file named for 30 September but written on 2 October goes to September")
    void lateFileGoesToItsOwnMonth() throws Exception {
        try (AgentHarness agent = start(STORE_ONE)) {
            agent.write("CPJ2026-09-30-590.xml", "september business day", LocalDateTime.of(2026, 10, 2, 6, 0));
            agent.copyStableFiles();

            assertEquals(List.of("CPJ/2026/september/2026-09-30/CPJ2026-09-30-590.xml"), agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("store 2's layout: FGM3402609230009241533201.xml goes to 2026-09-23")
    void storeTwoLayout() throws Exception {
        try (AgentHarness agent =
                start(
                        Map.of(
                                "INCLUDE_PREFIXES", "FGM",
                                "DATE_SOURCE", "FILENAME",
                                "FILENAME_DATE_PATTERN", "??????yyMMdd*"))) {
            agent.write("FGM3402609230009241533201.xml", "store two", OCT_15);
            agent.copyStableFiles();

            assertEquals(
                    List.of("FGM/2026/september/2026-09-23/FGM3402609230009241533201.xml"),
                    agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("a name without a date falls back to the creation date, with one warning")
    void undatedNameFallsBack() throws Exception {
        try (AgentHarness agent = start(STORE_ONE)) {
            agent.write("CPJ_summary.xml", "no date in the name", OCT_15);
            agent.copyStableFiles();
            agent.copyStableFiles();

            assertEquals(List.of("CPJ/2026/october/2026-10-15/CPJ_summary.xml"), agent.mirroredFiles());
            assertEquals(
                    1L,
                    agent.problemLines().stream().filter(line -> line.contains("No date matching")).count());
        }
    }

    @Test
    @DisplayName("COPY_FROM_DATE is compared with the date in the file name")
    void copyFromDateUsesFileNameDate() throws Exception {
        final Map<String, String> settings = new java.util.HashMap<>(STORE_ONE);
        settings.put("COPY_FROM_DATE", "2026-10-01");
        try (AgentHarness agent = start(settings)) {
            // Both written in October, but only one is for October.
            agent.write("CPJ2026-09-30-1.xml", "september", LocalDateTime.of(2026, 10, 2, 6, 0));
            agent.write("CPJ2026-10-01-2.xml", "october", LocalDateTime.of(2026, 10, 2, 6, 0));
            agent.copyStableFiles();

            assertEquals(List.of("CPJ/2026/october/2026-10-01/CPJ2026-10-01-2.xml"), agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("subfolder files use their own date setting")
    void subfolderDateSetting() throws Exception {
        final Map<String, String> settings = new java.util.HashMap<>(STORE_ONE);
        final Path journals = journals();
        settings.put("INCLUDE_SUBFOLDERS", journals.toString());
        settings.put("SUBFOLDER_DATE_SOURCE", "FILENAME");
        settings.put("SUBFOLDER_FILENAME_DATE_PATTERN", "*_yyyyMMdd.pdf");
        try (AgentHarness agent = start(settings)) {
            writeTo(journals, "journal_20260815.pdf", "august journal", OCT_15);
            agent.write("CPJ2026-09-13-590.xml", "root file", OCT_15);
            agent.copyStableFiles();

            assertEquals(
                    List.of(
                            "CPJ/2026/september/2026-09-13/CPJ2026-09-13-590.xml",
                            "Journals/2026/august/2026-08-15/journal_20260815.pdf"),
                    agent.mirroredFiles());
        }
    }

    // ------------------------------------------------------------------ date from inside the file

    /** The .env lines for a Gilbarco Passport store such as Wolf. */
    private static final Map<String, String> PASSPORT =
            Map.of(
                    "INCLUDE_PREFIXES", "FGM,MCM,PJR,ILT",
                    "DATE_SOURCE", "CONTENT",
                    "CONTENT_DATE_FIELDS", "BusinessDate");

    /** The .env lines for a Verifone store such as Tiki. */
    private static final Map<String, String> VERIFONE =
            Map.of(
                    "INCLUDE_PREFIXES", "CPJ,SUM",
                    "DATE_SOURCE", "CONTENT",
                    "CONTENT_DATE_FIELDS", "periodEndDate,closedTime");

    @Test
    @DisplayName("Passport store: files written after midnight on 1 Oct go to their business day, 30 Sept")
    void passportMonthEnd() throws Exception {
        try (AgentHarness agent = start(PASSPORT)) {
            final LocalDateTime written = LocalDateTime.of(2026, 10, 1, 0, 9);
            agent.write(
                    "FGM3402610010009241533201.xml",
                    "<MovementHeader><BusinessDate>2026-09-30</BusinessDate><EndDate>2026-10-01</EndDate>",
                    written);
            agent.write(
                    "PJR3402610010004181533189.xml",
                    "<SaleEvent><EventStartDate>2026-10-01</EventStartDate><BusinessDate>2026-09-30</BusinessDate>",
                    written);
            agent.write(
                    "MCM3402610020009421533209.xml",
                    "<MovementHeader><BusinessDate>2026-10-01</BusinessDate>",
                    LocalDateTime.of(2026, 10, 2, 0, 9));
            agent.copyStableFiles();

            assertEquals(
                    List.of(
                            "FGM/2026/september/2026-09-30/FGM3402610010009241533201.xml",
                            "MCM/2026/october/2026-10-01/MCM3402610020009421533209.xml",
                            "PJR/2026/september/2026-09-30/PJR3402610010004181533189.xml"),
                    agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("Passport store: an acknowledgement has no business date and falls back, with one warning")
    void passportAcknowledgementFallsBack() throws Exception {
        try (AgentHarness agent = start(PASSPORT)) {
            agent.write(
                    "ILTACK3402609250942421537747.xml",
                    "<ACKHEADER><REFERENCEID>????</REFERENCEID><STATUSCODE>Success</STATUSCODE></ACKHEADER>",
                    LocalDateTime.of(2026, 9, 25, 9, 42));
            agent.copyStableFiles();
            agent.copyStableFiles();

            assertEquals(
                    List.of("ILT/2026/september/2026-09-25/ILTACK3402609250942421537747.xml"),
                    agent.mirroredFiles());
            assertEquals(
                    1L,
                    agent.problemLines().stream().filter(line -> line.contains("CONTENT_DATE_FIELDS")).count());
        }
    }

    @Test
    @DisplayName("Verifone store: reports by periodEndDate and the CPJ by closedTime, as written")
    void verifoneStore() throws Exception {
        try (AgentHarness agent = start(VERIFONE)) {
            // Saved just after midnight, so its creation date is the 5th; the period ended on the 4th.
            agent.write(
                    "SUM2025-10-04-252.xml",
                    "<pd:summaryPd><vs:period name=\"Day\" periodBeginDate=\"2025-10-03T23:45:08-04:00\""
                            + " periodEndDate=\"2025-10-04T23:45:06-04:00\" />",
                    LocalDateTime.of(2025, 10, 5, 0, 2));
            agent.write(
                    "CPJ2026-09-13-590.xml",
                    "<transSet longId=\"2026-09-13T21:40:45-04:00\"><openedTime>2026-09-12T21:40:31-04:00</openedTime>"
                            + "<closedTime>2026-09-13T21:40:45-04:00</closedTime>"
                            + "<trans><trHeader><date>2026-09-12T21:42:24-04:00</date>",
                    LocalDateTime.of(2026, 9, 13, 21, 41));
            agent.copyStableFiles();

            assertEquals(
                    List.of(
                            "CPJ/2026/september/2026-09-13/CPJ2026-09-13-590.xml",
                            "SUM/2025/october/2025-10-04/SUM2025-10-04-252.xml"),
                    agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("COPY_FROM_DATE is compared with the date inside the file")
    void copyFromDateUsesContentDate() throws Exception {
        final Map<String, String> settings = new java.util.HashMap<>(PASSPORT);
        settings.put("COPY_FROM_DATE", "2026-10-01");
        try (AgentHarness agent = start(settings)) {
            // Both written on 1 Oct; only one is for October.
            agent.write(
                    "FGM3402610010009241533201.xml",
                    "<BusinessDate>2026-09-30</BusinessDate>",
                    LocalDateTime.of(2026, 10, 1, 0, 9));
            agent.write(
                    "MCM3402610010008001533209.xml",
                    "<BusinessDate>2026-10-01</BusinessDate>",
                    LocalDateTime.of(2026, 10, 1, 23, 0));
            agent.copyStableFiles();
            agent.copyStableFiles();

            assertEquals(
                    List.of("MCM/2026/october/2026-10-01/MCM3402610010008001533209.xml"),
                    agent.mirroredFiles());
        }
    }

    // ------------------------------------------------------------------ two source folders

    @Test
    @DisplayName("Passport store: XML from BOOutBox by content, PDFs from PassportReports by print time")
    void passportXmlAndPdfFolders() throws Exception {
        final Path outBox = Files.createDirectories(sourceRoot.resolve("BOOutBox"));
        final Path reports = Files.createDirectories(sourceRoot.resolve("PassportReports"));
        final Map<String, String> settings = new java.util.HashMap<>(PASSPORT);
        settings.put("INCLUDE_PREFIXES", "MCM");
        settings.put("INCLUDE_SUBFOLDERS", reports.toString());
        settings.put("SUBFOLDER_EXTENSIONS", "pdf");
        settings.put("SUBFOLDER_DATE_SOURCE", "FILENAME");
        settings.put("SUBFOLDER_FILENAME_DATE_PATTERN", "*yyyyMMddHHmmss.pdf");
        settings.put("BUSINESS_DAY_STARTS_AT", "03:00");
        settings.put("EXCLUDE_PATTERNS", "~$*,*.tmp,*.fmtmp,UNREADEMAIL*");

        try (AgentHarness agent = AgentHarness.start(outBox, mirrorRoot, baseDirectory, settings)) {
            agent.write("MCM3402610010009421533209.xml", "<BusinessDate>2026-09-30</BusinessDate>",
                    LocalDateTime.of(2026, 10, 1, 0, 9));
            for (final String pdf : List.of(
                    "StoreClose20261001001017.pdf", // month end: business day 30 Sept
                    "ShiftClose20261001001002.pdf", // night shift of 30 Sept
                    "ShiftClose20260930122815.pdf", // midday shift of 30 Sept
                    "UNREADEMAIL20261001000823173032400196800000220261001000750.pdf")) {
                Files.writeString(reports.resolve(pdf), "%PDF-1.4 " + pdf);
            }
            agent.copyStableFiles();

            assertEquals(
                    List.of(
                            "MCM/2026/september/2026-09-30/MCM3402610010009421533209.xml",
                            "PassportReports/2026/september/2026-09-30/ShiftClose20260930122815.pdf",
                            "PassportReports/2026/september/2026-09-30/ShiftClose20261001001002.pdf",
                            "PassportReports/2026/september/2026-09-30/StoreClose20261001001017.pdf"),
                    agent.mirroredFiles());
        }

        // After a restart nothing is copied again: the ledger finds the PDFs in their own folder.
        try (AgentHarness restarted = AgentHarness.start(outBox, mirrorRoot, baseDirectory, settings)) {
            restarted.copyStableFiles();

            assertEquals(0L, restarted.count(CopyOutcome.COPIED) + restarted.count(CopyOutcome.REPLACED));
        }
    }

    // ------------------------------------------------------------------ time zone

    /** 02:30 UTC on 16 October is 22:30 on 15 October in New York. */
    private static final java.time.Instant LATE_EVENING_NEW_YORK =
            java.time.Instant.parse("2026-10-16T02:30:00Z");

    @Test
    @DisplayName("dates are New York dates by default, whatever the PC's own time zone")
    void newYorkDateByDefault() throws Exception {
        try (AgentHarness agent = start()) {
            final Path file = agent.write("CPJ1.xml", "late evening");
            AgentHarness.setCreated(file, LATE_EVENING_NEW_YORK);
            agent.copyStableFiles();

            assertEquals(List.of("CPJ/2026/october/2026-10-15/CPJ1.xml"), agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("TIME_ZONE changes which date a creation time falls on")
    void configurableTimeZone() throws Exception {
        try (AgentHarness agent = start(Map.of("TIME_ZONE", "UTC"))) {
            final Path file = agent.write("CPJ1.xml", "late evening");
            AgentHarness.setCreated(file, LATE_EVENING_NEW_YORK);
            agent.copyStableFiles();

            assertEquals(List.of("CPJ/2026/october/2026-10-16/CPJ1.xml"), agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("COPY_FROM_DATE is compared with the New York date")
    void copyFromDateUsesTimeZone() throws Exception {
        try (AgentHarness agent = start(Map.of("COPY_FROM_DATE", "2026-10-16"))) {
            final Path file = agent.write("CPJ1.xml", "still the 15th in New York");
            AgentHarness.setCreated(file, LATE_EVENING_NEW_YORK);
            agent.copyStableFiles();

            assertEquals(List.of(), agent.mirroredFiles());
        }
    }

    // ------------------------------------------------------------------ further behaviour

    @Test
    @DisplayName("the target keeps the source's last-modified time")
    void targetKeepsTimestamp() throws Exception {
        try (AgentHarness agent = start()) {
            final Path source = agent.write("CPJ1.xml", "payload", OCT_15);
            agent.copyStableFiles();

            assertEquals(
                    Files.getLastModifiedTime(source),
                    Files.getLastModifiedTime(agent.target("CPJ/2026/october/2026-10-15/CPJ1.xml")));
        }
    }

    @Test
    @DisplayName("deleting a source file leaves its copy untouched, and nothing is written to the source")
    void sourceIsNeverModified() throws Exception {
        try (AgentHarness agent = start()) {
            final Path source = agent.write("CPJ1.xml", "payload", OCT_15);
            agent.copyStableFiles();

            assertEquals(List.of("CPJ1.xml"), names(sourceRoot), "the source folder must be untouched");
            Files.delete(source);
            agent.copyStableFiles();

            assertEquals(List.of("CPJ/2026/october/2026-10-15/CPJ1.xml"), agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("a file larger than MAX_COPY_BYTES is skipped and warned about once")
    void oversizedFileIsSkipped() throws Exception {
        try (AgentHarness agent = start(Map.of("MAX_COPY_BYTES", "4"))) {
            agent.write("CPJ1.xml", "far too large", OCT_15);
            agent.copyStableFiles();
            agent.copyStableFiles();

            assertEquals(List.of(), agent.mirroredFiles());
            assertEquals(
                    1L,
                    agent.problemLines().stream().filter(line -> line.contains("MAX_COPY_BYTES")).count());
        }
    }

    @Test
    @DisplayName("a missing included folder is warned about once and picked up when it appears")
    void missingSubfolderAppearsLater() throws Exception {
        final Path journals = baseDirectory.resolve("Journals");
        try (AgentHarness agent = start(Map.of("INCLUDE_SUBFOLDERS", journals.toString()))) {
            agent.copyStableFiles();
            agent.copyStableFiles();
            assertEquals(
                    1L,
                    agent.problemLines().stream().filter(line -> line.contains("subfolder not found")).count());

            writeTo(journals, "j1.pdf", "late arrival", OCT_15);
            agent.copyStableFiles();

            assertEquals(List.of("Journals/2026/october/2026-10-15/j1.pdf"), agent.mirroredFiles());
        }
    }

    private static List<String> names(Path directory) throws Exception {
        try (var stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
