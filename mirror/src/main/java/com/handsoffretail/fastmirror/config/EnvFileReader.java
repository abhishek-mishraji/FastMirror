package com.handsoffretail.fastmirror.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads configuration from a {@code .env} file, falling back to OS environment variables.
 *
 * <p>Resolution order, matching the existing HOR AWS Sync Agent so operators configure both the
 * same way (SRS 7):
 *
 * <ol>
 *   <li>{@code .env} beside the executable
 *   <li>{@code .env} in the parent directory
 *   <li>OS environment variables
 * </ol>
 *
 * <p>Parsing is deliberately minimal: {@code KEY=VALUE}, one per line. Blank lines and lines whose
 * first non-blank character is {@code #} are ignored. Keys and values are trimmed. A value may
 * contain {@code =}; only the first one separates. No quoting, no escapes, no interpolation —
 * anything cleverer would be a source of silent misconfiguration.
 */
public final class EnvFileReader {

    private static final String FILE_NAME = ".env";

    private final Map<String, String> fileValues;
    private final Path sourceFile;

    /**
     * @param baseDirectory the directory the executable lives in
     */
    public EnvFileReader(Path baseDirectory) {
        Objects.requireNonNull(baseDirectory, "baseDirectory");
        final Path base = baseDirectory.toAbsolutePath().normalize();

        Path found = null;
        final Path beside = base.resolve(FILE_NAME);
        if (Files.isRegularFile(beside)) {
            found = beside;
        } else {
            final Path parent = base.getParent();
            if (parent != null) {
                final Path above = parent.resolve(FILE_NAME);
                if (Files.isRegularFile(above)) {
                    found = above;
                }
            }
        }

        this.sourceFile = found;
        this.fileValues = found == null ? Map.of() : parse(found);
    }

    /**
     * The value for a key: the {@code .env} file first, then the OS environment.
     *
     * @return the trimmed value, or {@code null} if the key is set nowhere
     */
    public String get(String key) {
        Objects.requireNonNull(key, "key");
        final String fromFile = fileValues.get(key);
        if (fromFile != null) {
            return fromFile;
        }
        return System.getenv(key);
    }

    /** The {@code .env} that was actually loaded, or {@code null} if none was found. */
    public Path sourceFile() {
        return sourceFile;
    }

    /** The keys read from the file, for the startup log line. Never includes OS environment keys. */
    public Map<String, String> fileValues() {
        return fileValues;
    }

    private static Map<String, String> parse(Path file) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }

        final Map<String, String> values = new LinkedHashMap<>();
        for (final String raw : lines) {
            final String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            final int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            final String key = line.substring(0, separator).trim();
            final String value = line.substring(separator + 1).trim();
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return Collections.unmodifiableMap(values);
    }
}
