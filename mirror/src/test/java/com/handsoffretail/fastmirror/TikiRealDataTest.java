package com.handsoffretail.fastmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.handsoffretail.fastmirror.config.EnvFileReader;
import com.handsoffretail.fastmirror.support.AgentHarness;
import com.handsoffretail.fastmirror.support.MutableClock;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance criterion 2, on the real Tiki sample files and the shipped verifone-tiki.env: each
 * report, the CPJ and the PJR land in their own day/type folder and their own zip. The expected date
 * is worked out here independently of the agent: for reports and the CPJ, read straight from each
 * file (periodEndDate, else closedTime); for CREATED_DATE_PREFIXES (the PJR), the day the slice was
 * written, which its name records.
 * Skipped where the sample files are not present.
 */
class TikiRealDataTest {

    private static final Path SAMPLES =
            Path.of(System.getProperty("user.home"), "OneDrive", "HOR-XML", "Tiki", "AllTypeFiles");
    private static final Path TIKI_ENV = Path.of("..", "packaging", "env-examples", "verifone-tiki.env");
    private static final String STORE = "Tiki_8730_IN-47872";
    private static final Pattern DATE =
            Pattern.compile("periodEndDate=\"(\\d{4})-(\\d{2})-(\\d{2})|<(?:\\w+:)?closedTime>(\\d{4})-(\\d{2})-(\\d{2})");
    /** A PJR slice's name: the New York date and the minute of that day it was written. */
    private static final Pattern SLICE_NAME =
            Pattern.compile("PJR(\\d{2})-(\\d{2})-(\\d{4})-(\\d+)\\.xml", Pattern.CASE_INSENSITIVE);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path baseDirectory;
    @TempDir Path outputs;

    @Test
    @DisplayName("Tiki samples: every report, the CPJ and the PJR in its own day/type folder and its own zip")
    void tikiSamples() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES), "the Tiki sample files are not on this PC");

        final Path envBase = Files.createDirectories(baseDirectory.resolve("env"));
        Files.copy(TIKI_ENV, envBase.resolve(".env"));
        final Map<String, String> settings = new HashMap<>(new EnvFileReader(envBase).fileValues());
        settings.put("SOURCE_FOLDER", sourceRoot.toString());
        settings.put("MIRROR_FOLDER", mirrorRoot.toString());
        settings.put("OUTBOX_FOLDER", outputs.resolve("outbox").toString());
        final List<String> prefixes = List.of(settings.get("INCLUDE_PREFIXES").split(","));
        final List<String> byCreation = List.of(settings.getOrDefault("CREATED_DATE_PREFIXES", "").split(","));

        final List<String> expected = new ArrayList<>();
        try (Stream<Path> files = Files.list(SAMPLES)) {
            for (final Path file : files.toList()) {
                final String name = file.getFileName().toString();
                final String type = name.substring(0, 3).toUpperCase(Locale.ROOT);
                if (!prefixes.contains(type)) {
                    continue;
                }
                final Path copy = sourceRoot.resolve(name);
                Files.copy(file, copy);
                final LocalDate day;
                if (byCreation.contains(type)) {
                    // The copy is created now. Give it the time POS Link wrote the original, which
                    // the name records, as the original has on the store PC.
                    final Matcher slice = SLICE_NAME.matcher(name);
                    if (!slice.matches()) {
                        continue;
                    }
                    final ZonedDateTime written =
                            LocalDate.of(
                                            Integer.parseInt(slice.group(3)),
                                            Integer.parseInt(slice.group(1)),
                                            Integer.parseInt(slice.group(2)))
                                    .atStartOfDay(NEW_YORK)
                                    .plusMinutes(Integer.parseInt(slice.group(4)));
                    Files.setAttribute(copy, "basic:creationTime", FileTime.from(written.toInstant()));
                    day = written.toLocalDate();
                } else {
                    final Matcher date = DATE.matcher(head(file));
                    if (!date.find()) {
                        continue;
                    }
                    final int g = date.group(1) != null ? 1 : 4;
                    day = LocalDate.parse(date.group(g) + "-" + date.group(g + 1) + "-" + date.group(g + 2));
                }
                final String month =
                        day.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
                expected.add(
                        STORE + "/" + day.getYear() + "/" + month + "/" + day + "/" + type + "/"
                                + STORE + "_" + type + "_" + day + "_v1.zip");
            }
        }
        assertEquals(11, expected.size(), "the Tiki report types, the CPJ and the PJR: " + expected);

        // After every sample's day, so all of them are due.
        final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T07:00:00Z"), ZoneOffset.UTC);
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory, settings, clock)) {
            agent.copyStableFiles();
            clock.advance(java.time.Duration.ofHours(1));
            agent.packageNow();
        }

        final Path outbox = outputs.resolve("outbox");
        try (Stream<Path> walk = Files.walk(outbox)) {
            assertEquals(
                    expected.stream().sorted().toList(),
                    walk.filter(Files::isRegularFile)
                            .map(path -> outbox.relativize(path).toString().replace('\\', '/'))
                            .sorted()
                            .toList());
        }
    }

    private static String head(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(65_536), StandardCharsets.UTF_8);
        }
    }
}
