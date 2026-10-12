package com.handsoffretail.fastmirror.service;

/**
 * Periodically lists the source folder and hands stable, selected, not-yet-copied files to the copy
 * queue.
 *
 * <p>Reads directory entries and attributes only. It never opens a file's contents; that stays on
 * the single audited path in the copier.
 */
public interface ScanService {

    /** Runs one scan on the calling thread. */
    void scan();

    /** Starts scanning every {@code SCAN_INTERVAL_SECONDS}. */
    void start();

    /** Stops the periodic scan. */
    void stop();
}
