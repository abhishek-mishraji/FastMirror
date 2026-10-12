package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.entity.CopyJob;
import com.handsoffretail.fastmirror.entity.CopyResult;
import com.handsoffretail.fastmirror.entity.LedgerKey;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.CopyLedger;
import com.handsoffretail.fastmirror.service.CopyQueue;
import com.handsoffretail.fastmirror.service.FileCopier;
import com.handsoffretail.fastmirror.service.StatsReporter;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fixed worker pool with one rule: a file already queued or running is not queued again.
 *
 * <p>The scan re-submits a stable file on every pass until it is in the ledger, so without that rule
 * a slow copy would be queued once a minute. A successful copy is recorded in the ledger
 * <em>before</em> the file leaves the in-flight set, so a scan running at that moment sees it as
 * copied rather than as new.
 */
public final class CopyQueueImpl implements CopyQueue {

    private final FileCopier copier;
    private final CopyLedger ledger;
    private final StatsReporter stats;
    private final AppLogger logger;

    private final Set<LedgerKey> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeJobs = new AtomicInteger();
    private final ExecutorService workers;

    private volatile boolean accepting = true;

    public CopyQueueImpl(
            FileCopier copier,
            CopyLedger ledger,
            StatsReporter stats,
            AppLogger logger,
            int workerCount) {
        this.copier = Objects.requireNonNull(copier, "copier");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.workers = Executors.newFixedThreadPool(workerCount, namedThreads());
    }

    @Override
    public void submit(CopyJob job) {
        Objects.requireNonNull(job, "job");
        if (!accepting || !inFlight.add(job.key())) {
            return;
        }
        activeJobs.incrementAndGet();
        try {
            workers.execute(() -> run(job));
        } catch (final RejectedExecutionException e) {
            // Shutting down. Release the entry so the set does not leak.
            inFlight.remove(job.key());
            activeJobs.decrementAndGet();
        }
    }

    @Override
    public int pending() {
        return inFlight.size();
    }

    @Override
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (inFlight.isEmpty() && activeJobs.get() == 0) {
                return true;
            }
            Thread.sleep(5L);
        }
        return inFlight.isEmpty() && activeJobs.get() == 0;
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

    private void run(CopyJob job) {
        try {
            final CopyResult result = execute(job);
            if (result.outcome().wroteData()) {
                ledger.record(job.key());
            }
            stats.record(result);
        } catch (final Throwable t) {
            logger.error("Recording the result failed for " + job.relativeSource(), t);
        } finally {
            inFlight.remove(job.key());
            activeJobs.decrementAndGet();
        }
    }

    /** Runs one job, absorbing anything it throws. A worker thread must never die. */
    private CopyResult execute(CopyJob job) {
        try {
            return copier.copy(job);
        } catch (final Throwable t) {
            logger.error("Copy job failed unexpectedly for " + job.relativeSource(), t);
            return CopyResult.failed(t);
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
