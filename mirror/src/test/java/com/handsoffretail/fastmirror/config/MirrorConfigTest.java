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
import com.handsoffretail.fastmirror.entity.DateSource;
import com.handsoffretail.fastmirror.entity.GroupBy;
import java.util.List;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
        values.put(MirrorConfig.KEY_INCLUDE_PREFIXES, "CPJ");
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
    @DisplayName("CV-2: a source folder that is not there yet is a warning, so the agent still starts")
    void cv2_missingSourceOnlyWarns() {
        final Path missing = sourceRoot.resolve("not-connected-yet");
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, missing.toString());

        final MirrorConfig config = load();

        assertEquals(missing.toAbsolutePath().normalize(), config.sourceFolder());
        assertEquals(1, config.warnings().size(), config.warnings().toString());
        assertTrue(config.warnings().get(0).contains("not available yet"));
        assertFalse(Files.exists(missing), "the agent must never create the source folder");
    }

    @Test
    @DisplayName("CV-5: overlap is still rejected when the source folder is not there yet")
    void cv5_overlapCheckedForMissingSource() {
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, mirrorRoot.resolve("later").toString());

        assertThrows(ConfigException.class, this::load);
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
    @DisplayName("a mirror folder nested inside the source folder is rejected as an overlap")
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
    @DisplayName("CV-6: validation leaves the source folder completely untouched")
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

    @Test
    @DisplayName("the state folder for the ledger is created beside the exe")
    void cv6_stateFolderIsCreatedBesideTheExe() {
        final MirrorConfig config = load();

        assertTrue(Files.isDirectory(config.stateFolder()));
        assertTrue(config.stateFolder().endsWith("state"));
    }

    // ------------------------------------------------------------------ ranges

    @ParameterizedTest
    @CsvSource({
        "COPY_WORKERS, 0",
        "COPY_WORKERS, 33",
        "OPEN_RETRY_ATTEMPTS, -1",
        "OPEN_RETRY_ATTEMPTS, 21",
        "OPEN_RETRY_INITIAL_MS, 0",
        "OPEN_RETRY_INITIAL_MS, 1001",
        "MAX_COPY_BYTES, -1",
        "STATS_INTERVAL_MS, 999",
        "STATS_INTERVAL_MS, 3600001",
        "STABILITY_MINUTES, 0",
        "STABILITY_MINUTES, 1441",
        "SCAN_INTERVAL_SECONDS, 9",
        "SCAN_INTERVAL_SECONDS, 3601"
    })
    @DisplayName("every numeric key rejects values outside its documented range")
    void rejectsOutOfRange(String key, String value) {
        values.put(key, value);

        final ConfigException thrown =
                assertThrows(ConfigException.class, this::load, key + "=" + value + " should be rejected");
        assertTrue(thrown.getMessage().contains("Valid range"), thrown.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
        "COPY_WORKERS, 1",
        "COPY_WORKERS, 32",
        "OPEN_RETRY_ATTEMPTS, 0",
        "OPEN_RETRY_ATTEMPTS, 20",
        "OPEN_RETRY_INITIAL_MS, 1",
        "OPEN_RETRY_INITIAL_MS, 1000",
        "MAX_COPY_BYTES, 0",
        "STATS_INTERVAL_MS, 1000",
        "STATS_INTERVAL_MS, 3600000",
        "STABILITY_MINUTES, 1",
        "STABILITY_MINUTES, 1440",
        "SCAN_INTERVAL_SECONDS, 10",
        "SCAN_INTERVAL_SECONDS, 3600"
    })
    @DisplayName("the documented range boundaries are accepted")
    void acceptsBoundaries(String key, String value) {
        values.put(key, value);

        assertTrue(load() != null, key + "=" + value + " should be accepted");
    }

    @Test
    @DisplayName("a non-numeric value names the key and the expected range")
    void rejectsNonNumeric() {
        values.put(MirrorConfig.KEY_STABILITY_MINUTES, "three");

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains(MirrorConfig.KEY_STABILITY_MINUTES));
        assertTrue(thrown.getMessage().contains("whole number"));
    }

    // ------------------------------------------------------------------ defaults

    @Test
    @DisplayName("every documented default is applied when the key is absent")
    void appliesDocumentedDefaults() {
        final MirrorConfig config = load();

        assertEquals(List.of("xml"), config.includeExtensions(), "INCLUDE_EXTENSIONS");
        assertEquals(GroupBy.DATE, config.groupBy(), "GROUP_BY");
        assertEquals(List.of(), config.includeSubfolders(), "INCLUDE_SUBFOLDERS");
        assertEquals(List.of("pdf"), config.subfolderExtensions(), "SUBFOLDER_EXTENSIONS");
        assertEquals(null, config.copyFromDate(), "COPY_FROM_DATE");
        assertEquals(java.time.ZoneId.of("America/New_York"), config.timeZone(), "TIME_ZONE");
        assertEquals(DateSource.CREATED, config.dateSource(), "DATE_SOURCE");
        assertEquals(null, config.fileNameDatePattern(), "FILENAME_DATE_PATTERN");
        assertEquals(DateSource.CREATED, config.subfolderDateSource(), "SUBFOLDER_DATE_SOURCE");
        assertEquals(3, config.stabilityMinutes(), "STABILITY_MINUTES");
        assertEquals(60, config.scanIntervalSeconds(), "SCAN_INTERVAL_SECONDS");
        assertEquals(4, config.copyWorkers(), "COPY_WORKERS");
        assertEquals(5, config.openRetryAttempts(), "OPEN_RETRY_ATTEMPTS");
        assertEquals(5L, config.openRetryInitialMs(), "OPEN_RETRY_INITIAL_MS");
        assertEquals(0L, config.maxCopyBytes(), "MAX_COPY_BYTES");
        assertEquals(List.of("~$*", "*.tmp", "*.fmtmp"), config.excludePatterns(), "EXCLUDE_PATTERNS");
        assertEquals(60_000L, config.statsIntervalMs(), "STATS_INTERVAL_MS");
        assertEquals(baseDirectory.resolve("logs"), config.logFolder(), "LOG_FOLDER");
        assertEquals(List.of(), config.warnings());
    }

    // ------------------------------------------------------------------ what to copy

    @Test
    @DisplayName("prefixes are trimmed, upper-cased and de-duplicated")
    void prefixesAreNormalized() {
        values.put(MirrorConfig.KEY_INCLUDE_PREFIXES, " cpj, HRS ,,Cpj, ism ");

        assertEquals(List.of("CPJ", "HRS", "ISM"), load().includePrefixes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CP", "CPJX", "C-J", "C J"})
    @DisplayName("a prefix that is not exactly 3 letters or digits is rejected")
    void rejectsBadPrefix(String prefix) {
        values.put(MirrorConfig.KEY_INCLUDE_PREFIXES, "CPJ," + prefix);

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains("exactly 3"), thrown.getMessage());
    }

    @Test
    @DisplayName("no prefixes and no subfolders means nothing to copy, which refuses to start")
    void nothingToCopy() {
        values.remove(MirrorConfig.KEY_INCLUDE_PREFIXES);

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains("Nothing to copy"), thrown.getMessage());
    }

    @Test
    @DisplayName("no prefixes but a subfolder is allowed, with a warning")
    void subfoldersOnly() {
        values.remove(MirrorConfig.KEY_INCLUDE_PREFIXES);
        values.put(MirrorConfig.KEY_INCLUDE_SUBFOLDERS, "Journals");

        final MirrorConfig config = load();

        assertEquals(List.of(), config.includePrefixes());
        assertEquals(1, config.warnings().size());
        assertTrue(config.warnings().get(0).contains(MirrorConfig.KEY_INCLUDE_PREFIXES));
    }

    @Test
    @DisplayName("extensions accept xml, .xml and *.xml, and are lower-cased")
    void extensionsAreNormalized() {
        values.put(MirrorConfig.KEY_INCLUDE_EXTENSIONS, "XML, .txt, *.csv");
        values.put(MirrorConfig.KEY_SUBFOLDER_EXTENSIONS, "PDF");

        final MirrorConfig config = load();

        assertEquals(List.of("xml", "txt", "csv"), config.includeExtensions());
        assertEquals(List.of("pdf"), config.subfolderExtensions());
    }

    @Test
    @DisplayName("an extension with a path or wildcard in it is rejected")
    void rejectsBadExtension() {
        values.put(MirrorConfig.KEY_INCLUDE_EXTENSIONS, "x?l");

        assertThrows(ConfigException.class, this::load);
    }

    @Test
    @DisplayName("GROUP_BY accepts DATE or MONTH in any case, and nothing else")
    void groupBy() {
        values.put(MirrorConfig.KEY_GROUP_BY, "month");
        assertEquals(GroupBy.MONTH, load().groupBy());

        values.put(MirrorConfig.KEY_GROUP_BY, "WEEK");
        final ConfigException thrown = assertThrows(ConfigException.class, this::load);
        assertTrue(thrown.getMessage().contains("DATE or MONTH"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", ".", "a/b", "a\\b", "C:"})
    @DisplayName("INCLUDE_SUBFOLDERS accepts only plain folder names")
    void rejectsBadSubfolder(String name) {
        values.put(MirrorConfig.KEY_INCLUDE_SUBFOLDERS, name);

        assertThrows(ConfigException.class, this::load);
    }

    @Test
    @DisplayName("subfolder names are de-duplicated ignoring case, keeping the first spelling")
    void subfoldersAreDeduplicated() {
        values.put(MirrorConfig.KEY_INCLUDE_SUBFOLDERS, "Journals, journals, Reports");

        assertEquals(List.of("Journals", "Reports"), load().includeSubfolders());
    }

    @Test
    @DisplayName("COPY_FROM_DATE must be yyyy-MM-dd")
    void copyFromDate() {
        values.put(MirrorConfig.KEY_COPY_FROM_DATE, "2026-01-31");
        assertEquals(LocalDate.of(2026, 1, 31), load().copyFromDate());

        values.put(MirrorConfig.KEY_COPY_FROM_DATE, "31/01/2026");
        final ConfigException thrown = assertThrows(ConfigException.class, this::load);
        assertTrue(thrown.getMessage().contains("yyyy-MM-dd"));
    }

    @Test
    @DisplayName("TIME_ZONE accepts zone names and rejects anything else")
    void timeZone() {
        values.put(MirrorConfig.KEY_TIME_ZONE, "UTC");
        assertEquals(java.time.ZoneId.of("UTC"), load().timeZone());

        values.put(MirrorConfig.KEY_TIME_ZONE, "New York");
        final ConfigException thrown = assertThrows(ConfigException.class, this::load);
        assertTrue(thrown.getMessage().contains("America/New_York"), thrown.getMessage());
    }

    @Test
    @DisplayName("DATE_SOURCE=FILENAME takes the pattern; the source is case-insensitive")
    void dateFromFileName() {
        values.put(MirrorConfig.KEY_DATE_SOURCE, "filename");
        values.put(MirrorConfig.KEY_FILENAME_DATE_PATTERN, "???yyyy-MM-dd*");

        final MirrorConfig config = load();

        assertEquals(DateSource.FILENAME, config.dateSource());
        assertEquals("???yyyy-MM-dd*", config.fileNameDatePattern().template());
    }

    @Test
    @DisplayName("DATE_SOURCE=FILENAME without a pattern refuses to start and shows an example")
    void fileNameNeedsPattern() {
        values.put(MirrorConfig.KEY_DATE_SOURCE, "FILENAME");

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains(MirrorConfig.KEY_FILENAME_DATE_PATTERN), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("???yyyy-MM-dd*"), thrown.getMessage());
    }

    @Test
    @DisplayName("an unusable pattern refuses to start and names the key")
    void badPattern() {
        values.put(MirrorConfig.KEY_DATE_SOURCE, "FILENAME");
        values.put(MirrorConfig.KEY_FILENAME_DATE_PATTERN, "???yyyy-MM*");

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains(MirrorConfig.KEY_FILENAME_DATE_PATTERN), thrown.getMessage());
    }

    @Test
    @DisplayName("a DATE_SOURCE other than CREATED or FILENAME is rejected")
    void badDateSource() {
        values.put(MirrorConfig.KEY_DATE_SOURCE, "MODIFIED");

        assertThrows(ConfigException.class, this::load);
    }

    @Test
    @DisplayName("a pattern given while the source is CREATED is ignored with a warning")
    void patternIgnoredForCreated() {
        values.put(MirrorConfig.KEY_FILENAME_DATE_PATTERN, "???yyyy-MM-dd*");

        final MirrorConfig config = load();

        assertEquals(null, config.fileNameDatePattern());
        assertTrue(config.warnings().get(0).contains("ignored"), config.warnings().toString());
    }

    @Test
    @DisplayName("subfolder files have their own date source and pattern")
    void subfolderDateSettings() {
        values.put(MirrorConfig.KEY_DATE_SOURCE, "FILENAME");
        values.put(MirrorConfig.KEY_FILENAME_DATE_PATTERN, "???yyyy-MM-dd*");
        values.put(MirrorConfig.KEY_SUBFOLDER_DATE_SOURCE, "FILENAME");
        values.put(MirrorConfig.KEY_SUBFOLDER_FILENAME_DATE_PATTERN, "*_yyyyMMdd.pdf");

        final MirrorConfig config = load();

        assertEquals("*_yyyyMMdd.pdf", config.subfolderFileNameDatePattern().template());
    }

    @Test
    @DisplayName("DATE_SOURCE=CONTENT takes the field list")
    void dateFromContent() {
        values.put(MirrorConfig.KEY_DATE_SOURCE, "content");
        values.put(MirrorConfig.KEY_CONTENT_DATE_FIELDS, "periodEndDate,closedTime");

        final MirrorConfig config = load();

        assertEquals(DateSource.CONTENT, config.dateSource());
        assertEquals(List.of("periodEndDate", "closedTime"), config.contentDateFields().names());
        assertEquals(65_536, config.contentScanBytes());
    }

    @Test
    @DisplayName("DATE_SOURCE=CONTENT without fields refuses to start and shows both store examples")
    void contentNeedsFields() {
        values.put(MirrorConfig.KEY_DATE_SOURCE, "CONTENT");

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains("BusinessDate"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("periodEndDate,closedTime"), thrown.getMessage());
    }

    @Test
    @DisplayName("an unusable field name refuses to start and names the key")
    void badContentField() {
        values.put(MirrorConfig.KEY_DATE_SOURCE, "CONTENT");
        values.put(MirrorConfig.KEY_CONTENT_DATE_FIELDS, "<BusinessDate>");

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains(MirrorConfig.KEY_CONTENT_DATE_FIELDS), thrown.getMessage());
    }

    @Test
    @DisplayName("fields given while the source is not CONTENT are ignored with a warning")
    void contentFieldsIgnored() {
        values.put(MirrorConfig.KEY_CONTENT_DATE_FIELDS, "BusinessDate");

        final MirrorConfig config = load();

        assertEquals(null, config.contentDateFields());
        assertTrue(config.warnings().get(0).contains("not CONTENT"), config.warnings().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1023", "16777217", "lots"})
    @DisplayName("CONTENT_SCAN_BYTES must be between 1 KB and 16 MB")
    void contentScanBytesRange(String value) {
        values.put(MirrorConfig.KEY_CONTENT_SCAN_BYTES, value);

        assertThrows(ConfigException.class, this::load);
    }

    @Test
    @DisplayName("BUSINESS_DAY_STARTS_AT defaults to midnight and takes HH:mm")
    void businessDayStartsAt() {
        assertEquals(java.time.LocalTime.MIDNIGHT, load().businessDayStartsAt());

        values.put(MirrorConfig.KEY_BUSINESS_DAY_STARTS_AT, "03:00");
        assertEquals(java.time.LocalTime.of(3, 0), load().businessDayStartsAt());

        values.put(MirrorConfig.KEY_BUSINESS_DAY_STARTS_AT, "3am");
        final ConfigException thrown = assertThrows(ConfigException.class, this::load);
        assertTrue(thrown.getMessage().contains("HH:mm"), thrown.getMessage());
    }

    @Test
    @DisplayName("INCLUDE_SUBFOLDERS takes a full path to a folder anywhere, named by its last part")
    void externalSubfolder() throws IOException {
        final Path reports = Files.createDirectories(baseDirectory.resolve("Z").resolve("PassportReports"));
        values.put(MirrorConfig.KEY_INCLUDE_SUBFOLDERS, "Journals, " + reports);

        final MirrorConfig config = load();

        assertEquals(List.of("Journals", "PassportReports"), config.includeSubfolders());
        assertEquals(Map.of("PassportReports", reports.toRealPath()), config.externalSubfolders());
    }

    @Test
    @DisplayName("a full-path folder that is not there yet only warns, like the source folder")
    void externalSubfolderMissing() {
        values.put(MirrorConfig.KEY_INCLUDE_SUBFOLDERS, baseDirectory.resolve("offline").resolve("PassportReports").toString());

        final MirrorConfig config = load();

        assertTrue(config.warnings().get(0).contains("not available yet"), config.warnings().toString());
    }

    @Test
    @DisplayName("a full-path folder may not overlap the mirror folder")
    void externalSubfolderOverlap() {
        values.put(MirrorConfig.KEY_INCLUDE_SUBFOLDERS, mirrorRoot.resolve("reports").toString());

        assertThrows(ConfigException.class, this::load);
    }

    @Test
    @DisplayName("two folders with the same name are rejected, since the name is their mirror folder")
    void externalSubfolderNameClash() throws IOException {
        final Path journals = Files.createDirectories(baseDirectory.resolve("elsewhere").resolve("Journals"));
        values.put(MirrorConfig.KEY_INCLUDE_SUBFOLDERS, "Journals, " + journals);

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains("two folders named"), thrown.getMessage());
    }

    // ------------------------------------------------------------------ state folder and retired keys

    @Test
    @DisplayName("a state folder inside the source folder is rejected")
    void stateFolderNotInSource() {
        values.put(MirrorConfig.KEY_STATE_FOLDER, sourceRoot.resolve("state").toString());

        final ConfigException thrown = assertThrows(ConfigException.class, this::load);

        assertTrue(thrown.getMessage().contains(MirrorConfig.KEY_STATE_FOLDER));
    }

    @Test
    @DisplayName("a state folder inside the mirror folder is rejected, so the ledger is never uploaded")
    void stateFolderNotInMirror() {
        values.put(MirrorConfig.KEY_STATE_FOLDER, mirrorRoot.resolve("state").toString());

        assertThrows(ConfigException.class, this::load);
    }

    @Test
    @DisplayName("retired v2 keys produce a warning, not an error")
    void retiredKeysWarn() {
        values.put("SWEEP_INTERVAL_MS", "500");
        values.put("CONTENT_VERIFY", "true");

        final List<String> warnings = load().warnings();

        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("SWEEP_INTERVAL_MS"));
        assertTrue(warnings.get(1).contains("CONTENT_VERIFY"));
    }

    @Test
    @DisplayName("exclude patterns are split on commas and trimmed")
    void splitsExcludePatterns() {
        values.put(MirrorConfig.KEY_EXCLUDE_PATTERNS, " *.bak , ~$* ,, *.swp ");

        assertEquals(List.of("*.bak", "~$*", "*.swp"), load().excludePatterns());
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
    @DisplayName("the configured lists are immutable once built")
    void listsAreImmutable() {
        final MirrorConfig config = load();

        assertThrows(UnsupportedOperationException.class, () -> config.excludePatterns().add("*.new"));
        assertThrows(UnsupportedOperationException.class, () -> config.includePrefixes().add("NEW"));
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
