package com.handsoffretail.fastmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.support.AgentHarness;
import com.handsoffretail.fastmirror.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every moment is UTC and every business date is the POS's own, whatever the PC's time zone is set
 * to. The same files, at the same instant, give the same mirror and the same log on a PC set to
 * New York, Kolkata or UTC.
 */
class TimeZoneIndependenceTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path baseDirectory;

    /** 02:00 UTC on the 16th: 22:00 on the 15th in New York, 07:30 on the 16th in Kolkata. */
    private static final Instant NOW = Instant.parse("2026-10-16T02:00:00Z");

    @ParameterizedTest
    @ValueSource(strings = {"America/New_York", "Asia/Kolkata", "UTC"})
    @DisplayName("PC zone New York, Kolkata or UTC: identical business dates, UTC log times")
    void sameResultInEveryZone(String pcZone) throws Exception {
        final TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(pcZone));
        final Path logFolder;
        try (AgentHarness agent =
                AgentHarness.start(
                        sourceRoot,
                        mirrorRoot,
                        baseDirectory,
                        Map.of(
                                "INCLUDE_PREFIXES", "CPJ,SUM,HRS",
                                "DATE_SOURCE", "CONTENT",
                                "CONTENT_DATE_FIELDS", "periodEndDate,closedTime"),
                        new MutableClock(NOW, ZoneId.of(pcZone)))) {
            // A Tiki day closed at 21:40 New York, which is 01:40 UTC on the 16th.
            agent.write(
                    "CPJ2026-10-15-600.xml",
                    "<transSet><openedTime>2026-10-14T21:40:31-04:00</openedTime>"
                            + "<closedTime>2026-10-15T21:40:45-04:00</closedTime>",
                    LocalDateTime.of(2026, 10, 15, 21, 41));
            agent.write(
                    "SUM2026-10-15-1.xml",
                    "<vs:period periodEndDate=\"2026-10-15T21:40:44-04:00\" />",
                    LocalDateTime.of(2026, 10, 15, 21, 42));
            // No date inside: dated by its creation time in TIME_ZONE (New York), 21:30 on the 15th.
            agent.write("HRS1.xml", "<hours/>", LocalDateTime.of(2026, 10, 15, 21, 30));
            agent.copyStableFiles();

            assertEquals(
                    List.of(
                            "CPJ/2026/october/2026-10-15/CPJ2026-10-15-600.xml",
                            "HRS/2026/october/2026-10-15/HRS1.xml",
                            "SUM/2026/october/2026-10-15/SUM2026-10-15-1.xml"),
                    agent.mirroredFiles());
            logFolder = agent.config().logFolder();
        } finally {
            TimeZone.setDefault(original);
        }

        // Read after close, when the asynchronous log writer has flushed everything.
        try (Stream<Path> logs = Files.list(logFolder)) {
            assertEquals(
                    List.of("fastmirror-2026-10-16.log"),
                    logs.map(path -> path.getFileName().toString()).sorted().toList(),
                    "one log file per UTC day");
        }
        final List<String> lines =
                Files.readAllLines(logFolder.resolve("fastmirror-2026-10-16.log"), StandardCharsets.UTF_8);
        assertTrue(lines.size() > 3, lines.toString());
        for (final String line : lines) {
            if (line.matches("^\\d{4}-.*")) {
                assertTrue(line.matches("^2026-10-16 02:0\\d:\\d\\dZ \\[[A-Z]+\\] .*"), line);
            }
        }
    }
}
