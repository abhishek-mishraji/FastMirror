package com.handsoffretail.fastmirror.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable snapshot of the copy counters at one instant.
 *
 * <p>Holds data only. Rendering the summary line is the reporter's job.
 */
public final class CopyStats {

    private final Map<CopyOutcome, Long> counts;
    private final long packaged;

    public CopyStats(Map<CopyOutcome, Long> counts) {
        this(counts, 0L);
    }

    /** @param packaged zips moved into the outbox */
    public CopyStats(Map<CopyOutcome, Long> counts, long packaged) {
        this.packaged = packaged;
        Objects.requireNonNull(counts, "counts");
        final Map<CopyOutcome, Long> copy = new EnumMap<>(CopyOutcome.class);
        copy.putAll(counts);
        this.counts = Collections.unmodifiableMap(copy);
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

    /** Whether anything at all happened in this window, used to suppress empty log lines. */
    public boolean isEmpty() {
        return counts().isEmpty() && packaged == 0L;
    }

    /** Zips moved into the outbox. */
    public long packaged() {
        return packaged;
    }
}
