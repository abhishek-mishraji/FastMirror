package com.handsoffretail.fastmirror.config;

import com.handsoffretail.fastmirror.entity.GroupBy;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The immutable configuration of one FastMirror process.
 *
 * <p>Built once at startup by {@link #load}, which applies every validation rule and throws
 * {@link ConfigException} with an actionable message on the first failure. There is no hot reload:
 * a running agent's configuration never changes.
 *
 * @param sourceFolder        the POS Link Archive folder, absolute and real; read-only to FastMirror
 * @param mirrorFolder        the destination tree, absolute and real
 * @param includePrefixes     3-character file-name prefixes copied from the source root, upper case;
 *                            empty means no root files are copied
 * @param includeExtensions   extensions copied from the source root, lower case, without the dot
 * @param groupBy             grouping of root files below {@code <yyyy>/<month>}
 * @param includeSubfolders   subfolders of the source root whose files are copied too
 * @param subfolderExtensions extensions copied from those subfolders, lower case, without the dot
 * @param copyFromDate        copy only files created on or after this date; {@code null} copies all
 * @param stabilityMinutes    how long a file's size and timestamp must stay unchanged before copying
 * @param scanIntervalSeconds how often the source folder is scanned
 * @param copyWorkers         copy threads, and therefore the cap on concurrently open source files
 * @param openRetryAttempts   extra attempts to open a locked source file
 * @param openRetryInitialMs  first backoff delay; doubles each attempt
 * @param maxCopyBytes        skip files larger than this; {@code 0} means no limit
 * @param excludePatterns     file-name globs never copied
 * @param statsIntervalMs     how often the counter summary is logged
 * @param logFolder           log destination
 * @param stateFolder         where the copy ledger lives; outside both the source and the mirror
 * @param warnings            non-fatal configuration notes to log at startup
 */
public record MirrorConfig(
        Path sourceFolder,
        Path mirrorFolder,
        List<String> includePrefixes,
        List<String> includeExtensions,
        GroupBy groupBy,
        List<String> includeSubfolders,
        List<String> subfolderExtensions,
        LocalDate copyFromDate,
        int stabilityMinutes,
        int scanIntervalSeconds,
        int copyWorkers,
        int openRetryAttempts,
        long openRetryInitialMs,
        long maxCopyBytes,
        List<String> excludePatterns,
        long statsIntervalMs,
        Path logFolder,
        Path stateFolder,
        List<String> warnings) {

    public static final String KEY_SOURCE_FOLDER = "SOURCE_FOLDER";
    public static final String KEY_MIRROR_FOLDER = "MIRROR_FOLDER";
    public static final String KEY_INCLUDE_PREFIXES = "INCLUDE_PREFIXES";
    public static final String KEY_INCLUDE_EXTENSIONS = "INCLUDE_EXTENSIONS";
    public static final String KEY_GROUP_BY = "GROUP_BY";
    public static final String KEY_INCLUDE_SUBFOLDERS = "INCLUDE_SUBFOLDERS";
    public static final String KEY_SUBFOLDER_EXTENSIONS = "SUBFOLDER_EXTENSIONS";
    public static final String KEY_COPY_FROM_DATE = "COPY_FROM_DATE";
    public static final String KEY_STABILITY_MINUTES = "STABILITY_MINUTES";
    public static final String KEY_SCAN_INTERVAL_SECONDS = "SCAN_INTERVAL_SECONDS";
    public static final String KEY_COPY_WORKERS = "COPY_WORKERS";
    public static final String KEY_OPEN_RETRY_ATTEMPTS = "OPEN_RETRY_ATTEMPTS";
    public static final String KEY_OPEN_RETRY_INITIAL_MS = "OPEN_RETRY_INITIAL_MS";
    public static final String KEY_MAX_COPY_BYTES = "MAX_COPY_BYTES";
    public static final String KEY_EXCLUDE_PATTERNS = "EXCLUDE_PATTERNS";
    public static final String KEY_STATS_INTERVAL_MS = "STATS_INTERVAL_MS";
    public static final String KEY_LOG_FOLDER = "LOG_FOLDER";
    public static final String KEY_STATE_FOLDER = "STATE_FOLDER";

    /** Keys from FastMirror v2 that no longer do anything. Present in an old .env, they only warn. */
    public static final List<String> RETIRED_KEYS =
            List.of("SWEEP_INTERVAL_MS", "CONTENT_VERIFY", "PUBLISH_PARTIAL", "MIRROR_EMPTY_DIRECTORIES");

    public static final String DEFAULT_INCLUDE_EXTENSIONS = "xml";
    public static final GroupBy DEFAULT_GROUP_BY = GroupBy.DATE;
    public static final String DEFAULT_SUBFOLDER_EXTENSIONS = "pdf";
    public static final int DEFAULT_STABILITY_MINUTES = 3;
    public static final int DEFAULT_SCAN_INTERVAL_SECONDS = 60;
    public static final int DEFAULT_COPY_WORKERS = 4;
    public static final int DEFAULT_OPEN_RETRY_ATTEMPTS = 5;
    public static final long DEFAULT_OPEN_RETRY_INITIAL_MS = 5L;
    public static final long DEFAULT_MAX_COPY_BYTES = 0L;
    public static final String DEFAULT_EXCLUDE_PATTERNS = "~$*,*.tmp,*.fmtmp";
    public static final long DEFAULT_STATS_INTERVAL_MS = 60_000L;
    public static final String DEFAULT_LOG_FOLDER = "logs";
    public static final String DEFAULT_STATE_FOLDER = "state";

    /** The suffix of FastMirror's own staging files. */
    public static final String STAGING_SUFFIX = ".fmtmp";

    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9]{3}");
    private static final Pattern EXTENSION = Pattern.compile("[A-Za-z0-9]{1,16}");

    public MirrorConfig {
        Objects.requireNonNull(sourceFolder, "sourceFolder");
        Objects.requireNonNull(mirrorFolder, "mirrorFolder");
        Objects.requireNonNull(groupBy, "groupBy");
        Objects.requireNonNull(logFolder, "logFolder");
        Objects.requireNonNull(stateFolder, "stateFolder");
        includePrefixes = List.copyOf(Objects.requireNonNull(includePrefixes, "includePrefixes"));
        includeExtensions = List.copyOf(Objects.requireNonNull(includeExtensions, "includeExtensions"));
        includeSubfolders = List.copyOf(Objects.requireNonNull(includeSubfolders, "includeSubfolders"));
        subfolderExtensions =
                List.copyOf(Objects.requireNonNull(subfolderExtensions, "subfolderExtensions"));
        excludePatterns = List.copyOf(Objects.requireNonNull(excludePatterns, "excludePatterns"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    /**
     * Read, validate and freeze the configuration.
     *
     * @param lookup        resolves a key to its raw value, or {@code null} if unset; normally
     *                      {@code envFileReader::get}
     * @param baseDirectory the directory the executable lives in, used to resolve a relative
     *                      {@code LOG_FOLDER} or {@code STATE_FOLDER}
     * @throws ConfigException on the first rule that fails
     */
    public static MirrorConfig load(Function<String, String> lookup, Path baseDirectory) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(baseDirectory, "baseDirectory");

        final List<String> warnings = new ArrayList<>();

        // Both required keys present and non-blank.
        final String rawSource = required(lookup, KEY_SOURCE_FOLDER);
        final String rawMirror = required(lookup, KEY_MIRROR_FOLDER);

        final Path sourceInput = toPath(rawSource, KEY_SOURCE_FOLDER);
        final Path mirrorInput = toPath(rawMirror, KEY_MIRROR_FOLDER);

        // Source exists and is a directory.
        if (!Files.exists(sourceInput)) {
            throw new ConfigException(
                    KEY_SOURCE_FOLDER + " does not exist: " + sourceInput
                            + ". Create it, or correct the path in .env.");
        }
        if (!Files.isDirectory(sourceInput)) {
            throw new ConfigException(
                    KEY_SOURCE_FOLDER + " is not a directory: " + sourceInput);
        }

        // Mirror exists, or can be created.
        createDirectoryIfMissing(mirrorInput, KEY_MIRROR_FOLDER);

        // Absolute, normalized, real paths.
        final Path source = toRealPath(sourceInput, KEY_SOURCE_FOLDER);
        final Path mirror = toRealPath(mirrorInput, KEY_MIRROR_FOLDER);

        // The two trees must not overlap, or the agent would copy its own output.
        validateNoOverlap(
                source, KEY_SOURCE_FOLDER, mirror, KEY_MIRROR_FOLDER,
                "This would cause an infinite copy loop.");

        // Source readable, mirror writable.
        validateSourceReadable(source);
        validateMirrorWritable(mirror);

        // What to copy.
        final List<String> includePrefixes = prefixes(value(lookup, KEY_INCLUDE_PREFIXES, ""));
        final List<String> includeExtensions =
                extensions(value(lookup, KEY_INCLUDE_EXTENSIONS, DEFAULT_INCLUDE_EXTENSIONS),
                        KEY_INCLUDE_EXTENSIONS);
        final GroupBy groupBy = groupBy(lookup);
        final List<String> includeSubfolders = subfolders(value(lookup, KEY_INCLUDE_SUBFOLDERS, ""));
        final List<String> subfolderExtensions =
                extensions(value(lookup, KEY_SUBFOLDER_EXTENSIONS, DEFAULT_SUBFOLDER_EXTENSIONS),
                        KEY_SUBFOLDER_EXTENSIONS);
        final LocalDate copyFromDate = date(lookup, KEY_COPY_FROM_DATE);

        if (includePrefixes.isEmpty() && includeSubfolders.isEmpty()) {
            throw new ConfigException(
                    "Nothing to copy: " + KEY_INCLUDE_PREFIXES + " and " + KEY_INCLUDE_SUBFOLDERS
                            + " are both empty. Set at least one, for example "
                            + KEY_INCLUDE_PREFIXES + "=CPJ,SUM,HRS");
        }
        if (includePrefixes.isEmpty()) {
            warnings.add(
                    KEY_INCLUDE_PREFIXES + " is empty, so no files in the root of "
                            + KEY_SOURCE_FOLDER + " will be copied; only " + KEY_INCLUDE_SUBFOLDERS
                            + " are.");
        }

        // Timing and tuning, each parsed and in range.
        final int stabilityMinutes =
                intInRange(lookup, KEY_STABILITY_MINUTES, DEFAULT_STABILITY_MINUTES, 1, 1_440);
        final int scanIntervalSeconds =
                intInRange(lookup, KEY_SCAN_INTERVAL_SECONDS, DEFAULT_SCAN_INTERVAL_SECONDS, 10, 3_600);
        final int copyWorkers =
                intInRange(lookup, KEY_COPY_WORKERS, DEFAULT_COPY_WORKERS, 1, 32);
        final int openRetryAttempts =
                intInRange(lookup, KEY_OPEN_RETRY_ATTEMPTS, DEFAULT_OPEN_RETRY_ATTEMPTS, 0, 20);
        final long openRetryInitialMs =
                longInRange(lookup, KEY_OPEN_RETRY_INITIAL_MS, DEFAULT_OPEN_RETRY_INITIAL_MS, 1L, 1_000L);
        final long maxCopyBytes =
                longInRange(lookup, KEY_MAX_COPY_BYTES, DEFAULT_MAX_COPY_BYTES, 0L, Long.MAX_VALUE);
        final long statsIntervalMs =
                longInRange(lookup, KEY_STATS_INTERVAL_MS, DEFAULT_STATS_INTERVAL_MS, 1_000L, 3_600_000L);

        final List<String> excludePatterns =
                splitList(value(lookup, KEY_EXCLUDE_PATTERNS, DEFAULT_EXCLUDE_PATTERNS));

        final Path logFolder =
                baseDirectory.resolve(value(lookup, KEY_LOG_FOLDER, DEFAULT_LOG_FOLDER))
                        .toAbsolutePath()
                        .normalize();

        // The ledger must survive restarts, must not be uploaded by the S3 agent, and must never be
        // written inside the producer's folder.
        final Path stateInput =
                baseDirectory.resolve(value(lookup, KEY_STATE_FOLDER, DEFAULT_STATE_FOLDER))
                        .toAbsolutePath()
                        .normalize();
        createDirectoryIfMissing(stateInput, KEY_STATE_FOLDER);
        final Path stateFolder = toRealPath(stateInput, KEY_STATE_FOLDER);
        validateNoOverlap(
                source, KEY_SOURCE_FOLDER, stateFolder, KEY_STATE_FOLDER,
                "FastMirror never writes inside the source folder.");
        validateNoOverlap(
                mirror, KEY_MIRROR_FOLDER, stateFolder, KEY_STATE_FOLDER,
                "The copy ledger must not be uploaded with the copied files.");

        for (final String retired : RETIRED_KEYS) {
            final String raw = lookup.apply(retired);
            if (raw != null && !raw.isBlank()) {
                warnings.add(retired + " is no longer used and has been ignored. Remove it from .env.");
            }
        }

        return new MirrorConfig(
                source,
                mirror,
                includePrefixes,
                includeExtensions,
                groupBy,
                includeSubfolders,
                subfolderExtensions,
                copyFromDate,
                stabilityMinutes,
                scanIntervalSeconds,
                copyWorkers,
                openRetryAttempts,
                openRetryInitialMs,
                maxCopyBytes,
                excludePatterns,
                statsIntervalMs,
                logFolder,
                stateFolder,
                warnings);
    }

    /** Whether a file of this size is within the copy limit. */
    public boolean withinSizeLimit(long bytes) {
        return maxCopyBytes <= 0L || bytes <= maxCopyBytes;
    }

    // ---------------------------------------------------------------- validation helpers

    private static void validateNoOverlap(
            Path first, String firstKey, Path second, String secondKey, String consequence) {
        if (first.equals(second)) {
            throw new ConfigException(
                    firstKey + " and " + secondKey + " are the same directory (" + first
                            + "). They must be separate. " + consequence);
        }
        if (second.startsWith(first)) {
            throw new ConfigException(
                    secondKey + " (" + second + ") is inside " + firstKey + " (" + first + "). "
                            + consequence + " Choose a folder outside it.");
        }
        if (first.startsWith(second)) {
            throw new ConfigException(
                    firstKey + " (" + first + ") is inside " + secondKey + " (" + second + "). "
                            + consequence + " Choose a folder outside it.");
        }
    }

    /**
     * Confirms the source is readable by listing it and nothing more.
     *
     * <p>Deliberately not a probe file: FastMirror never creates, writes or deletes anything inside
     * the source folder, not even during startup validation.
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

    private static void createDirectoryIfMissing(Path directory, String key) {
        if (!Files.exists(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (final IOException e) {
                throw new ConfigException(
                        key + " does not exist and could not be created: "
                                + directory + " (" + e.getMessage() + ")", e);
            }
        }
        if (!Files.isDirectory(directory)) {
            throw new ConfigException(key + " is not a directory: " + directory);
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

    private static List<String> prefixes(String raw) {
        final Set<String> prefixes = new LinkedHashSet<>();
        for (final String prefix : splitList(raw)) {
            if (!PREFIX.matcher(prefix).matches()) {
                throw new ConfigException(
                        KEY_INCLUDE_PREFIXES + " entries must be exactly 3 letters or digits, was '"
                                + prefix + "'. Example: " + KEY_INCLUDE_PREFIXES + "=CPJ,SUM,HRS");
            }
            prefixes.add(prefix.toUpperCase(Locale.ROOT));
        }
        return List.copyOf(prefixes);
    }

    private static List<String> extensions(String raw, String key) {
        final Set<String> extensions = new LinkedHashSet<>();
        for (final String entry : splitList(raw)) {
            String extension = entry;
            if (extension.startsWith("*.")) {
                extension = extension.substring(2);
            } else if (extension.startsWith(".")) {
                extension = extension.substring(1);
            }
            if (!EXTENSION.matcher(extension).matches()) {
                throw new ConfigException(
                        key + " entries must be plain extensions such as xml or pdf, was '"
                                + entry + "'.");
            }
            extensions.add(extension.toLowerCase(Locale.ROOT));
        }
        if (extensions.isEmpty()) {
            throw new ConfigException(key + " must name at least one extension, for example " + key + "=xml");
        }
        return List.copyOf(extensions);
    }

    private static GroupBy groupBy(Function<String, String> lookup) {
        final String raw = lookup.apply(KEY_GROUP_BY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_GROUP_BY;
        }
        try {
            return GroupBy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new ConfigException(
                    KEY_GROUP_BY + " must be DATE or MONTH, was '" + raw.trim() + "'.", e);
        }
    }

    private static List<String> subfolders(String raw) {
        final List<String> subfolders = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (final String name : splitList(raw)) {
            if (name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")
                    || name.contains(":")) {
                throw new ConfigException(
                        KEY_INCLUDE_SUBFOLDERS + " entries must be plain folder names directly inside "
                                + KEY_SOURCE_FOLDER + ", such as Journals, was '" + name + "'.");
            }
            if (seen.add(name.toLowerCase(Locale.ROOT))) {
                subfolders.add(name);
            }
        }
        return List.copyOf(subfolders);
    }

    private static LocalDate date(Function<String, String> lookup, String key) {
        final String raw = lookup.apply(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (final DateTimeParseException e) {
            throw new ConfigException(
                    key + " must be a date in the form yyyy-MM-dd, such as 2026-01-01, was '"
                            + raw.trim() + "'.", e);
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

    private static List<String> splitList(String raw) {
        final List<String> items = new ArrayList<>();
        for (final String part : raw.split(",")) {
            final String item = part.trim();
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return List.copyOf(items);
    }
}
