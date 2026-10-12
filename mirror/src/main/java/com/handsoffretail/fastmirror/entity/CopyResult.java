package com.handsoffretail.fastmirror.entity;

import java.util.Objects;

/**
 * What one copy job did, returned by {@code FileCopier}.
 *
 * @param outcome     the terminal state; never {@code null}
 * @param bytesCopied bytes written to the mirror; {@code 0} unless the outcome wrote data
 * @param cause       the failure that ended the copy, or {@code null} if none
 */
public record CopyResult(CopyOutcome outcome, long bytesCopied, Throwable cause) {

    public CopyResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    /** A result that wrote nothing and has no exception to report. */
    public static CopyResult of(CopyOutcome outcome) {
        return new CopyResult(outcome, 0L, null);
    }

    /** A fully copied file. */
    public static CopyResult completed(CopyOutcome outcome, long bytesCopied) {
        return new CopyResult(outcome, bytesCopied, null);
    }

    /** A failure; nothing was published. */
    public static CopyResult failed(Throwable cause) {
        return new CopyResult(CopyOutcome.FAILED, 0L, cause);
    }
}
