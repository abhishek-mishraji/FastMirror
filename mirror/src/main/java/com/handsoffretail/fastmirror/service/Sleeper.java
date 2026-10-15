package com.handsoffretail.fastmirror.service;

/**
 * Injectable delay, so retry backoff can be driven deterministically in tests rather than by
 * wall-clock sleeps.
 *
 * <p>The only production use is the backoff between attempts to open a
 * locked source file.
 */
public interface Sleeper {

    /**
     * Pause the calling thread.
     *
     * @param millis how long to pause; zero or negative returns immediately
     * @throws InterruptedException if the thread is interrupted while paused, which callers must
     *                              propagate or handle by restoring the interrupt flag — shutdown
     *                              depends on it
     */
    void sleep(long millis) throws InterruptedException;
}
