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
    @DisplayName("PL-06: GROUP_BY=MONTH puts files directly in the month folder")
    void pl06_groupByMonth() throws Exception {
        try (AgentHarness agent = start(Map.of("GROUP_BY", "MONTH"))) {
            agent.write("CPJ432.xml", "monthly", OCT_15);
            agent.write("HRS9.xml", "january", LocalDateTime.of(2027, 1, 2, 8, 0));
            agent.copyStableFiles();

            assertEquals(
                    List.of("CPJ/2026/october/CPJ432.xml", "HRS/2027/january/HRS9.xml"),
                    agent.mirroredFiles());
        }
    }

    @Test
    @DisplayName("PL-07: an included subfolder copies only its pdf files, grouped by month")
    void pl07_subfolderPdfs() throws Exception {
        try (AgentHarness agent =
                start(Map.of("INCLUDE_SUBFOLDERS", "Journals", "GROUP_BY", "DATE"))) {
            agent.write("Journals/journal_1015.pdf", "pdf bytes", OCT_15);
            agent.write("Journals/CPJ1.xml", "an xml in the subfolder", OCT_15);
            agent.write("Journals/deeper/nested.pdf", "too deep", OCT_15);
            agent.write("Other/ignored.pdf", "not included", OCT_15);
            agent.write("CPJ1.xml", "root file", OCT_15);
            agent.copyStableFiles();

            assertEquals(
                    List.of(
                            "CPJ/2026/october/2026-10-15/CPJ1.xml",
                            "Journals/2026/october/journal_1015.pdf"),
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
    @DisplayName("a missing included subfolder is warned about once and picked up when it appears")
    void missingSubfolderAppearsLater() throws Exception {
        try (AgentHarness agent = start(Map.of("INCLUDE_SUBFOLDERS", "Journals"))) {
            agent.copyStableFiles();
            agent.copyStableFiles();
            assertEquals(
                    1L,
                    agent.problemLines().stream().filter(line -> line.contains("subfolder not found")).count());

            agent.write("Journals/j1.pdf", "late arrival", OCT_15);
            agent.copyStableFiles();

            assertEquals(List.of("Journals/2026/october/j1.pdf"), agent.mirroredFiles());
        }
    }

    private static List<String> names(Path directory) throws Exception {
        try (var stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
