package com.handsoffretail.fastmirror.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.entity.DateSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shipped per-POS .env files load as they are, so the same build serves both kinds of store.
 */
class EnvExamplesTest {

    private static final Path EXAMPLES = Path.of("..", "packaging", "env-examples");

    @TempDir Path base;
    @TempDir Path source;
    @TempDir Path mirror;
    @TempDir Path outputs;

    /** Reads a shipped file the way the agent does, beside an exe in {@code base}. */
    private Map<String, String> read(String fileName) throws IOException {
        return read(EXAMPLES.resolve(fileName));
    }

    private Map<String, String> read(Path file) throws IOException {
        Files.copy(file, base.resolve(".env"));
        return new HashMap<>(new EnvFileReader(base).fileValues());
    }

    /**
     * The folders are specific to a store PC, so the test points them at temporary ones. Nothing
     * is ever created at the shipped paths.
     */
    private MirrorConfig loadWithLocalFolders(Map<String, String> values) {
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, source.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirror.toString());
        if (values.containsKey(MirrorConfig.KEY_OUTBOX_FOLDER)) {
            values.put(MirrorConfig.KEY_OUTBOX_FOLDER, outputs.resolve("outbox").toString());
        }
        return MirrorConfig.load(values::get, base);
    }

    @Test
    @DisplayName("env.template and .env.example are the same file, and load with packaging on")
    void template() throws IOException {
        final Path template = Path.of("..", "packaging", "env.template");
        assertEquals(Files.readString(template), Files.readString(Path.of("..", ".env.example")));

        final MirrorConfig config = loadWithLocalFolders(read(template));

        assertTrue(config.packagingEnabled());
        assertEquals("Tiki_8730_IN-47872", config.packaging().storeFolder());
        assertEquals(java.time.LocalTime.of(8, 0), config.packaging().packageAtUtc());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("verifone-tiki.env: business date from periodEndDate, then closedTime, then date")
    void verifone() throws IOException {
        final MirrorConfig config = loadWithLocalFolders(read("verifone-tiki.env"));

        assertEquals(DateSource.CONTENT, config.dateSource());
        assertEquals(List.of("periodEndDate", "closedTime", "date"), config.contentDateFields().names());
        assertEquals(
                List.of("CPJ", "SUM", "MCM", "ISM", "MSM", "HRS", "FGM", "FCF", "LYT", "TPM", "PJR"),
                config.includePrefixes());
        assertTrue(config.packagingEnabled());
        assertEquals("Tiki_8730_IN-47872", config.packaging().storeFolder());
        assertEquals(java.time.LocalTime.of(8, 0), config.packaging().packageAtUtc());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("passport-wolf.env: XML by BusinessDate; PDFs from a second folder by print time")
    void passport() throws IOException {
        final Map<String, String> values = read("passport-wolf.env");
        final Path reports = Files.createDirectories(base.resolve("PassportReports"));
        values.put(MirrorConfig.KEY_INCLUDE_SUBFOLDERS, reports.toString());

        final MirrorConfig config = loadWithLocalFolders(values);

        assertEquals(DateSource.CONTENT, config.dateSource());
        assertEquals(List.of("BusinessDate"), config.contentDateFields().names());
        assertEquals(DateSource.FILENAME, config.subfolderDateSource());
        assertEquals("*yyyyMMddHHmmss.pdf", config.subfolderFileNameDatePattern().template());
        assertEquals(java.time.LocalTime.of(3, 0), config.businessDayStartsAt());
        assertTrue(config.excludePatterns().contains("UNREADEMAIL*"));
        assertTrue(config.packagingEnabled());
        assertEquals("wolf_800_NY-13208", config.packaging().storeFolder());
        assertEquals(java.time.LocalTime.of(8, 0), config.packaging().packageAtUtc());
        assertEquals(List.of(), config.warnings());
    }
}
