package com.handsoffretail.fastmirror.config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The immutable configuration of one FastMirror process (SRS 7).
 *
 * <p>Built once at startup by {@link #load}, which applies every validation rule of SRS 7.3 and
 * throws {@link ConfigException} with an actionable message on the first failure. There is no hot
 * reload: a running agent's configuration never changes.
 *
 * @param sourceFolder           the watched tree, absolute and real; read-only to FastMirror
 * @param mirrorFolder           the destination tree, absolute and real
 * @param copyWorkers            copy threads, and therefore the hard cap on concurrently open
 *                               source handles (NI-10)
 * @param openRetryAttempts      extra attempts to open a locked source file (FR-10)
 * @param openRetryInitialMs     first backoff delay; doubles each attempt
 * @param sweepIntervalMs        periodic sweep interval; {@code 0} disables the sweep
 * @param contentVerify          also compare SHA-256 when deciding whether to re-copy (SRS 9.4)
 * @param publishPartial         publish bytes captured before a transfer failure (PD-2, FR-27)
 * @param mirrorEmptyDirectories create source directories in the mirror even when empty (FR-25)
 * @param maxCopyBytes           skip files larger than this; {@code 0} means no limit
 * @param excludePatterns        file-name globs never copied (FR-18)
 * @param statsIntervalMs        how often the counter summary is logged (FR-21)
 * @param logFolder              log destination
 */
public record MirrorConfig(
        Path sourceFolder,
        Path mirrorFolder,
        int copyWorkers,
        int openRetryAttempts,
        long openRetryInitialMs,
        long sweepIntervalMs,
        boolean contentVerify,
        boolean publishPartial,
        boolean mirrorEmptyDirectories,
        long maxCopyBytes,
        List<String> excludePatterns,
        long statsIntervalMs,
        Path logFolder) {

    public static final String KEY_SOURCE_FOLDER = "SOURCE_FOLDER";
    public static final String KEY_MIRROR_FOLDER = "MIRROR_FOLDER";
    public static final String KEY_COPY_WORKERS = "COPY_WORKERS";
    public static final String KEY_OPEN_RETRY_ATTEMPTS = "OPEN_RETRY_ATTEMPTS";
    public static final String KEY_OPEN_RETRY_INITIAL_MS = "OPEN_RETRY_INITIAL_MS";
    public static final String KEY_SWEEP_INTERVAL_MS = "SWEEP_INTERVAL_MS";
    public static final String KEY_CONTENT_VERIFY = "CONTENT_VERIFY";
    public static final String KEY_PUBLISH_PARTIAL = "PUBLISH_PARTIAL";
    public static final String KEY_MIRROR_EMPTY_DIRECTORIES = "MIRROR_EMPTY_DIRECTORIES";
    public static final String KEY_MAX_COPY_BYTES = "MAX_COPY_BYTES";
    public static final String KEY_EXCLUDE_PATTERNS = "EXCLUDE_PATTERNS";
    public static final String KEY_STATS_INTERVAL_MS = "STATS_INTERVAL_MS";
    public static final String KEY_LOG_FOLDER = "LOG_FOLDER";

    public static final int DEFAULT_COPY_WORKERS = 4;
    public static final int DEFAULT_OPEN_RETRY_ATTEMPTS = 5;
    public static final long DEFAULT_OPEN_RETRY_INITIAL_MS = 5L;
    public static final long DEFAULT_SWEEP_INTERVAL_MS = 500L;
    public static final boolean DEFAULT_CONTENT_VERIFY = false;
    public static final boolean DEFAULT_PUBLISH_PARTIAL = true;
    public static final boolean DEFAULT_MIRROR_EMPTY_DIRECTORIES = false;
    public static final long DEFAULT_MAX_COPY_BYTES = 0L;
    public static final String DEFAULT_EXCLUDE_PATTERNS = "~$*,*.tmp,*.fmtmp";
    public static final long DEFAULT_STATS_INTERVAL_MS = 60_000L;
    public static final String DEFAULT_LOG_FOLDER = "logs";

    /** The suffix of FastMirror's own staging files (SRS 3). */
    public static final String STAGING_SUFFIX = ".fmtmp";

    public MirrorConfig {
        Objects.requireNonNull(sourceFolder, "sourceFolder");
        Objects.requireNonNull(mirrorFolder, "mirrorFolder");
        Objects.requireNonNull(logFolder, "logFolder");
        excludePatterns = List.copyOf(Objects.requireNonNull(excludePatterns, "excludePatterns"));
    }

    /**
     * Read, validate and freeze the configuration.
     *
     * @param lookup        resolves a key to its raw value, or {@code null} if unset; normally
     *                      {@code envFileReader::get}
     * @param baseDirectory the directory the executable lives in, used to resolve a relative
     *                      {@code LOG_FOLDER}
     * @throws ConfigException on the first rule that fails (SRS 7.3)
     */
    public static MirrorConfig load(Function<String, String> lookup, Path baseDirectory) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(baseDirectory, "baseDirectory");

        // CV-1: both required keys present and non-blank.
        final String rawSource = required(lookup, KEY_SOURCE_FOLDER);
        final String rawMirror = required(lookup, KEY_MIRROR_FOLDER);

        final Path sourceInput = toPath(rawSource, KEY_SOURCE_FOLDER);
        final Path mirrorInput = toPath(rawMirror, KEY_MIRROR_FOLDER);

        // CV-2: source exists and is a directory.
        if (!Files.exists(sourceInput)) {
            throw new ConfigException(
                    KEY_SOURCE_FOLDER + " does not exist: " + sourceInput
                            + ". Create it, or correct the path in .env.");
        }
        if (!Files.isDirectory(sourceInput)) {
            throw new ConfigException(
                    KEY_SOURCE_FOLDER + " is not a directory: " + sourceInput);
        }

        // CV-3: mirror exists, or can be created.
        if (!Files.exists(mirrorInput)) {
            try {
                Files.createDirectories(mirrorInput);
            } catch (final IOException e) {
                throw new ConfigException(
                        KEY_MIRROR_FOLDER + " does not exist and could not be created: "
                                + mirrorInput + " (" + e.getMessage() + ")", e);
            }
        }
        if (!Files.isDirectory(mirrorInput)) {
            throw new ConfigException(
                    KEY_MIRROR_FOLDER + " is not a directory: " + mirrorInput);
        }

        // CV-4: store absolute, normalized, real paths.
        final Path source = toRealPath(sourceInput, KEY_SOURCE_FOLDER);
        final Path mirror = toRealPath(mirrorInput, KEY_MIRROR_FOLDER);

        // CV-5: the two trees must not overlap, or the agent would copy its own output forever.
        validateNoOverlap(source, mirror);

        // CV-6: source readable, mirror writable.
        validateSourceReadable(source);
        validateMirrorWritable(mirror);

        // CV-7: every numeric and boolean key parses and is in range.
        final int copyWorkers =
                intInRange(lookup, KEY_COPY_WORKERS, DEFAULT_COPY_WORKERS, 1, 32);
        final int openRetryAttempts =
                intInRange(lookup, KEY_OPEN_RETRY_ATTEMPTS, DEFAULT_OPEN_RETRY_ATTEMPTS, 0, 20);
        final long openRetryInitialMs =
                longInRange(lookup, KEY_OPEN_RETRY_INITIAL_MS, DEFAULT_OPEN_RETRY_INITIAL_MS, 1L, 1_000L);
        final long sweepIntervalMs =
                longInRange(lookup, KEY_SWEEP_INTERVAL_MS, DEFAULT_SWEEP_INTERVAL_MS, 0L, 600_000L);
        final boolean contentVerify =
                bool(lookup, KEY_CONTENT_VERIFY, DEFAULT_CONTENT_VERIFY);
        final boolean publishPartial =
                bool(lookup, KEY_PUBLISH_PARTIAL, DEFAULT_PUBLISH_PARTIAL);
        final boolean mirrorEmptyDirectories =
                bool(lookup, KEY_MIRROR_EMPTY_DIRECTORIES, DEFAULT_MIRROR_EMPTY_DIRECTORIES);
        final long maxCopyBytes =
                longInRange(lookup, KEY_MAX_COPY_BYTES, DEFAULT_MAX_COPY_BYTES, 0L, Long.MAX_VALUE);
        final long statsIntervalMs =
                longInRange(lookup, KEY_STATS_INTERVAL_MS, DEFAULT_STATS_INTERVAL_MS, 1_000L, 3_600_000L);

        final List<String> excludePatterns =
                splitPatterns(value(lookup, KEY_EXCLUDE_PATTERNS, DEFAULT_EXCLUDE_PATTERNS));

        final String rawLogFolder = value(lookup, KEY_LOG_FOLDER, DEFAULT_LOG_FOLDER);
        final Path logFolder = baseDirectory.resolve(rawLogFolder).toAbsolutePath().normalize();

        return new MirrorConfig(
                source,
                mirror,
                copyWorkers,
                openRetryAttempts,
                openRetryInitialMs,
                sweepIntervalMs,
                contentVerify,
                publishPartial,
                mirrorEmptyDirectories,
                maxCopyBytes,
                excludePatterns,
                statsIntervalMs,
                logFolder);
    }

    /** Whether the periodic sweep is enabled (FR-14). */
    public boolean sweepEnabled() {
        return sweepIntervalMs > 0L;
    }

    /** Whether a file of this size is within the copy limit (FR-19). */
    public boolean withinSizeLimit(long bytes) {
        return maxCopyBytes <= 0L || bytes <= maxCopyBytes;
    }

    // ---------------------------------------------------------------- validation helpers

    private static void validateNoOverlap(Path source, Path mirror) {
        if (source.equals(mirror)) {
            throw new ConfigException(
                    KEY_SOURCE_FOLDER + " and " + KEY_MIRROR_FOLDER
                            + " are the same directory (" + source
                            + "). They must be separate, or every copy would re-trigger itself.");
        }
        if (mirror.startsWith(source)) {
            throw new ConfigException(
                    KEY_MIRROR_FOLDER + " (" + mirror + ") is inside " + KEY_SOURCE_FOLDER
                            + " (" + source + "). This would cause an infinite copy loop."
                            + " Choose a mirror folder outside the watched tree.");
        }
        if (source.startsWith(mirror)) {
            throw new ConfigException(
                    KEY_SOURCE_FOLDER + " (" + source + ") is inside " + KEY_MIRROR_FOLDER
                            + " (" + mirror + "). This would cause an infinite copy loop."
                            + " Choose a mirror folder outside the watched tree.");
        }
    }

    /**
     * Confirms the source is readable by listing it and nothing more.
     *
     * <p>Deliberately not a probe file: FastMirror never creates, writes or deletes anything inside
     * the source folder, not even during startup validation (NI-7).
     */
    @SuppressWarnings("try") // opening and closing the stream IS the readability test
    private static void validateSourceReadable(Path source) {
        try (DirectoryStream<Path> ignored = Files.newDirectoryStream(source)) {
            // Opening and closing the stream is the whole test.
        } catch (final IOException e) {
            throw new ConfigException(
                    KEY_SOURCE_FOLDER + " is not readable: " + source
                            + " (" + e.getMessage() + "). Check the account's permissions.", e);
        }
    }

    /** Confirms the mirror is writable with a probe file, which is safe because the mirror is ours. */
    private static void validateMirrorWritable(Path mirror) {
        Path probe = null;
        try {
            probe = Files.createTempFile(mirror, "fastmirror-probe-", STAGING_SUFFIX);
        } catch (final IOException e) {
            throw new ConfigException(
                    KEY_MIRROR_FOLDER + " is not writable: " + mirror
                            + " (" + e.getMessage() + "). Check the account's permissions"
                            + " and the free space on that volume.", e);
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (final IOException e) {
                    throw new ConfigException(
                            "Could not remove the write probe " + probe
                                    + " (" + e.getMessage() + ").", e);
                }
            }
        }
    }

    private static String required(Function<String, String> lookup, String key) {
        final String raw = lookup.apply(key);
        if (raw == null || raw.isBlank()) {
            throw new ConfigException(
                    key + " is required but was not set. Add it to .env, for example"
                            + " " + key + "=C:/path/to/folder");
        }
        return raw.trim();
    }

    private static String value(Function<String, String> lookup, String key, String fallback) {
        final String raw = lookup.apply(key);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static Path toPath(String raw, String key) {
        try {
            return Path.of(raw).toAbsolutePath().normalize();
        } catch (final RuntimeException e) {
            throw new ConfigException(key + " is not a valid path: " + raw, e);
        }
    }

    private static Path toRealPath(Path path, String key) {
        try {
            return path.toRealPath();
        } catch (final IOException e) {
            throw new ConfigException(
                    key + " could not be resolved: " + path + " (" + e.getMessage() + ")", e);
        }
    }

    private static int intInRange(
            Function<String, String> lookup, String key, int fallback, int min, int max) {
        final long parsed = longInRange(lookup, key, fallback, min, max);
        return (int) parsed;
    }

    private static long longInRange(
            Function<String, String> lookup, String key, long fallback, long min, long max) {
        final String raw = lookup.apply(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        final long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        } catch (final NumberFormatException e) {
            throw new ConfigException(
                    key + " must be a whole number, was '" + raw.trim() + "'."
                            + " Valid range is " + min + " to " + max + ".", e);
        }
        if (parsed < min || parsed > max) {
            throw new ConfigException(
                    key + " is out of range: " + parsed + "."
                            + " Valid range is " + min + " to " + max + ".");
        }
        return parsed;
    }

    private static boolean bool(Function<String, String> lookup, String key, boolean fallback) {
        final String raw = lookup.apply(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        final String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        throw new ConfigException(
                key + " must be exactly 'true' or 'false', was '" + raw.trim() + "'.");
    }

    private static List<String> splitPatterns(String raw) {
        final List<String> patterns = new ArrayList<>();
        for (final String part : raw.split(",")) {
            final String pattern = part.trim();
            if (!pattern.isEmpty()) {
                patterns.add(pattern);
            }
        }
        return List.copyOf(patterns);
    }
}
