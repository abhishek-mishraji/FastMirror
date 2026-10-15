package com.handsoffretail.fastmirror.config;

import com.handsoffretail.fastmirror.entity.DateSource;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * @param includeSubfolders   extra folders whose files are copied too, by name: the last part of
 *                            each full path given
 * @param externalSubfolders  those folders' full paths, by name; they may live anywhere outside the
 *                            source, mirror and state folders
 * @param subfolderExtensions extensions copied from those subfolders, lower case, without the dot
 * @param copyFromDate        copy only files created on or after this date; {@code null} copies all
 * @param timeZone            the zone in which a file's creation time is turned into a date, for
 *                            its destination folder and for {@code COPY_FROM_DATE}
 * @param dateSource          where a root file's date comes from
 * @param fileNameDatePattern where the date sits in a root file's name; {@code null} unless
 *                            {@code dateSource} is {@code FILENAME}
 * @param subfolderDateSource where a subfolder file's date comes from
 * @param subfolderFileNameDatePattern the same for subfolder files
 * @param contentDateFields   the fields holding a root file's business date; {@code null} unless
 *                            {@code dateSource} is {@code CONTENT}
 * @param subfolderContentDateFields the same for subfolder files
 * @param contentScanBytes    how much of the start of a file is searched for those fields
 * @param businessDayStartsAt a timestamp before this time of day belongs to the previous business
 *                            day; applies to creation times and file-name times, never to
 *                            content dates
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
 * @param packaging           day packaging into the outbox; {@code null} when it is off
 * @param warnings            non-fatal configuration notes to log at startup
 */
