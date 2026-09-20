package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.FileEvent;

/**
 * Owns the event-handling policy of SRS 9.3: what to do with each kind of change.
 *
 * <p>Runs a single thread that drains the event queue. That thread never blocks on a copy and never
 * performs the difference check, because both would add latency to the one path that has to stay
 * fast — the path from "a file appeared" to "we have a handle on it".
 */
public interface MirrorService {

    /**
     * Hands one event over from the watcher thread.
     *
     * <p>Must never block: the caller is the thread keeping the Windows notification buffer drained.
     */
    void accept(FileEvent event);

    /** Starts the event thread. */
    void start();

    /** Stops accepting events and stops the event thread (SRS 9.10). */
    void stop();
}
