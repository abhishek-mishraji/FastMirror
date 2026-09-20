package com.handsoffretail.fastmirror.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvFileReaderTest {

    @TempDir Path base;

    private void writeEnv(Path directory, String content) throws IOException {
        Files.writeString(directory.resolve(".env"), content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("reads KEY=VALUE pairs from a .env beside the executable")
    void readsBesideExecutable() throws IOException {
        writeEnv(base, "SOURCE_FOLDER=C:/Gemcom/bin/out\nMIRROR_FOLDER=C:/temp\n");

        final EnvFileReader reader = new EnvFileReader(base);

        assertEquals("C:/Gemcom/bin/out", reader.get("SOURCE_FOLDER"));
        assertEquals("C:/temp", reader.get("MIRROR_FOLDER"));
        assertEquals(base.resolve(".env"), reader.sourceFile());
    }

    @Test
    @DisplayName("falls back to a .env in the parent directory")
    void fallsBackToParent() throws IOException {
        final Path child = Files.createDirectories(base.resolve("bin"));
        writeEnv(base, "SOURCE_FOLDER=C:/from/parent\n");

        final EnvFileReader reader = new EnvFileReader(child);

        assertEquals("C:/from/parent", reader.get("SOURCE_FOLDER"));
    }

    @Test
    @DisplayName("a .env beside the executable wins over the one above it")
    void besideBeatsParent() throws IOException {
        final Path child = Files.createDirectories(base.resolve("bin"));
        writeEnv(base, "SOURCE_FOLDER=C:/from/parent\n");
        writeEnv(child, "SOURCE_FOLDER=C:/from/beside\n");

        assertEquals("C:/from/beside", new EnvFileReader(child).get("SOURCE_FOLDER"));
    }

    @Test
    @DisplayName("comments, blank lines and malformed lines are ignored")
    void ignoresNoise() throws IOException {
        writeEnv(
                base,
                """
                # a comment
                   # an indented comment

                SOURCE_FOLDER=C:/real
                this line has no separator
                =novalue
                """);

        final EnvFileReader reader = new EnvFileReader(base);

        assertEquals("C:/real", reader.get("SOURCE_FOLDER"));
        assertEquals(1, reader.fileValues().size());
    }

    @Test
    @DisplayName("keys and values are trimmed, and only the first = separates")
    void trimsAndSplitsOnFirstSeparator() throws IOException {
        writeEnv(base, "  EXCLUDE_PATTERNS  =  ~$*,*.tmp  \nODD=a=b=c\n");

        final EnvFileReader reader = new EnvFileReader(base);

        assertEquals("~$*,*.tmp", reader.get("EXCLUDE_PATTERNS"));
        assertEquals("a=b=c", reader.get("ODD"));
    }

    @Test
    @DisplayName("an unset key returns null rather than a blank string")
    void unsetKeyIsNull() {
        final EnvFileReader reader = new EnvFileReader(base);

        assertNull(reader.get("FASTMIRROR_KEY_THAT_IS_NOT_SET_ANYWHERE"));
        assertNull(reader.sourceFile(), "no .env should have been found");
        assertTrue(reader.fileValues().isEmpty());
    }
}
