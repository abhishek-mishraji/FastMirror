package com.handsoffretail.fastmirror.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MirrorConfigTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path baseDirectory;

    private Map<String, String> values;

    @BeforeEach
    void setUp() {
        values = new HashMap<>();
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirrorRoot.toString());
    }

    private MirrorConfig load() {
        return MirrorConfig.load(values::get, baseDirectory);
    }

    // ------------------------------------------------------------------ CV-1 .. CV-4

    @Test
    @DisplayName("CV-1: a missing required key names itself and shows an example")
    void cv1_missingRequiredKey() {
        values.remove(MirrorConfig.KEY_SOURCE_FOLDER);

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains(MirrorConfig.KEY_SOURCE_FOLDER));
        assertTrue(thrown.getMessage().contains("required"));
    }

    @Test
    @DisplayName("CV-1: a blank required key is treated as missing")
    void cv1_blankRequiredKey() {
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, "   ");

        assertThrows(ConfigException.class, this::load);
    }

    @Test
    @DisplayName("CV-2: a source folder that does not exist is rejected")
    void cv2_sourceMustExist() {
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.resolve("nope").toString());

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains("does not exist"));
    }

    @Test
    @DisplayName("CV-2: a source folder that is a file is rejected")
    void cv2_sourceMustBeDirectory() throws IOException {
        final Path file = Files.createFile(sourceRoot.resolve("not-a-dir.xml"));
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, file.toString());

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains("not a directory"));
    }

    @Test
    @DisplayName("CV-3: a missing mirror folder is created")
    void cv3_mirrorIsCreated() {
        final Path fresh = mirrorRoot.resolve("created").resolve("deeply");
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, fresh.toString());

        final MirrorConfig config = load();

        assertTrue(Files.isDirectory(fresh));
        assertEquals(fresh.toAbsolutePath().normalize(), config.mirrorFolder());
    }

    @Test
    @DisplayName("CV-4: paths are stored absolute and normalized")
    void cv4_pathsAreReal() {
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.resolve("sub").resolve("..").toString());

        final MirrorConfig config = load();

        assertTrue(config.sourceFolder().isAbsolute());
        assertEquals(config.sourceFolder(), config.sourceFolder().normalize());
    }

    // ------------------------------------------------------------------ CV-5 overlap

    @Test
    @DisplayName("UT-09: a mirror folder nested inside the source folder is rejected as an overlap")
    void ut09_rejectsMirrorInsideSource() {
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, sourceRoot.resolve("inner").toString());

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(
                thrown.getMessage().contains("infinite copy loop"),
                "message should explain the consequence, was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("is inside"));
    }

    @Test
    @DisplayName("CV-5: a source folder nested inside the mirror folder is rejected too")
    void cv5_rejectsSourceInsideMirror() throws IOException {
        final Path inner = Files.createDirectories(mirrorRoot.resolve("inner"));
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, inner.toString());

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains("infinite copy loop"));
    }

    @Test
    @DisplayName("CV-5: identical source and mirror folders are rejected")
    void cv5_rejectsIdenticalFolders() {
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, sourceRoot.toString());

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains("same directory"));
    }

    // ------------------------------------------------------------------ CV-6 non-interference

    @Test
    @DisplayName("CV-6/NI-7: validation leaves the source folder completely untouched")
    void cv6_sourceFolderIsNeverWrittenTo() throws IOException {
        final List<String> before = listNames(sourceRoot);

        load();

        assertEquals(
                before,
                listNames(sourceRoot),
                "startup validation must not create a probe file in the source folder");
        assertTrue(before.isEmpty(), "sanity: the temp source folder started empty");
    }

    @Test
    @DisplayName("CV-6: the mirror write probe is cleaned up after itself")
    void cv6_mirrorProbeIsRemoved() throws IOException {
        load();

        assertTrue(listNames(mirrorRoot).isEmpty(), "the write probe must not be left behind");
    }

    // ------------------------------------------------------------------ CV-7 ranges

    @Test
    @DisplayName("UT-10: a negative sweep interval is rejected and the valid range is stated")
    void ut10_rejectsNegativeSweepInterval() {
        values.put(MirrorConfig.KEY_SWEEP_INTERVAL_MS, "-1");

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains("out of range"));
        assertTrue(
                thrown.getMessage().contains("0 to 600000"),
                "message should state the valid range, was: " + thrown.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
        "COPY_WORKERS, 0",
        "COPY_WORKERS, 33",
        "OPEN_RETRY_ATTEMPTS, -1",
        "OPEN_RETRY_ATTEMPTS, 21",
        "OPEN_RETRY_INITIAL_MS, 0",
        "OPEN_RETRY_INITIAL_MS, 1001",
        "SWEEP_INTERVAL_MS, 600001",
        "MAX_COPY_BYTES, -1",
        "STATS_INTERVAL_MS, 999",
        "STATS_INTERVAL_MS, 3600001"
    })
    @DisplayName("CV-7: every numeric key rejects values outside its documented range")
    void cv7_rejectsOutOfRange(String key, String value) {
        values.put(key, value);

        assertThrows(ConfigException.class, this::load, key + "=" + value + " should be rejected");
    }

    @ParameterizedTest
    @CsvSource({
        "COPY_WORKERS, 1",
        "COPY_WORKERS, 32",
        "OPEN_RETRY_ATTEMPTS, 0",
        "OPEN_RETRY_ATTEMPTS, 20",
        "OPEN_RETRY_INITIAL_MS, 1",
        "OPEN_RETRY_INITIAL_MS, 1000",
        "SWEEP_INTERVAL_MS, 0",
        "SWEEP_INTERVAL_MS, 600000",
        "MAX_COPY_BYTES, 0",
        "STATS_INTERVAL_MS, 1000",
        "STATS_INTERVAL_MS, 3600000"
    })
    @DisplayName("CV-7: the documented range boundaries are accepted")
    void cv7_acceptsBoundaries(String key, String value) {
        values.put(key, value);

        assertTrue(load() != null, key + "=" + value + " should be accepted");
    }

    @Test
    @DisplayName("CV-7: a non-numeric value names the key and the expected range")
    void cv7_rejectsNonNumeric() {
        values.put(MirrorConfig.KEY_COPY_WORKERS, "four");

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains(MirrorConfig.KEY_COPY_WORKERS));
        assertTrue(thrown.getMessage().contains("whole number"));
    }

    @Test
    @DisplayName("CV-7: a boolean key accepts only exactly true or false")
    void cv7_rejectsLooseBooleans() {
        values.put(MirrorConfig.KEY_CONTENT_VERIFY, "yes");

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains("exactly 'true' or 'false'"));
    }

    @Test
    @DisplayName("CV-7: booleans are case-insensitive")
    void cv7_booleansAreCaseInsensitive() {
        values.put(MirrorConfig.KEY_CONTENT_VERIFY, "TRUE");

        assertTrue(load().contentVerify());
    }

    // ------------------------------------------------------------------ UT-11 defaults

    @Test
    @DisplayName("UT-11: every documented default is applied when the key is absent")
    void ut11_appliesDocumentedDefaults() {
        final MirrorConfig config = load();

        assertEquals(4, config.copyWorkers(), "COPY_WORKERS");
        assertEquals(5, config.openRetryAttempts(), "OPEN_RETRY_ATTEMPTS");
        assertEquals(5L, config.openRetryInitialMs(), "OPEN_RETRY_INITIAL_MS");
        assertEquals(500L, config.sweepIntervalMs(), "SWEEP_INTERVAL_MS");
        assertFalse(config.contentVerify(), "CONTENT_VERIFY");
        assertTrue(config.publishPartial(), "PUBLISH_PARTIAL defaults to true (PD-2)");
        assertFalse(config.mirrorEmptyDirectories(), "MIRROR_EMPTY_DIRECTORIES");
        assertEquals(0L, config.maxCopyBytes(), "MAX_COPY_BYTES");
        assertEquals(List.of("~$*", "*.tmp", "*.fmtmp"), config.excludePatterns(), "EXCLUDE_PATTERNS");
        assertEquals(60_000L, config.statsIntervalMs(), "STATS_INTERVAL_MS");
        assertEquals(baseDirectory.resolve("logs"), config.logFolder(), "LOG_FOLDER");
    }

    @Test
    @DisplayName("PUBLISH_PARTIAL can be turned off to restore the stricter discard behaviour")
    void publishPartialCanBeDisabled() {
        values.put(MirrorConfig.KEY_PUBLISH_PARTIAL, "false");

        assertFalse(load().publishPartial());
    }

    @Test
    @DisplayName("exclude patterns are split on commas and trimmed")
    void splitsExcludePatterns() {
        values.put(MirrorConfig.KEY_EXCLUDE_PATTERNS, " *.bak , ~$* ,, *.swp ");

        assertEquals(List.of("*.bak", "~$*", "*.swp"), load().excludePatterns());
    }

    @Test
    @DisplayName("a sweep interval of zero disables the sweep")
    void zeroSweepIntervalDisablesSweep() {
        values.put(MirrorConfig.KEY_SWEEP_INTERVAL_MS, "0");

        assertFalse(load().sweepEnabled());
    }

    @Test
    @DisplayName("MAX_COPY_BYTES of zero means no limit")
    void zeroMaxCopyBytesMeansNoLimit() {
        assertTrue(load().withinSizeLimit(Long.MAX_VALUE));

        values.put(MirrorConfig.KEY_MAX_COPY_BYTES, "1024");
        final MirrorConfig limited = load();

        assertTrue(limited.withinSizeLimit(1024L));
        assertFalse(limited.withinSizeLimit(1025L));
    }

    @Test
    @DisplayName("the exclude pattern list is immutable once built")
    void excludePatternsAreImmutable() {
        final List<String> patterns = load().excludePatterns();

        assertThrows(UnsupportedOperationException.class, () -> patterns.add("*.new"));
    }

    private static List<String> listNames(Path directory) throws IOException {
        final List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (final Path entry : stream) {
                names.add(entry.getFileName().toString());
            }
        }
        names.sort(String::compareTo);
        return names;
    }
}
