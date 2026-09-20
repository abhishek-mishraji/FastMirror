package com.handsoffretail.fastmirror.service;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Deduplicates and schedules copy jobs across the worker pool (SRS 9.6, FR-12).
 *
 * <p>Implements the three-state machine QUEUED / RUNNING / RUNNING_DIRTY, which guarantees that a
 * burst of events for one file collapses into at most one running copy plus one follow-up. The
 * follow-up always observes the newest content, so the mirror converges on the final state of the
 * source.
 *
 * <p>It is also what makes NI-10 true by construction: at most one job per path runs at a time and
 * at most {@code COPY_WORKERS} jobs run at once, so FastMirror can never hold more than that many
 * of the producer's files open.
 */
public interface CopyQueue {

    /**
     * Ask for {@code relative} to be copied.
     *
     * <p>Never blocks: it is called from the event thread, which must never wait on a copy.
     *
     * @param relative            the path relative to both roots
     * @param eventNanos          {@link System#nanoTime()} at event receipt
     * @param followedCreateEvent whether this was triggered by a CREATE (FR-31)
     */
    void submit(Path relative, long eventNanos, boolean followedCreateEvent);

    /** Paths currently queued or running. Zero when the queue is idle. */
    int pending();

    /**
     * Waits until no job is queued or running.
     *
     * @return {@code true} if the queue became idle within the timeout
     */
    boolean awaitIdle(Duration timeout) throws InterruptedException;

    /**
     * Stops accepting work and lets in-flight copies finish (SRS 9.10).
     *
     * <p>The grace period matters for non-interference: a worker interrupted mid-copy would still
     * release its handle, but letting it finish keeps the mirror consistent and the handle lifetime
     * predictable.
     */
    void shutdown(Duration grace);
}
