package com.handsoffretail.fastmirror.service;

/**
 * Walks the source tree and submits anything that differs (SRS 9.7).
 *
 * <p>A safety net, not the primary mechanism. With source files living about a second, a 500 ms
 * sweep catches only a fraction of them on its own; event-driven copying is what makes the agent
 * work. The sweep exists to cover dropped notifications (FR-15), the startup window (FR-13) and
 * anything a transient lock caused FastMirror to miss.
 *
 * <p>Reads directory entries and attributes only. It never opens a file's contents — all content
 * access stays on the single audited path of SRS 9.5 (NI-12).
 */
public interface SweepService {

    /** Runs one full sweep on the calling thread. */
    void sweep();

    /**
     * Asks for a sweep to run soon on the sweep thread.
     *
     * <p>Coalescing: if a sweep is already pending, this does nothing. An OVERFLOW storm must not
     * turn into a queue of redundant tree walks over a folder the producer is actively using.
     */
    void requestSweep();

    /** Starts the periodic sweep, if {@code SWEEP_INTERVAL_MS} is non-zero (FR-14). */
    void start();

    /** Stops the periodic sweep and the sweep thread. */
    void stop();
}
