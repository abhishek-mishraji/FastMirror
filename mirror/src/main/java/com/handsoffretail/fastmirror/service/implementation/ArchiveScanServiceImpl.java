package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.CopyJob;
import com.handsoffretail.fastmirror.entity.LedgerKey;
import com.handsoffretail.fastmirror.entity.Selection;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.ArchiveFileFilter;
import com.handsoffretail.fastmirror.service.CopyLedger;
import com.handsoffretail.fastmirror.service.CopyQueue;
import com.handsoffretail.fastmirror.service.FileDateResolver;
import com.handsoffretail.fastmirror.service.PathMapper;
import com.handsoffretail.fastmirror.service.ScanService;
import com.handsoffretail.fastmirror.service.StabilityTracker;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lists the source root and each included subfolder, one level deep, and submits files that are
 * selected, not yet in the ledger, and stable.
 *
 * <p>Attribute reads only. Opening anything is the copier's job.
 *
 * <p>Tolerates per-entry failures: the producer is writing into this folder continuously, so an
 * entry disappearing mid-listing is normal, not exceptional.
 */
public final class ArchiveScanServiceImpl implements ScanService {

    private final MirrorConfig config;
    private final PathMapper mapper;
    private final ArchiveFileFilter filter;
    private final FileDateResolver dates;
    private final StabilityTracker tracker;
    private final CopyLedger ledger;
    private final CopyQueue queue;
    private final AppLogger logger;

    private final ScheduledExecutorService scheduler;

    /** Included subfolders already reported missing, so the warning is logged once, not every scan. */
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    /** Files already reported as too large, for the same reason. */
    private final Set<Path> reportedTooLarge = ConcurrentHashMap.newKeySet();

    /** Folders that could not be listed on the last attempt, so a failure is logged once. */
    private final Set<Path> unlistable = ConcurrentHashMap.newKeySet();

    /** Whether the source folder was there at the last scan; {@code null} before the first. */
    private Boolean sourceAvailable;

    private boolean firstScanDone;

    public ArchiveScanServiceImpl(
            MirrorConfig config,
            PathMapper mapper,
            ArchiveFileFilter filter,
            FileDateResolver dates,
            StabilityTracker tracker,
            CopyLedger ledger,
            CopyQueue queue,
            AppLogger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.dates = Objects.requireNonNull(dates, "dates");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            final Thread thread = new Thread(runnable, "fastmirror-scan");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    /** Synchronized: the scheduled scan and an on-demand scan must never interleave. */
    @Override
    public synchronized void scan() {
        if (!sourceIsAvailable()) {
            return;
        }
        final Tally tally = new Tally();

        if (!scanFolder(config.sourceFolder(), tally)) {
            // Listing the root failed, so "present" is incomplete. Forgetting every waiting file
            // would restart all their stability clocks, so this scan changes nothing.
            return;
        }
        for (final String subfolder : config.includeSubfolders()) {
            final Path directory = config.externalSubfolders().get(subfolder);
            if (!Files.isDirectory(directory)) {
                if (reportedMissing.add(subfolder)) {
                    logger.warn(
                            "Included subfolder not found, will keep checking: " + directory);
                }
                continue;
            }
            if (reportedMissing.remove(subfolder)) {
                logger.info("Included subfolder has appeared: " + directory);
            }
            scanFolder(directory, tally);
        }

        tracker.retainOnly(tally.present);
        dates.retainOnly(tally.keys);

        if (!firstScanDone) {
            firstScanDone = true;
            logger.info(
                    "Initial scan: " + tally.present.size() + " matching file(s) not yet copied;"
                            + " each is copied once unchanged for " + config.stabilityMinutes()
                            + " minute(s)");
        } else if (tally.submitted > 0) {
            logger.info(
                    "Scan: " + tally.submitted + " file(s) stable and queued for copy, "
                            + tracker.waiting() + " still waiting, " + ledger.size()
                            + " copied so far");
        }
    }

    @Override
    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::scanQuietly,
                config.scanIntervalSeconds(),
                config.scanIntervalSeconds(),
                TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        scheduler.shutdownNow();
    }

