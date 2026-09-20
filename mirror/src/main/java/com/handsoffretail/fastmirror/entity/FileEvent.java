package com.handsoffretail.fastmirror.entity;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One filesystem change, stamped with the moment FastMirror learned about it.
 *
 * <p>{@code eventNanos} is a {@link System#nanoTime()} reading taken on the watcher thread the
 * instant the event was dequeued from the watch service. It is the start of the capture-latency
 * measurement defined in SRS 9.5: latency is the interval between this stamp and the successful
 * open of the source file. Measurement only — it never drives control flow (AR-9).
 *
 * @param kind       what happened
 * @param path       the absolute path the event concerns; {@code null} only for {@link FileEventKind#OVERFLOW}
 * @param eventNanos {@code System.nanoTime()} at event receipt
 */
public record FileEvent(FileEventKind kind, Path path, long eventNanos) {

    public FileEvent {
        Objects.requireNonNull(kind, "kind");
        if (kind != FileEventKind.OVERFLOW) {
            Objects.requireNonNull(path, "path is required for " + kind);
        }
    }

    /** An overflow event, which carries no path (C-6). */
    public static FileEvent overflow(long eventNanos) {
        return new FileEvent(FileEventKind.OVERFLOW, null, eventNanos);
    }
}
