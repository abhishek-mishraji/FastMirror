package com.handsoffretail.fastmirror.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** Reads a shipped file the way the agent does, beside an exe in {@code base}. */
    private Map<String, String> read(String fileName) throws IOException {
        Files.copy(EXAMPLES.resolve(fileName), base.resolve(".env"));
        return new HashMap<>(new EnvFileReader(base).fileValues());
    }

    /** The folders are specific to a store PC, so the test points them at temporary ones. */
    private MirrorConfig loadWithLocalFolders(Map<String, String> values) {
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, source.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirror.toString());
        return MirrorConfig.load(values::get, base);
    }

    @Test
    @DisplayName("verifone-tiki.env: business date from periodEndDate, then closedTime")
    void verifone() throws IOException {
        final MirrorConfig config = loadWithLocalFolders(read("verifone-tiki.env"));

        assertEquals(DateSource.CONTENT, config.dateSource());
        assertEquals(List.of("periodEndDate", "closedTime"), config.contentDateFields().names());
        assertEquals(
                List.of("CPJ", "SUM", "MCM", "ISM", "MSM", "HRS", "FGM", "FCF", "LYT", "TPM"),
                config.includePrefixes());
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
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("passport-wolf.env refuses to start until its <STORE> placeholder is replaced")
    void passportPlaceholderMustBeReplaced() throws IOException {
        final Map<String, String> values = read("passport-wolf.env");
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, source.toString());
        values.put(MirrorConfig.KEY_INCLUDE_SUBFOLDERS, "");

        final ConfigException thrown =
                assertThrows(ConfigException.class, () -> MirrorConfig.load(values::get, base));

        assertTrue(thrown.getMessage().contains(MirrorConfig.KEY_MIRROR_FOLDER), thrown.getMessage());
    }
}
