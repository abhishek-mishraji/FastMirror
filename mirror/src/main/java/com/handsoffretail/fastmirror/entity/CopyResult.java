package com.handsoffretail.fastmirror.entity;

import java.util.Objects;

/**
 * What one copy job did, returned by {@code FileCopier} (SRS 6.2).
 *
 * @param outcome          the terminal state; never {@code null}
 * @param bytesCopied      bytes actually written to the mirror; {@code 0} unless the outcome wrote data
 * @param expectedBytes    the source size observed on the open channel, or {@code -1} if never read
 * @param captureLatencyMs interval from event receipt to successful source open, or {@code -1}
 *                         if the source was never opened
 * @param cause            the failure that ended the transfer, or {@code null} if none
 */
public record CopyResult(
        CopyOutcome outcome,
        long bytesCopied,
        long expectedBytes,
        long captureLatencyMs,
        Throwable cause) {

    public CopyResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    /** A result for an outcome reached before the source was ever opened. */
    public static CopyResult of(CopyOutcome outcome) {
        return new CopyResult(outcome, 0L, -1L, -1L, null);
    }

    /** A result for an outcome reached after the source was opened but before bytes were written. */
    public static CopyResult of(CopyOutcome outcome, long captureLatencyMs) {
        return new CopyResult(outcome, 0L, -1L, captureLatencyMs, null);
    }

    /** A fully captured copy. */
    public static CopyResult completed(CopyOutcome outcome, long bytesCopied, long captureLatencyMs) {
        return new CopyResult(outcome, bytesCopied, bytesCopied, captureLatencyMs, null);
    }

    /** A copy whose transfer failed after {@code bytesCopied} bytes were captured and published (FR-27). */
    public static CopyResult partial(
            long bytesCopied, long expectedBytes, long captureLatencyMs, Throwable cause) {
        return new CopyResult(
                CopyOutcome.PARTIAL, bytesCopied, expectedBytes, captureLatencyMs, cause);
    }

    /** A failure that captured nothing (FR-28). */
    public static CopyResult failed(long captureLatencyMs, Throwable cause) {
        return new CopyResult(CopyOutcome.FAILED, 0L, -1L, captureLatencyMs, cause);
    }

    /** Whether the source handle was obtained at all. */
    public boolean wasOpened() {
        return captureLatencyMs >= 0L;
    }
}
