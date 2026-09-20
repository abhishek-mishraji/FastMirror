package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.CopyResult;
import com.handsoffretail.fastmirror.entity.CopyStats;

/**
 * Owns the copy counters and emits the periodic summary (FR-21, SRS 9.8).
 *
 * <p>The counters are the product's instrumentation, not decoration. {@code VANISHED} is the
 * measurement of the irreducible race in SRS 12; {@code PARTIAL} says whether reads are failing
 * mid-transfer. Both exist so the operator can answer questions from a log rather than a guess.
 */
public interface StatsReporter {

    /** Record one finished copy job. Called from a copy worker, so it must be cheap and non-blocking. */
    void record(CopyResult result);

    /** Emit one summary line for the window just ended and start a new window. */
    void report();

    /** The current window's counters, without resetting them. */
    CopyStats snapshot();

    /** Every outcome counted since startup, for the shutdown summary. */
    CopyStats lifetime();
}
