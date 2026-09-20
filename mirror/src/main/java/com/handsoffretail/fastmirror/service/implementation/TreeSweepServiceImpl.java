package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.CopyQueue;
import com.handsoffretail.fastmirror.service.DifferenceDetector;
import com.handsoffretail.fastmirror.service.PathFilter;
import com.handsoffretail.fastmirror.service.PathMapper;
import com.handsoffretail.fastmirror.service.SweepService;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Depth-first sweep of the source tree (SRS 9.7).
 *
 * <p>Tolerates per-entry failures: an unreadable file or directory is logged and skipped, never a
 * reason to abandon the walk. The producer is writing into this tree continuously, so entries
 * disappearing mid-walk is normal, not exceptional.
 *
 * <p>Attribute reads only (NI-12). The sweep decides what to submit; opening anything is the
 * copier's job.
 */
public final class TreeSweepServiceImpl implements SweepService {

    private final MirrorConfig config;
    private final PathMapper mapper;
    private final PathFilter filter;
    private final DifferenceDetector detector;
    private final CopyQueue queue;
    private final AppLogger logger;

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean sweepPending = new AtomicBoolean();

    public TreeSweepServiceImpl(
            MirrorConfig config,
            PathMapper mapper,
            PathFilter filter,
            DifferenceDetector detector,
            CopyQueue queue,
            AppLogger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            final Thread thread = new Thread(runnable, "fastmirror-sweep");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    @Override
    public void sweep() {
        final long start = System.nanoTime();
        final int[] submitted = {0};

        try {
            Files.walkFileTree(config.sourceFolder(), new SweepVisitor(submitted));
        } catch (final IOException e) {
            logger.warn("Sweep could not complete: " + e);
        } catch (final RuntimeException e) {
            logger.error("Sweep failed unexpectedly", e);
        }

        if (submitted[0] > 0) {
            final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            logger.info("Sweep complete: " + submitted[0] + " file(s) submitted in " + elapsedMs + "ms");
        }
    }

    @Override
    public void requestSweep() {
        // Coalesce: one pending sweep is enough, however many OVERFLOWs arrive.
        if (!sweepPending.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.execute(
                    () -> {
                        sweepPending.set(false);
                        sweep();
                    });
        } catch (final RuntimeException e) {
            sweepPending.set(false);
        }
    }

    @Override
    public void start() {
        if (!config.sweepEnabled()) {
            logger.info("Periodic sweep is disabled (SWEEP_INTERVAL_MS=0)");
            return;
        }
        scheduler.scheduleWithFixedDelay(
                this::sweepQuietly,
                config.sweepIntervalMs(),
                config.sweepIntervalMs(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        scheduler.shutdownNow();
    }

    /** A scheduled task that throws is silently cancelled forever, so nothing may escape here. */
    private void sweepQuietly() {
        try {
            sweep();
        } catch (final Throwable t) {
            logger.error("Periodic sweep failed", t);
        }
    }

    private final class SweepVisitor implements FileVisitor<Path> {

        private final int[] submitted;

        private SweepVisitor(int[] submitted) {
            this.submitted = submitted;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (!directory.equals(config.sourceFolder()) && filter.isExcluded(directory)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (config.mirrorEmptyDirectories() && !directory.equals(config.sourceFolder())) {
                createMirrorDirectory(directory);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (!attributes.isRegularFile() || filter.isExcluded(file)) {
                return FileVisitResult.CONTINUE;
            }
            try {
                final Path relative = mapper.relativize(file);
                if (detector.needsCopy(relative)) {
                    // A sweep is not an event, so it is never treated as a CREATE (FR-31).
                    queue.submit(relative, System.nanoTime(), false);
                    submitted[0]++;
                }
            } catch (final RuntimeException e) {
                logger.warn("Sweep skipped " + file + ": " + e);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) {
            // Entirely expected: the producer deletes files while we walk. Not worth a log line
            // per occurrence, and definitely not worth abandoning the sweep.
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
            if (failure != null) {
                logger.warn("Sweep could not finish reading " + directory + ": " + failure);
            }
            return FileVisitResult.CONTINUE;
        }

        private void createMirrorDirectory(Path directory) {
            try {
                Files.createDirectories(mapper.toTarget(mapper.relativize(directory)));
            } catch (final IOException | RuntimeException e) {
                logger.warn("Could not mirror the directory " + directory + ": " + e);
            }
        }
    }
}