    /** A scheduled task that throws is silently cancelled forever, so nothing may escape here. */
    private void scanQuietly() {
        try {
            scan();
        } catch (final Throwable t) {
            logger.error("Periodic scan failed", t);
        }
    }

    private static final class Tally {
        private final Set<Path> present = new HashSet<>();
        private final Set<LedgerKey> keys = new HashSet<>();
        private int submitted;
    }

    /**
     * The source folder can disappear and come back - a network drive or VM share disconnecting -
     * and the agent must ride that out rather than stop. Each change is logged once.
     */
    private boolean sourceIsAvailable() {
        final boolean available = Files.isDirectory(config.sourceFolder());
        if (!available && !Boolean.FALSE.equals(sourceAvailable)) {
            logger.warn(
                    "Source folder not available, will keep checking every "
                            + config.scanIntervalSeconds() + "s: " + config.sourceFolder());
        } else if (available && Boolean.FALSE.equals(sourceAvailable)) {
            logger.info("Source folder is available again: " + config.sourceFolder());
        }
        sourceAvailable = available;
        return available;
    }

    /** @return whether the folder could be listed */
    private boolean scanFolder(Path directory, Tally tally) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (final Path entry : entries) {
                try {
                    consider(entry, tally);
                } catch (final RuntimeException e) {
                    logger.warn("Scan skipped " + entry + ": " + e);
                }
            }
        } catch (final IOException | DirectoryIteratorException e) {
            if (unlistable.add(directory)) {
                logger.warn("Could not list " + directory + ", will keep trying: " + e);
            }
            return false;
        }
        if (unlistable.remove(directory)) {
            logger.info("Listing works again: " + directory);
        }
        return true;
    }

    private void consider(Path file, Tally tally) {
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (final IOException e) {
            // Deleted between the listing and now. Entirely expected.
            return;
        }
        if (!attributes.isRegularFile()) {
            return;
        }

        final Path relative = mapper.relativize(file);
        final Optional<Selection> selection = filter.select(relative);
        if (selection.isEmpty()) {
            return;
        }

        final LedgerKey key =
                LedgerKey.of(relative, attributes.creationTime().toMillis(), attributes.size());
        if (ledger.contains(key)) {
            tracker.forget(relative);
            return;
        }
        tally.keys.add(key);

        // The date from the creation time or the file name is known at once. A date inside the file
        // is known only once the file has been read, which happens when it is stable (below).
        final Optional<LocalDate> knownDate =
                dates.knownDate(relative, key, attributes.creationTime());
        if (knownDate.isPresent() && !filter.withinDateRange(knownDate.get())) {
            return;
        }

        if (!config.withinSizeLimit(attributes.size())) {
            if (reportedTooLarge.add(relative)) {
                logger.warn(
                        "Skipped (" + attributes.size() + " bytes exceeds MAX_COPY_BYTES="
                                + config.maxCopyBytes() + "): " + relative);
            }
            return;
        }

        tally.present.add(relative);
        if (!tracker.observe(relative, attributes.size(), attributes.lastModifiedTime())) {
            return;
        }

        // Stable. Read the date inside the file now if that is the configured source: once, since
        // the answer is remembered, and never while the producer may still be writing it.
        final Optional<LocalDate> fileDate =
                knownDate.isPresent()
                        ? knownDate
                        : dates.dateOf(relative, key, attributes.creationTime());
        if (fileDate.isEmpty() || !filter.withinDateRange(fileDate.get())) {
            // Unreadable right now (tried again next scan), or before COPY_FROM_DATE.
            return;
        }

        queue.submit(
                new CopyJob(
                        relative,
                        mapper.targetFor(selection.get(), relative, fileDate.get()),
                        key));
        tally.submitted++;
    }
}
