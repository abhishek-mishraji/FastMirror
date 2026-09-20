package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.entity.CopyResult;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.CopyQueue;
import com.handsoffretail.fastmirror.service.DifferenceDetector;
import com.handsoffretail.fastmirror.service.FileCopier;
import com.handsoffretail.fastmirror.service.StatsReporter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The QUEUED / RUNNING / RUNNING_DIRTY scheduler of SRS 9.6.
 *
 * <p>The state map is mutated only through atomic {@code compute} operations, so two threads
 * submitting the same path can never both dispatch it and an update can never be lost.
 *
 * <p>Three properties follow from the machine, and all three are required behaviour:
 *
 * <ul>
 *   <li>a burst of MODIFY events for one file collapses into at most one running copy plus one
 *       follow-up;
 *   <li>the follow-up always observes the newest content, so the mirror converges;
 *   <li>the map cannot grow without bound, because an entry is removed once no further work is
 *       pending.
 * </ul>
 *
 * <p>It is also the enforcement point for NI-10: one job per path at a time, at most
 * {@code COPY_WORKERS} jobs at once, so the number of producer files FastMirror can hold open is
 * bounded by configuration rather than by load.
 */
public final class CopyQueueImpl implements CopyQueue {

    private enum State {
        /** Waiting for a worker. */
        QUEUED,
        /** A worker is copying it now. */
        RUNNING,
        /** A worker is copying it now, and it changed again since that copy started. */
        RUNNING_DIRTY
    }

    /**
     * @param earliestEventNanos the oldest event still waiting to be satisfied, so capture latency
     *                           is measured from the first event that asked rather than the last
     * @param createSeen         whether any of the merged events was a CREATE (FR-31)
     */
    private record Entry(State state, long earliestEventNanos, boolean createSeen) {}

    private final DifferenceDetector detector;
    private final FileCopier copier;
    private final StatsReporter stats;
    private final AppLogger logger;

    private final ConcurrentHashMap<Path, Entry> states = new ConcurrentHashMap<>();
    private final AtomicInteger activeJobs = new AtomicInteger();
    private final ExecutorService workers;

    private volatile boolean accepting = true;

    public CopyQueueImpl(
            DifferenceDetector detector,
            FileCopier copier,
            StatsReporter stats,
            AppLogger logger,
            int workerCount) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.copier = Objects.requireNonNull(copier, "copier");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.workers = Executors.newFixedThreadPool(workerCount, namedThreads());
    }

    @Override
    public void submit(Path relative, long eventNanos, boolean followedCreateEvent) {
        Objects.requireNonNull(relative, "relative");
        if (!accepting) {
            return;
        }

        final boolean[] dispatch = {false};
        states.compute(
                relative,
                (path, existing) -> {
                    if (existing == null) {
                        dispatch[0] = true;
                        return new Entry(State.QUEUED, eventNanos, followedCreateEvent);
                    }
                    return switch (existing.state()) {
                        // Already waiting: fold this event into the pending one. Keep the earliest
                        // stamp, because that is when the mirror first fell behind.
                        case QUEUED ->
                                new Entry(
                                        State.QUEUED,
                                        Math.min(existing.earliestEventNanos(), eventNanos),
                                        existing.createSeen() || followedCreateEvent);
                        // A copy is in flight and the file changed again. Exactly one further copy
                        // will run after it, measured from this event.
                        case RUNNING, RUNNING_DIRTY ->
                                new Entry(State.RUNNING_DIRTY, eventNanos, followedCreateEvent);
                    };
                });

        if (dispatch[0]) {
            dispatch(relative);
        }
    }

    @Override
    public int pending() {
        return states.size();
    }

    @Override
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (states.isEmpty() && activeJobs.get() == 0) {
                return true;
            }
            Thread.sleep(5L);
        }
        return states.isEmpty() && activeJobs.get() == 0;
    }

    @Override
    public void shutdown(Duration grace) {
        accepting = false;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                logger.warn(
                        "Copy workers did not finish within " + grace.toMillis()
                                + "ms; interrupting. Any open source handle is released by the"
                                + " interrupt, so the producer is unaffected.");
                workers.shutdownNow();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    private void dispatch(Path relative) {
        activeJobs.incrementAndGet();
        try {
            workers.execute(() -> run(relative));
        } catch (final RejectedExecutionException e) {
            // Shutting down. Drop the entry so the map does not leak.
            activeJobs.decrementAndGet();
            states.remove(relative);
        }
    }

    private void run(Path relative) {
        try {
            final Entry started =
                    states.compute(
                            relative,
                            (path, existing) -> {
                                if (existing == null) {
                                    return null;
                                }
                                return existing.state() == State.RUNNING_DIRTY
                                        ? existing
                                        : new Entry(
                                                State.RUNNING,
                                                existing.earliestEventNanos(),
                                                existing.createSeen());
                            });
            if (started == null) {
                return;
            }

            stats.record(execute(relative, started));

            final boolean[] again = {false};
            states.compute(
                    relative,
                    (path, existing) -> {
                        if (existing == null) {
                            return null;
                        }
                        if (existing.state() == State.RUNNING_DIRTY) {
                            again[0] = true;
                            return new Entry(
                                    State.QUEUED,
                                    existing.earliestEventNanos(),
                                    existing.createSeen());
                        }
                        // Nothing further pending: remove, so the map stays bounded.
                        return null;
                    });

            if (again[0]) {
                dispatch(relative);
            }
        } finally {
            activeJobs.decrementAndGet();
        }
    }

    /** Runs one job, absorbing anything it throws. A worker thread must never die (FR-29, NFR-07). */
    private CopyResult execute(Path relative, Entry entry) {
        try {
            if (!detector.needsCopy(relative)) {
                return CopyResult.of(CopyOutcome.UNCHANGED);
            }
            return copier.copy(relative, entry.earliestEventNanos(), entry.createSeen());
        } catch (final Throwable t) {
            logger.error("Copy job failed unexpectedly for " + relative, t);
            return CopyResult.failed(-1L, t);
        }
    }

    private static ThreadFactory namedThreads() {
        final AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            final Thread thread =
                    new Thread(runnable, "fastmirror-copy-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
