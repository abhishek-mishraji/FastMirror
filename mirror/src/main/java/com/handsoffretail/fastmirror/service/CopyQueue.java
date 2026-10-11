package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.CopyJob;
import java.time.Duration;

/**
 * Runs copy jobs on the worker pool and records each completed copy in the ledger.
 *
 * <p>At most one job per file is queued or running at a time, and at most {@code COPY_WORKERS} jobs
 * run at once, so FastMirror never holds more than that many of the producer's files open.
 */
public interface CopyQueue {

    /**
     * Ask for a file to be copied. Ignored if the same file is already queued or running.
     *
     * <p>Never blocks.
     */
    void submit(CopyJob job);

    /** Jobs currently queued or running. Zero when the queue is idle. */
    int pending();

    /**
     * Waits until no job is queued or running.
     *
     * @return {@code true} if the queue became idle within the timeout
     */
    boolean awaitIdle(Duration timeout) throws InterruptedException;

    /** Stops accepting work and lets in-flight copies finish. */
    void shutdown(Duration grace);
}
