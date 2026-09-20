package com.handsoffretail.fastmirror.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable snapshot of the copy counters at one instant (SRS 9.8, FR-21).
 *
 * <p>Holds data only. Rendering the summary line is the reporter's job (SRS 6.2).
 */
public final class CopyStats {

    private final Map<CopyOutcome, Long> counts;
    private final long observations;
    private final double averageLatencyMs;
    private final long maxLatencyMs;

    public CopyStats(
            Map<CopyOutcome, Long> counts,
            long observations,
            double averageLatencyMs,
            long maxLatencyMs) {
        Objects.requireNonNull(counts, "counts");
        final Map<CopyOutcome, Long> copy = new EnumMap<>(CopyOutcome.class);
        copy.putAll(counts);
        this.counts = Collections.unmodifiableMap(copy);
        this.observations = observations;
        this.averageLatencyMs = averageLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
    }

    /** The count for one outcome, zero if it never occurred. */
    public long count(CopyOutcome outcome) {
        return counts.getOrDefault(Objects.requireNonNull(outcome, "outcome"), 0L);
    }

    /** All non-zero counters, in enum order. */
    public Map<CopyOutcome, Long> counts() {
        final Map<CopyOutcome, Long> nonZero = new EnumMap<>(CopyOutcome.class);
        counts.forEach(
                (outcome, count) -> {
                    if (count != null && count > 0L) {
                        nonZero.put(outcome, count);
                    }
                });
        return Collections.unmodifiableMap(nonZero);
    }

    /** How many capture-latency measurements contributed to the averages. */
    public long observations() {
        return observations;
    }

    public double averageLatencyMs() {
        return averageLatencyMs;
    }

    public long maxLatencyMs() {
        return maxLatencyMs;
    }

    /** Whether anything at all happened in this window, used to suppress empty log lines. */
    public boolean isEmpty() {
        return counts().isEmpty();
    }
}
