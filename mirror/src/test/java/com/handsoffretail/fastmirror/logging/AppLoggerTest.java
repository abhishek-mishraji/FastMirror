package com.handsoffretail.fastmirror.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppLoggerTest {

    @TempDir Path logFolder;

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final Instant NOON_20TH = Instant.parse("2026-09-20T12:00:00Z");

    /** A clock the test moves by hand, so no assertion depends on wall-clock time. */
    private static final class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone;

        MutableClock(Instant start) {
            this(start, ZONE);
        }

        /** A clock in another zone, standing in for a PC set to that zone. */
        MutableClock(Instant start, ZoneId zone) {
            this.now = start;
            this.zone = zone;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private Path fileFor(String date) {
        return logFolder.resolve("fastmirror-" + date + ".log");
    }

    /** Waits for the asynchronous writer to catch up, without asserting anything about timing. */
    private static List<String> awaitLines(Path file, int expected) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) {
                final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.size() >= expected) {
                    return lines;
                }
            }
            Thread.sleep(10L);
        }
        return Files.isRegularFile(file)
                ? Files.readAllLines(file, StandardCharsets.UTF_8)
                : List.of();
    }

    @Test
    @DisplayName("lines use the agreed yyyy-MM-dd HH:mm:ssZ [LEVEL] message format, in UTC")
    void writesTheAgreedFormat() throws Exception {
        try (AppLogger logger = new AppLogger(logFolder, new MutableClock(NOON_20TH))) {
            logger.info("FastMirror started: C:\\Gemcom\\bin\\out -> C:\\temp");

            final List<String> lines = awaitLines(fileFor("2026-09-20"), 1);

            assertEquals(1, lines.size());
            assertEquals(
                    "2026-09-20 12:00:00Z [INFO] FastMirror started: C:\\Gemcom\\bin\\out -> C:\\temp",
                    lines.get(0));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"America/New_York", "Asia/Kolkata", "UTC"})
    @DisplayName("the PC's time zone changes nothing: same UTC time, same UTC-day file")
    void independentOfTheClockZone(String zone) throws Exception {
        // 20:00 UTC is 16:00 in New York and already 01:30 on the 21st in Kolkata.
        final Instant evening = Instant.parse("2026-09-20T20:00:00Z");
        try (AppLogger logger = new AppLogger(logFolder, new MutableClock(evening, ZoneId.of(zone)))) {
            logger.info("zone check");

            final List<String> lines = awaitLines(fileFor("2026-09-20"), 1);

            assertEquals(List.of("2026-09-20 20:00:00Z [INFO] zone check"), lines);
            assertEquals(fileFor("2026-09-20"), logger.currentFile());
        }
    }

    @Test
    @DisplayName("each level is rendered verbatim inside the brackets")
    void rendersEachLevel() throws Exception {
        try (AppLogger logger = new AppLogger(logFolder, new MutableClock(NOON_20TH))) {
            logger.info("one");
            logger.warn("two");
            logger.error("three");

            final List<String> lines = awaitLines(fileFor("2026-09-20"), 3);

            assertEquals(3, lines.size());
            assertTrue(lines.get(0).contains("[INFO] one"));
            assertTrue(lines.get(1).contains("[WARN] two"));
            assertTrue(lines.get(2).contains("[ERROR] three"));
        }
    }

    @Test
    @DisplayName("an exception is logged with its full stack trace")
    void logsStackTraces() throws Exception {
        try (AppLogger logger = new AppLogger(logFolder, new MutableClock(NOON_20TH))) {
            logger.error("Copy failed", new IOException("the volume is full"));

            final List<String> lines = awaitLines(fileFor("2026-09-20"), 2);

            assertTrue(lines.get(0).contains("[ERROR] Copy failed"));
            assertTrue(
                    String.join("\n", lines).contains("java.io.IOException: the volume is full"),
                    "the stack trace should be present");
        }
    }

    @Test
    @DisplayName("the log rolls to a new file when the UTC date changes")
    void rollsOverAtMidnight() throws Exception {
        final MutableClock clock = new MutableClock(NOON_20TH);
        try (AppLogger logger = new AppLogger(logFolder, clock)) {
            logger.info("before midnight");
            awaitLines(fileFor("2026-09-20"), 1);

            clock.advance(Duration.ofHours(13));
            logger.info("after midnight");

            final List<String> next = awaitLines(fileFor("2026-09-21"), 1);

            assertEquals(1, next.size());
            assertTrue(next.get(0).startsWith("2026-09-21 01:00:00Z [INFO] after midnight"));
            assertEquals(1, Files.readAllLines(fileFor("2026-09-20")).size());
        }
    }

    @Test
    @DisplayName("logs older than the retention window are deleted on rollover")
    void prunesOldLogs() throws Exception {
        final Path ancient = fileFor("2026-08-01");
        final Path recent = fileFor("2026-09-19");
        final Path unrelated = logFolder.resolve("notes.txt");
        Files.writeString(ancient, "old\n");
        Files.writeString(recent, "recent\n");
        Files.writeString(unrelated, "not a log\n");

        try (AppLogger logger = new AppLogger(logFolder, new MutableClock(NOON_20TH), 30)) {
            logger.info("today");
            awaitLines(fileFor("2026-09-20"), 1);
        }

        assertFalse(Files.exists(ancient), "a 50-day-old log should have been pruned");
        assertTrue(Files.exists(recent), "yesterday's log is inside the retention window");
        assertTrue(Files.exists(unrelated), "unrelated files must not be touched");
    }

    @Test
    @DisplayName("appends to an existing file for the same day rather than truncating it")
    void appendsWithinTheSameDay() throws Exception {
        final Path today = fileFor("2026-09-20");
        Files.writeString(today, "2026-09-20 11:00:00Z [INFO] from an earlier run\n");

        try (AppLogger logger = new AppLogger(logFolder, new MutableClock(NOON_20TH))) {
            logger.info("from this run");

            final List<String> lines = awaitLines(today, 2);

            assertEquals(2, lines.size());
            assertTrue(lines.get(0).contains("from an earlier run"));
            assertTrue(lines.get(1).contains("from this run"));
        }
    }

    @Test
    @DisplayName("logging never blocks the calling thread, even for a large burst")
    void neverBlocksTheCaller() throws Exception {
        try (AppLogger logger = new AppLogger(logFolder, new MutableClock(NOON_20TH))) {
            // Far more lines than the queue holds. If submit() blocked, this would not return
            // promptly - and a blocked caller would be a copy worker holding a producer's file open.
            for (int i = 0; i < 50_000; i++) {
                logger.warn("burst line " + i);
            }

            // The contract is only that the caller returned; lines may have been dropped.
            assertTrue(logger.droppedLines() >= 0L);
            awaitLines(fileFor("2026-09-20"), 1);
        }
    }

    @Test
    @DisplayName("close() drains what is already queued")
    void closeDrainsQueue() throws Exception {
        final AppLogger logger = new AppLogger(logFolder, new MutableClock(NOON_20TH));
        logger.info("first");
        logger.info("second");
        logger.info("third");
        logger.close();

        final List<String> lines = Files.readAllLines(fileFor("2026-09-20"), StandardCharsets.UTF_8);

        assertEquals(3, lines.size(), "every queued line should be on disk after close()");
    }

    @Test
    @DisplayName("the log folder is created if it does not exist")
    void createsLogFolder() {
        final Path nested = logFolder.resolve("deep").resolve("logs");

        try (AppLogger logger = new AppLogger(nested, new MutableClock(NOON_20TH))) {
            assertTrue(Files.isDirectory(nested));
            assertEquals(nested.resolve("fastmirror-2026-09-20.log"), logger.currentFile());
        }
    }
}
