package com.handsoffretail.fastmirror.entity;

/**
 * The kinds of filesystem change FastMirror reacts to.
 *
 * <p>Translated from {@code java.nio.file.StandardWatchEventKinds} by the watcher so that no other
 * component depends on the watch API (SRS 6.2).
 */
public enum FileEventKind {

    /** A new entry appeared. The primary trigger for a copy (FR-02). */
    CREATED,

    /** An existing entry changed. Triggers a re-evaluation and possible re-copy (FR-03). */
    MODIFIED,

    /** An entry was removed. FastMirror takes no action, ever (FR-04). */
    DELETED,

    /** Windows dropped notifications. Triggers an immediate full sweep (FR-15, C-6). */
    OVERFLOW
}
