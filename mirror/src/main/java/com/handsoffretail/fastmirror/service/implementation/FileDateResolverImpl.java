package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.config.ContentDateFields;
import com.handsoffretail.fastmirror.config.FileNameDatePattern;
import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.DateSource;
import com.handsoffretail.fastmirror.entity.LedgerKey;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.FileCopier;
import com.handsoffretail.fastmirror.service.FileDateResolver;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks a file's date from its creation time, its name or its content, as configured.
 *
 * <p>The name or the content is the better source where the store writes the business date there:
 * a file for 30 September that is only written on 1 October still belongs in September. Which one
 * applies depends on the POS, and is set per store in {@code .env}, so the same code serves every
 * store.
 *
 * <p>Root files whose prefix is in {@code CREATED_DATE_PREFIXES} are dated by their creation time
 * whatever the root rule says. Verifone's PJR slices need this: each repeats the journal since an
 * earlier transaction, so the first date inside can be hours before the slice was written, and a
 * slice written after a day was zipped would otherwise land in that day.
 */
public final class FileDateResolverImpl implements FileDateResolver {

    /** How one group of files (root or subfolder) is dated. */
    private record Rule(
            DateSource source,
            FileNameDatePattern pattern,
            ContentDateFields fields,
            String patternKey,
            String fieldsKey) {}

    private final ZoneId timeZone;
    private final LocalTime businessDayStartsAt;
    /** The rule for {@code CREATED_DATE_PREFIXES} files. */
    private static final Rule BY_CREATION = new Rule(DateSource.CREATED, null, null, null, null);

    private static final int PREFIX_LENGTH = 3;

    private final Rule root;
    private final Rule subfolder;
    private final Set<String> createdDatePrefixes;
    private final int contentScanBytes;
    private final FileCopier copier;
    private final AppLogger logger;

    /** Dates already read from file content, so each file is read once. */
    private final Map<LedgerKey, LocalDate> contentDates = new ConcurrentHashMap<>();

    /** Files already reported as having no date, so each is warned about once. */
    private final Set<Path> reportedUndated = ConcurrentHashMap.newKeySet();

    public FileDateResolverImpl(MirrorConfig config, FileCopier copier, AppLogger logger) {
        Objects.requireNonNull(config, "config");
        this.timeZone = config.timeZone();
        this.businessDayStartsAt = config.businessDayStartsAt();
        this.root =
                new Rule(
                        config.dateSource(),
                        config.fileNameDatePattern(),
                        config.contentDateFields(),
                        MirrorConfig.KEY_FILENAME_DATE_PATTERN,
                        MirrorConfig.KEY_CONTENT_DATE_FIELDS);
        this.subfolder =
                new Rule(
                        config.subfolderDateSource(),
                        config.subfolderFileNameDatePattern(),
                        config.subfolderContentDateFields(),
                        MirrorConfig.KEY_SUBFOLDER_FILENAME_DATE_PATTERN,
                        MirrorConfig.KEY_SUBFOLDER_CONTENT_DATE_FIELDS);
        this.createdDatePrefixes = Set.copyOf(config.createdDatePrefixes());
        this.contentScanBytes = config.contentScanBytes();
        this.copier = Objects.requireNonNull(copier, "copier");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public Optional<LocalDate> knownDate(Path relative, LedgerKey key, FileTime creationTime) {
        Objects.requireNonNull(relative, "relative");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(creationTime, "creationTime");

        final Rule rule = ruleFor(relative);
        return switch (rule.source()) {
            case CREATED -> Optional.of(created(creationTime));
            case FILENAME -> Optional.of(fromName(relative, rule, creationTime));
            case CONTENT -> Optional.ofNullable(contentDates.get(key));
        };
    }

    @Override
    public Optional<LocalDate> dateOf(Path relative, LedgerKey key, FileTime creationTime) {
        final Optional<LocalDate> known = knownDate(relative, key, creationTime);
        if (known.isPresent()) {
            return known;
        }

        final Optional<byte[]> head = copier.readHead(relative, contentScanBytes);
        if (head.isEmpty()) {
            // Locked or gone. Remember nothing, so a later scan reads it properly instead of
            // filing it under a fallback date for good.
            return Optional.empty();
        }

        final Rule rule = ruleFor(relative);
        final byte[] bytes = head.get();
        final LocalDate date =
                rule.fields()
                        .extract(bytes, bytes.length)
                        .orElseGet(
                                () ->
                                        fallBack(
                                                relative,
                                                creationTime,
                                                "No " + rule.fieldsKey() + "=" + rule.fields()
                                                        + " field in the first " + contentScanBytes
                                                        + " bytes of " + relative));
        contentDates.put(key, date);
        return Optional.of(date);
    }

    @Override
    public void retainOnly(Set<LedgerKey> keys) {
        Objects.requireNonNull(keys, "keys");
        contentDates.keySet().retainAll(keys);
    }

    private Rule ruleFor(Path relative) {
        if (relative.getNameCount() > 1) {
            return subfolder;
        }
        final String name = relative.getFileName().toString();
        if (name.length() >= PREFIX_LENGTH
                && createdDatePrefixes.contains(name.substring(0, PREFIX_LENGTH).toUpperCase(Locale.ROOT))) {
            return BY_CREATION;
        }
        return root;
    }

    private LocalDate created(FileTime creationTime) {
        final LocalDateTime local = LocalDateTime.ofInstant(creationTime.toInstant(), timeZone);
        return businessDay(local.toLocalDate(), local.toLocalTime());
    }

    /**
     * A timestamp before {@code BUSINESS_DAY_STARTS_AT} belongs to the day before: a store-close
     * report printed at 00:04 on the 24th is the 23rd's. Dates read from inside a file are business
     * dates already and never pass through here.
     */
    private LocalDate businessDay(LocalDate date, LocalTime time) {
        return time != null && time.isBefore(businessDayStartsAt) ? date.minusDays(1) : date;
    }

    private LocalDate fromName(Path relative, Rule rule, FileTime creationTime) {
        return rule.pattern()
                .extractStamp(relative.getFileName().toString())
                .map(stamp -> businessDay(stamp.date(), stamp.time()))
                .orElseGet(
                        () ->
                                fallBack(
                                        relative,
                                        creationTime,
                                        "No date matching " + rule.patternKey() + "="
                                                + rule.pattern().template() + " in the name of "
                                                + relative));
    }

    private LocalDate fallBack(Path relative, FileTime creationTime, String reason) {
        final LocalDate created = created(creationTime);
        if (reportedUndated.add(relative)) {
            logger.warn(reason + "; using its creation date " + created + " instead");
        }
        return created;
    }
}
