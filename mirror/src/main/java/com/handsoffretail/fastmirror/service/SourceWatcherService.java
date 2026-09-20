package com.handsoffretail.fastmirror.service;

/**
 * Watches the source tree and turns Windows notifications into {@code FileEvent}s (FR-01).
 *
 * <p>Runs on its own thread whose only job is to drain the notification buffer as fast as possible.
 * It performs no file I/O beyond the watch itself: anything slower here means Windows drops
 * notifications and files are missed (C-6).
 *
 * <p>It knows nothing about copying. Events are handed to {@link MirrorService}, which owns the
 * policy.
 */
public interface SourceWatcherService {

    /**
     * Registers the watch and starts the watcher thread.
     *
     * @throws IllegalStateException if the watch cannot be registered; startup must fail loudly
     *                               rather than run blind (FR-23)
     */
    void start();

    /** Stops the watcher thread and closes the watch service (SRS 9.10). */
    void stop();
}
