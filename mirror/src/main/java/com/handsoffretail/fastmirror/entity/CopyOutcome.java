package com.handsoffretail.fastmirror.entity;

/**
 * The terminal state of exactly one copy job.
 *
 * <p>Every copy job ends in exactly one of these values and never in an exception. Each value
 * increments a counter reported in the periodic summary.
 */
public enum CopyOutcome {

    /** The target did not exist; it now does, fully copied. */
    COPIED,

    /** A target with the same name already existed and has been overwritten, fully copied. */
    REPLACED,

    /**
     * The source was gone by the time the copy started. Nothing was copied and nothing is recorded
     * in the ledger.
     */
    VANISHED,

    /** The source was still locked by another process after every retry; a later scan tries again. */
    LOCKED,

    /** The source exceeded {@code MAX_COPY_BYTES}. */
    SKIPPED_TOO_LARGE,

    /**
     * An I/O error, including a read that failed part-way. Nothing is published and nothing is
     * recorded in the ledger, so a later scan tries again.
     */
    FAILED;

    /** Whether the target now holds a complete copy of the source. */
    public boolean wroteData() {
        return this == COPIED || this == REPLACED;
    }
}
