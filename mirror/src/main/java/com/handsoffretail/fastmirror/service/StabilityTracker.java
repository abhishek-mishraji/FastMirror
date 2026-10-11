package com.handsoffretail.fastmirror.service;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;

/**
 * Decides when a file has stopped changing.
 *
 * <p>A file is stable once its size and last-modified time have been seen unchanged for
 * {@code STABILITY_MINUTES}. The file is only ever observed through its attributes; it is never
 * opened or locked to find out whether someone is still writing it, because that could make the
 * producer's own write fail.
 */
public interface StabilityTracker {

    /**
     * Records one observation of a file.
     *
     * @return {@code true} if the file has now been unchanged for the whole stability period
     */
    boolean observe(Path relative, long size, FileTime lastModified);

    /** Forgets a file, typically because it has been copied. */
    void forget(Path relative);

    /** Forgets every file not in {@code present}, so files that vanished do not accumulate. */
    void retainOnly(Set<Path> present);

    /** How many files are being watched and are not yet stable. */
    int waiting();
}
