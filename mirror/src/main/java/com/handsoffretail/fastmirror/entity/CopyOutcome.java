package com.handsoffretail.fastmirror.entity;

/**
 * The terminal state of exactly one copy job (SRS 9.8).
 *
 * <p>Every copy job ends in exactly one of these values and never in an exception (FR-29). Each
 * value increments a counter reported in the periodic summary (FR-21).
 */
public enum CopyOutcome {

    /** The target did not exist; it now does, fully captured. */
    COPIED,

    /** The target existed and differed; it has been updated, fully captured. */
    REPLACED,

    /**
     * The transfer failed part-way and the bytes captured so far were published (FR-27, PD-2).
     *
     * <p>Under C-2 this should be close to zero. A persistently non-zero count means reads really
     * are failing mid-transfer and warrants investigation (SRS 14.3).
     */
    PARTIAL,

    /** The target already matched the source. Nothing was written. */
    UNCHANGED,

    /**
     * The source was already gone when the first open was attempted. Nothing was captured and
     * nothing can be — this is the irreducible race of SRS 12, and this counter is how it is
     * measured in production rather than assumed.
     */
    VANISHED,

    /** The source was still locked by another process after every retry (C-3, FR-10). */
    LOCKED,

    /** The source exceeded {@code MAX_COPY_BYTES} (FR-19). */
    SKIPPED_TOO_LARGE,

    /** An I/O error with nothing captured, or a failure while publishing (FR-28). */
    FAILED;

    /** Whether this outcome left bytes in the mirror that were not there before. */
    public boolean wroteData() {
        return this == COPIED || this == REPLACED || this == PARTIAL;
    }

    /** Whether this outcome represents source data that FastMirror failed to capture in full. */
    public boolean isDataLoss() {
        return this == VANISHED || this == PARTIAL;
    }
}
