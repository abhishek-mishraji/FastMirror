package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.CopyJob;
import com.handsoffretail.fastmirror.entity.LedgerKey;
import com.handsoffretail.fastmirror.entity.Selection;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.ArchiveFileFilter;
import com.handsoffretail.fastmirror.service.CopyLedger;
import com.handsoffretail.fastmirror.service.CopyQueue;
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
import java.time.Clock;
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
    private final StabilityTracker tracker;
    private final CopyLedger ledger;
    private final CopyQueue queue;
    private final AppLogger logger;
    private final Clock clock;

    private final ScheduledExecutorService scheduler;

    /** Included subfolders already reported missing, so the warning is logged once, not every scan. */
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    /** Files already reported as too large, for the same reason. */
    private final Set<Path> reportedTooLarge = ConcurrentHashMap.newKeySet();

    private boolean firstScanDone;

    public ArchiveScanServiceImpl(
            MirrorConfig config,
            PathMapper mapper,
            ArchiveFileFilter filter,
            StabilityTracker tracker,
            CopyLedger ledger,
            CopyQueue queue,
            AppLogger logger,
            Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        final Tally tally = new Tally();

        scanFolder(config.sourceFolder(), tally);
        for (final String subfolder : config.includeSubfolders()) {
            final Path directory = config.sourceFolder().resolve(subfolder);
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
        private int submitted;
    }

    private void scanFolder(Path directory, Tally tally) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (final Path entry : entries) {
                try {
                    consider(entry, tally);
                } catch (final RuntimeException e) {
                    logger.warn("Scan skipped " + entry + ": " + e);
                }
            }
        } catch (final IOException | DirectoryIteratorException e) {
            logger.warn("Could not list " + directory + ": " + e);
        }
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
        final LocalDate creationDate =
                LocalDate.ofInstant(attributes.creationTime().toInstant(), clock.getZone());
        final Optional<Selection> selection = filter.select(relative, creationDate);
        if (selection.isEmpty()) {
            return;
        }

        final LedgerKey key =
                LedgerKey.of(relative, attributes.creationTime().toMillis(), attributes.size());
        if (ledger.contains(key)) {
            tracker.forget(relative);
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
        if (tracker.observe(relative, attributes.size(), attributes.lastModifiedTime())) {
            queue.submit(
                    new CopyJob(
                            relative,
                            mapper.targetFor(selection.get(), relative, creationDate),
                            key));
            tally.submitted++;
        }
    }
}
