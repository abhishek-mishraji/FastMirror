package com.handsoffretail.fastmirror.service;

/**
 * Injectable delay, so retry backoff can be driven deterministically in tests rather than by
 * wall-clock sleeps (AR-9).
 *
 * <p>The only production use is the open-retry backoff of SRS 9.5.
 */
public interface Sleeper {

    /**
     * Pause the calling thread.
     *
     * @param millis how long to pause; zero or negative returns immediately
     * @throws InterruptedException if the thread is interrupted while paused, which callers must
     *                              propagate or handle by restoring the interrupt flag — shutdown
     *                              depends on it (SRS 9.10)
     */
    void sleep(long millis) throws InterruptedException;
}