public record MirrorConfig(
        Path sourceFolder,
        Path mirrorFolder,
        List<String> includePrefixes,
        List<String> includeExtensions,
        List<String> includeSubfolders,
        Map<String, Path> externalSubfolders,
        List<String> subfolderExtensions,
        LocalDate copyFromDate,
        ZoneId timeZone,
        DateSource dateSource,
        FileNameDatePattern fileNameDatePattern,
        DateSource subfolderDateSource,
        FileNameDatePattern subfolderFileNameDatePattern,
        ContentDateFields contentDateFields,
        ContentDateFields subfolderContentDateFields,
        int contentScanBytes,
        LocalTime businessDayStartsAt,
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
        PackageConfig packaging,
        List<String> warnings) {

    public static final String KEY_SOURCE_FOLDER = "SOURCE_FOLDER";
    public static final String KEY_MIRROR_FOLDER = "MIRROR_FOLDER";
    public static final String KEY_INCLUDE_PREFIXES = "INCLUDE_PREFIXES";
    public static final String KEY_INCLUDE_EXTENSIONS = "INCLUDE_EXTENSIONS";
    public static final String KEY_INCLUDE_SUBFOLDERS = "INCLUDE_SUBFOLDERS";
    public static final String KEY_SUBFOLDER_EXTENSIONS = "SUBFOLDER_EXTENSIONS";
    public static final String KEY_COPY_FROM_DATE = "COPY_FROM_DATE";
    public static final String KEY_TIME_ZONE = "TIME_ZONE";
    public static final String KEY_DATE_SOURCE = "DATE_SOURCE";
    public static final String KEY_FILENAME_DATE_PATTERN = "FILENAME_DATE_PATTERN";
    public static final String KEY_SUBFOLDER_DATE_SOURCE = "SUBFOLDER_DATE_SOURCE";
    public static final String KEY_SUBFOLDER_FILENAME_DATE_PATTERN = "SUBFOLDER_FILENAME_DATE_PATTERN";
    public static final String KEY_CONTENT_DATE_FIELDS = "CONTENT_DATE_FIELDS";
    public static final String KEY_SUBFOLDER_CONTENT_DATE_FIELDS = "SUBFOLDER_CONTENT_DATE_FIELDS";
    public static final String KEY_CONTENT_SCAN_BYTES = "CONTENT_SCAN_BYTES";
    public static final String KEY_BUSINESS_DAY_STARTS_AT = "BUSINESS_DAY_STARTS_AT";
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
    public static final String KEY_PACKAGE_ENABLED = "PACKAGE_ENABLED";
    public static final String KEY_STORE_FOLDER = "STORE_FOLDER";
    public static final String KEY_OUTBOX_FOLDER = "OUTBOX_FOLDER";
    public static final String KEY_PACKAGE_STAGING_FOLDER = "PACKAGE_STAGING_FOLDER";
    public static final String KEY_PACKAGE_AT_UTC = "PACKAGE_AT_UTC";
    public static final String KEY_PACKAGE_FROM_DATE = "PACKAGE_FROM_DATE";
    public static final String KEY_OUTBOX_KEEP_DAYS = "OUTBOX_KEEP_DAYS";

    public static final String DEFAULT_INCLUDE_EXTENSIONS = "xml";
    public static final String DEFAULT_SUBFOLDER_EXTENSIONS = "pdf";
    public static final String DEFAULT_TIME_ZONE = "America/New_York";
    public static final DateSource DEFAULT_DATE_SOURCE = DateSource.CREATED;
    public static final int DEFAULT_CONTENT_SCAN_BYTES = 65_536;
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
    public static final LocalTime DEFAULT_PACKAGE_AT_UTC = LocalTime.of(8, 0);
    public static final int DEFAULT_OUTBOX_KEEP_DAYS = 0;
    /** Appended to the outbox folder's name for the default staging folder beside it. */
    public static final String STAGING_FOLDER_SUFFIX = "-staging";

    /** The suffix of FastMirror's own staging files. */
    public static final String STAGING_SUFFIX = ".fmtmp";

    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9]{3}");
    private static final Pattern EXTENSION = Pattern.compile("[A-Za-z0-9]{1,16}");
    /** Safe in a Windows folder name, a zip name and an S3 key alike. */
    private static final Pattern STORE = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    public MirrorConfig {
        Objects.requireNonNull(sourceFolder, "sourceFolder");
        Objects.requireNonNull(mirrorFolder, "mirrorFolder");
        Objects.requireNonNull(timeZone, "timeZone");
        Objects.requireNonNull(dateSource, "dateSource");
        Objects.requireNonNull(subfolderDateSource, "subfolderDateSource");
        if (dateSource == DateSource.FILENAME) {
            Objects.requireNonNull(fileNameDatePattern, "fileNameDatePattern");
        }
        if (subfolderDateSource == DateSource.FILENAME) {
            Objects.requireNonNull(subfolderFileNameDatePattern, "subfolderFileNameDatePattern");
        }
        if (dateSource == DateSource.CONTENT) {
            Objects.requireNonNull(contentDateFields, "contentDateFields");
        }
        if (subfolderDateSource == DateSource.CONTENT) {
            Objects.requireNonNull(subfolderContentDateFields, "subfolderContentDateFields");
        }
        Objects.requireNonNull(logFolder, "logFolder");
        Objects.requireNonNull(stateFolder, "stateFolder");
        includePrefixes = List.copyOf(Objects.requireNonNull(includePrefixes, "includePrefixes"));
        includeExtensions = List.copyOf(Objects.requireNonNull(includeExtensions, "includeExtensions"));
        includeSubfolders = List.copyOf(Objects.requireNonNull(includeSubfolders, "includeSubfolders"));
        externalSubfolders = Map.copyOf(Objects.requireNonNull(externalSubfolders, "externalSubfolders"));
        Objects.requireNonNull(businessDayStartsAt, "businessDayStartsAt");
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

        // A source path naming a file is a mistake. A source folder that is not there yet is not:
        // a network drive or VM share can connect after the agent starts, and once started the
        // agent must keep running. The scan keeps checking and starts copying when it appears.
        if (Files.exists(sourceInput) && !Files.isDirectory(sourceInput)) {
            throw new ConfigException(
                    KEY_SOURCE_FOLDER + " is not a directory: " + sourceInput);
        }
        final boolean sourceAvailable = Files.isDirectory(sourceInput);
        if (!sourceAvailable) {
            warnings.add(
                    KEY_SOURCE_FOLDER + " is not available yet: " + sourceInput
                            + ". FastMirror keeps running and starts copying when it appears."
                            + " If the path is wrong, correct it in .env and restart.");
        }

        // Mirror exists, or can be created.
        createDirectoryIfMissing(mirrorInput, KEY_MIRROR_FOLDER);

        // Absolute, normalized, real paths.
        final Path source = sourceAvailable ? toRealPath(sourceInput, KEY_SOURCE_FOLDER) : sourceInput;
        final Path mirror = toRealPath(mirrorInput, KEY_MIRROR_FOLDER);

        // The two trees must not overlap, or the agent would copy its own output.
        validateNoOverlap(
                source, KEY_SOURCE_FOLDER, mirror, KEY_MIRROR_FOLDER,
                "This would cause an infinite copy loop.");

        // Source readable, mirror writable. An unreadable source is reported, not fatal, for the
        // same reason a missing one is: access to a share can come back.
        if (sourceAvailable && !sourceReadable(source)) {
            warnings.add(
                    KEY_SOURCE_FOLDER + " cannot be read right now: " + source
                            + ". FastMirror keeps running and retries every scan. Check the"
                            + " account's permissions if this persists.");
        }
        validateMirrorWritable(mirror);

        // What to copy.
        final List<String> includePrefixes = prefixes(value(lookup, KEY_INCLUDE_PREFIXES, ""));
        final List<String> includeExtensions =
                extensions(value(lookup, KEY_INCLUDE_EXTENSIONS, DEFAULT_INCLUDE_EXTENSIONS),
                        KEY_INCLUDE_EXTENSIONS);
        final Subfolders subfolders = subfolders(value(lookup, KEY_INCLUDE_SUBFOLDERS, ""));
        final List<String> includeSubfolders = subfolders.names();
        final List<String> subfolderExtensions =
                extensions(value(lookup, KEY_SUBFOLDER_EXTENSIONS, DEFAULT_SUBFOLDER_EXTENSIONS),
                        KEY_SUBFOLDER_EXTENSIONS);
        final LocalDate copyFromDate = date(lookup, KEY_COPY_FROM_DATE);
        final ZoneId timeZone = timeZone(value(lookup, KEY_TIME_ZONE, DEFAULT_TIME_ZONE));
        final DateSource dateSource = dateSource(lookup, KEY_DATE_SOURCE);
        final FileNameDatePattern fileNameDatePattern =
                datePattern(lookup, KEY_FILENAME_DATE_PATTERN, dateSource, KEY_DATE_SOURCE, warnings);
        final DateSource subfolderDateSource = dateSource(lookup, KEY_SUBFOLDER_DATE_SOURCE);
        final FileNameDatePattern subfolderFileNameDatePattern =
                datePattern(
                        lookup,
                        KEY_SUBFOLDER_FILENAME_DATE_PATTERN,
                        subfolderDateSource,
                        KEY_SUBFOLDER_DATE_SOURCE,
                        warnings);
        final ContentDateFields contentDateFields =
                contentFields(lookup, KEY_CONTENT_DATE_FIELDS, dateSource, KEY_DATE_SOURCE, warnings);
        final ContentDateFields subfolderContentDateFields =
                contentFields(
                        lookup,
                        KEY_SUBFOLDER_CONTENT_DATE_FIELDS,
                        subfolderDateSource,
                        KEY_SUBFOLDER_DATE_SOURCE,
                        warnings);
        final LocalTime businessDayStartsAt = timeOfDay(lookup, KEY_BUSINESS_DAY_STARTS_AT);
        final int contentScanBytes =
                intInRange(
                        lookup, KEY_CONTENT_SCAN_BYTES, DEFAULT_CONTENT_SCAN_BYTES, 1_024, 16_777_216);

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

        final Map<String, Path> externalSubfolders =
                externalFolders(subfolders.external(), source, mirror, stateFolder, warnings);

        final PackageConfig packaging =
                packaging(lookup, source, mirror, stateFolder, externalSubfolders);

        return new MirrorConfig(
                source,
                mirror,
                includePrefixes,
                includeExtensions,
                includeSubfolders,
                externalSubfolders,
                subfolderExtensions,
                copyFromDate,
                timeZone,
                dateSource,
                fileNameDatePattern,
                subfolderDateSource,
                subfolderFileNameDatePattern,
                contentDateFields,
                subfolderContentDateFields,
                contentScanBytes,
                businessDayStartsAt,
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
                packaging,
                warnings);
    }

    /** Whether day packaging is on. */
    public boolean packagingEnabled() {
        return packaging != null;
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
     * Whether the source can be listed.
     *
     * <p>Deliberately not a probe file: FastMirror never creates, writes or deletes anything inside
     * the source folder, not even during startup validation.
     */
    @SuppressWarnings("try") // opening and closing the stream IS the readability test
    private static boolean sourceReadable(Path source) {
        try (DirectoryStream<Path> ignored = Files.newDirectoryStream(source)) {
            return true;
        } catch (final IOException e) {
            return false;
        }
    }

    /** Confirms the mirror is writable with a probe file, which is safe because the mirror is ours. */
    private static void validateMirrorWritable(Path mirror) {
        validateWritable(mirror, KEY_MIRROR_FOLDER);
    }

    /**
     * Confirms a folder of ours is writable with a probe file. The probe's name starts with
     * {@code ~$}, the one kind of name the S3 agent never uploads, because it can appear in the
     * outbox for an instant.
     */
    private static void validateWritable(Path folder, String key) {
        Path probe = null;
        try {
            probe = Files.createTempFile(folder, "~$fastmirror-probe-", STAGING_SUFFIX);
        } catch (final IOException e) {
            throw new ConfigException(
                    key + " is not writable: " + folder
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
        return required(lookup, key, "C:/path/to/folder");
    }

    private static String required(Function<String, String> lookup, String key, String example) {
        final String raw = lookup.apply(key);
        if (raw == null || raw.isBlank()) {
            throw new ConfigException(
                    key + " is required but was not set. Add it to .env, for example"
                            + " " + key + "=" + example);
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

    /**
     * @param names    every folder by name, in the order given
     * @param external the same folders' full paths, by name, not yet validated
     */
    private record Subfolders(List<String> names, Map<String, Path> external) {}

    /**
     * Each entry is a full path to a folder anywhere ({@code Z:/PassportReports}). The folder's name
     * becomes the type folder of its files in the mirror, so names must be unique.
     */
    private static Subfolders subfolders(String raw) {
        final List<String> names = new ArrayList<>();
        final Map<String, Path> external = new LinkedHashMap<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (final String entry : splitList(raw)) {
            final Path path = asAbsolutePath(entry);
            final Path last = path.getFileName();
            if (last == null) {
                throw new ConfigException(
                        KEY_INCLUDE_SUBFOLDERS + " cannot be a whole drive, was '" + entry
                                + "'. Name a folder, such as Z:/PassportReports.");
            }
            final String name = last.toString();
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new ConfigException(
                        KEY_INCLUDE_SUBFOLDERS + " has two folders named '" + name + "'. Each"
                                + " folder's name becomes its folder in the mirror, so they must differ.");
            }
            names.add(name);
            external.put(name, path);
        }
        return new Subfolders(List.copyOf(names), external);
    }

    /** A full path such as Z:/PassportReports; anything else is a configuration error. */
    private static Path asAbsolutePath(String entry) {
        final Path path;
        try {
            path = Path.of(entry);
        } catch (final RuntimeException e) {
            throw new ConfigException(KEY_INCLUDE_SUBFOLDERS + " entry is not a valid path: " + entry, e);
        }
        if (!path.isAbsolute()) {
            throw new ConfigException(
                    KEY_INCLUDE_SUBFOLDERS + " entries must be full paths to a folder, such as "
                            + KEY_INCLUDE_SUBFOLDERS + "=Z:/PassportReports, was '" + entry + "'.");
        }
        return path.toAbsolutePath().normalize();
    }

    /**
     * Folders given as full paths: kept apart from the source, mirror and state folders, and allowed
     * to be missing at startup - like the source folder, a drive can connect later.
     */
    private static Map<String, Path> externalFolders(
            Map<String, Path> given, Path source, Path mirror, Path state, List<String> warnings) {
        final Map<String, Path> folders = new LinkedHashMap<>();
        for (final Map.Entry<String, Path> entry : given.entrySet()) {
            Path folder = entry.getValue();
            if (Files.exists(folder) && !Files.isDirectory(folder)) {
                throw new ConfigException(
                        KEY_INCLUDE_SUBFOLDERS + " entry is not a directory: " + folder);
            }
            if (Files.isDirectory(folder)) {
                folder = toRealPath(folder, KEY_INCLUDE_SUBFOLDERS);
            } else {
                warnings.add(
                        KEY_INCLUDE_SUBFOLDERS + " folder " + folder + " is not available yet."
                                + " FastMirror keeps checking and copies from it when it appears.");
            }
            validateNoOverlap(
                    source, KEY_SOURCE_FOLDER, folder, KEY_INCLUDE_SUBFOLDERS,
                    "Files inside the source folder are selected by " + KEY_INCLUDE_PREFIXES + ".");
            validateNoOverlap(
                    mirror, KEY_MIRROR_FOLDER, folder, KEY_INCLUDE_SUBFOLDERS,
                    "This would cause an infinite copy loop.");
            validateNoOverlap(
                    state, KEY_STATE_FOLDER, folder, KEY_INCLUDE_SUBFOLDERS,
                    "The copy ledger must not be copied.");
            folders.put(entry.getKey(), folder);
        }
        return folders;
    }

    /**
     * Day packaging, or {@code null} when {@code PACKAGE_ENABLED} is not {@code true}. When it is
     * off nothing else about it is read or checked.
     */
    private static PackageConfig packaging(
            Function<String, String> lookup,
            Path source,
            Path mirror,
            Path state,
            Map<String, Path> externalSubfolders) {
        final String enabled = value(lookup, KEY_PACKAGE_ENABLED, "false");
        if (enabled.equalsIgnoreCase("false")) {
            return null;
        }
        if (!enabled.equalsIgnoreCase("true")) {
            throw new ConfigException(
                    KEY_PACKAGE_ENABLED + " must be true or false, was '" + enabled + "'.");
        }

        final String store = required(lookup, KEY_STORE_FOLDER, "wolf_800_NY-13208");
        if (!STORE.matcher(store).matches() || store.equals(".") || store.equals("..")) {
            throw new ConfigException(
                    KEY_STORE_FOLDER + " may use only letters, digits, _ - and . (it names a folder,"
                            + " every zip and the S3 path), was '" + store + "'. Example: "
                            + KEY_STORE_FOLDER + "=wolf_800_NY-13208");
        }

        final Path outboxInput =
                toPath(required(lookup, KEY_OUTBOX_FOLDER, "C:/HOR/outbox"), KEY_OUTBOX_FOLDER);
        createDirectoryIfMissing(outboxInput, KEY_OUTBOX_FOLDER);
        final Path outbox = toRealPath(outboxInput, KEY_OUTBOX_FOLDER);

        final String rawStaging = lookup.apply(KEY_PACKAGE_STAGING_FOLDER);
        final Path stagingInput =
                rawStaging == null || rawStaging.isBlank()
                        ? outbox.resolveSibling(outbox.getFileName() + STAGING_FOLDER_SUFFIX)
                        : toPath(rawStaging.trim(), KEY_PACKAGE_STAGING_FOLDER);
        // The finished zip is moved into the outbox in one step, which only works on one drive.
        if (!Objects.equals(outbox.getRoot(), stagingInput.getRoot())) {
            throw new ConfigException(
                    KEY_PACKAGE_STAGING_FOLDER + " (" + stagingInput + ") must be on the same drive as "
                            + KEY_OUTBOX_FOLDER + " (" + outbox + "), so that a finished zip appears in"
                            + " the outbox in one step. Leave it empty to use " + outbox.getFileName()
                            + STAGING_FOLDER_SUFFIX + " beside the outbox.");
        }
        createDirectoryIfMissing(stagingInput, KEY_PACKAGE_STAGING_FOLDER);
        final Path staging = toRealPath(stagingInput, KEY_PACKAGE_STAGING_FOLDER);

        // Packaging reads the mirror only, and its output must never be copied, mirrored, or
        // mistaken for the ledger; and the S3 agent must never see a zip being built.
        final Map<String, Path> outputs = new LinkedHashMap<>();
        outputs.put(KEY_OUTBOX_FOLDER, outbox);
        outputs.put(KEY_PACKAGE_STAGING_FOLDER, staging);
        final String apart = "Packaging output must stay apart from the folders FastMirror reads.";
        for (final Map.Entry<String, Path> output : outputs.entrySet()) {
            final String key = output.getKey();
            final Path folder = output.getValue();
            validateNoOverlap(
                    source, KEY_SOURCE_FOLDER, folder, key,
                    "FastMirror never writes inside the source folder.");
            validateNoOverlap(mirror, KEY_MIRROR_FOLDER, folder, key, apart);
            validateNoOverlap(state, KEY_STATE_FOLDER, folder, key, "The ledgers must not be uploaded.");
            for (final Path external : externalSubfolders.values()) {
                validateNoOverlap(external, KEY_INCLUDE_SUBFOLDERS, folder, key, apart);
            }
        }
        validateNoOverlap(
                outbox, KEY_OUTBOX_FOLDER, staging, KEY_PACKAGE_STAGING_FOLDER,
                "The S3 agent would upload half-built zips.");
        validateWritable(outbox, KEY_OUTBOX_FOLDER);
        validateWritable(staging, KEY_PACKAGE_STAGING_FOLDER);

        final LocalTime packageAt = timeOfDay(lookup, KEY_PACKAGE_AT_UTC, DEFAULT_PACKAGE_AT_UTC);
        final LocalDate packageFrom = date(lookup, KEY_PACKAGE_FROM_DATE);
        final int keepDays =
                intInRange(lookup, KEY_OUTBOX_KEEP_DAYS, DEFAULT_OUTBOX_KEEP_DAYS, 0, 36_500);

        return new PackageConfig(store, outbox, staging, packageAt, packageFrom, keepDays);
    }

    private static LocalTime timeOfDay(Function<String, String> lookup, String key) {
        return timeOfDay(lookup, key, LocalTime.MIDNIGHT);
    }

    private static LocalTime timeOfDay(Function<String, String> lookup, String key, LocalTime fallback) {
        final String raw = lookup.apply(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(raw.trim());
        } catch (final DateTimeParseException e) {
            throw new ConfigException(
                    key + " must be a time of day in the form HH:mm, such as 03:00, was '"
                            + raw.trim() + "'.", e);
        }
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

    private static DateSource dateSource(Function<String, String> lookup, String key) {
        final String raw = lookup.apply(key);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DATE_SOURCE;
        }
        try {
            return DateSource.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new ConfigException(
                    key + " must be CREATED, FILENAME or CONTENT, was '" + raw.trim() + "'.", e);
        }
    }

    /** The pattern, required when the date source is FILENAME and ignored (with a warning) otherwise. */
    private static FileNameDatePattern datePattern(
            Function<String, String> lookup,
            String key,
            DateSource source,
            String sourceKey,
            List<String> warnings) {
        final String raw = lookup.apply(key);
        final boolean given = raw != null && !raw.isBlank();
        if (source != DateSource.FILENAME) {
            if (given) {
                warnings.add(key + " is ignored because " + sourceKey + " is not FILENAME.");
            }
            return null;
        }
        if (!given) {
            throw new ConfigException(
                    sourceKey + "=FILENAME needs " + key + " to say where the date is in the"
                            + " name, for example " + key + "=???yyyy-MM-dd* for CPJ2026-09-13-590.xml");
        }
        try {
            return FileNameDatePattern.parse(raw);
        } catch (final IllegalArgumentException e) {
            throw new ConfigException(
                    key + " '" + raw.trim() + "' is not usable: " + e.getMessage() + ".", e);
        }
    }

    /** The field list, required when the date source is CONTENT and ignored (with a warning) otherwise. */
    private static ContentDateFields contentFields(
            Function<String, String> lookup,
            String key,
            DateSource source,
            String sourceKey,
            List<String> warnings) {
        final String raw = lookup.apply(key);
        final boolean given = raw != null && !raw.isBlank();
        if (source != DateSource.CONTENT) {
            if (given) {
                warnings.add(key + " is ignored because " + sourceKey + " is not CONTENT.");
            }
            return null;
        }
        if (!given) {
            throw new ConfigException(
                    sourceKey + "=CONTENT needs " + key + " to name the field holding the business"
                            + " date, for example " + key + "=BusinessDate (Gilbarco Passport) or "
                            + key + "=periodEndDate,closedTime (Verifone)");
        }
        try {
            return ContentDateFields.parse(raw);
        } catch (final IllegalArgumentException e) {
            throw new ConfigException(
                    key + " '" + raw.trim() + "' is not usable: " + e.getMessage() + ".", e);
        }
    }

    private static ZoneId timeZone(String raw) {
        try {
            return ZoneId.of(raw);
        } catch (final DateTimeException e) {
            throw new ConfigException(
                    KEY_TIME_ZONE + " must be a time zone name such as America/New_York, was '"
                            + raw + "'.", e);
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
