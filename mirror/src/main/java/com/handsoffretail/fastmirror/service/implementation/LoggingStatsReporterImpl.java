package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.entity.CopyResult;
import com.handsoffretail.fastmirror.entity.CopyStats;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.StatsReporter;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts outcomes with {@link LongAdder} and logs the periodic summary (SRS 9.8, FR-21).
 *
 * <p>{@code record} is called from copy workers, so it must never block or contend: adders and a
 * compare-and-set maximum are used rather than a lock. A worker holds a producer file handle open
 * while it runs, and anything that could stall it there is a non-interference problem (NI-9).
 *
 * <p>Two windows are kept: one that resets on every report, for the periodic line, and one that
 * never resets, for the shutdown summary and for the soak-run numbers that SRS 17 asks to be
 * recorded.
 */
public final class LoggingStatsReporterImpl implements StatsReporter {

    private final AppLogger logger;
    private final long intervalMs;

    private final Map<CopyOutcome, LongAdder> windowCounts = new EnumMap<>(CopyOutcome.class);
    private final Map<CopyOutcome, LongAdder> lifetimeCounts = new EnumMap<>(CopyOutcome.class);

    private final LongAdder windowLatencySum = new LongAdder();
    private final LongAdder windowLatencyCount = new LongAdder();
    private final AtomicLong windowLatencyMax = new AtomicLong();

    private final LongAdder lifetimeLatencySum = new LongAdder();
    private final LongAdder lifetimeLatencyCount = new LongAdder();
    private final AtomicLong lifetimeLatencyMax = new AtomicLong();

    public LoggingStatsReporterImpl(AppLogger logger, long intervalMs) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.intervalMs = intervalMs;
        for (final CopyOutcome outcome : CopyOutcome.values()) {
            windowCounts.put(outcome, new LongAdder());
            lifetimeCounts.put(outcome, new LongAdder());
        }
    }

    @Override
    public void record(CopyResult result) {
        Objects.requireNonNull(result, "result");
        windowCounts.get(result.outcome()).increment();
        lifetimeCounts.get(result.outcome()).increment();

        final long latency = result.captureLatencyMs();
        if (latency >= 0L) {
            windowLatencySum.add(latency);
            windowLatencyCount.increment();
            windowLatencyMax.accumulateAndGet(latency, Math::max);
            lifetimeLatencySum.add(latency);
            lifetimeLatencyCount.increment();
            lifetimeLatencyMax.accumulateAndGet(latency, Math::max);
        }
    }

    @Override
    public void report() {
        final CopyStats stats = snapshot();
        resetWindow();
        if (stats.isEmpty()) {
            // Nothing happened. A line saying so every minute would bury the lines that matter.
            return;
        }
        logger.info(format(stats, "last " + (intervalMs / 1000L) + "s"));
    }

    @Override
    public CopyStats snapshot() {
        return snapshotOf(
                windowCounts, windowLatencySum, windowLatencyCount, windowLatencyMax);
    }

    @Override
    public CopyStats lifetime() {
        return snapshotOf(
                lifetimeCounts, lifetimeLatencySum, lifetimeLatencyCount, lifetimeLatencyMax);
    }

    /** Renders the summary line of SRS 9.8. Public so the shutdown summary can reuse it. */
    public static String format(CopyStats stats, String window) {
        final StringBuilder line = new StringBuilder(160);
        line.append("Stats (").append(window).append("):");
        for (final Map.Entry<CopyOutcome, Long> entry : stats.counts().entrySet()) {
            line.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
        }
        if (stats.observations() > 0L) {
            line.append(String.format(
                    Locale.ROOT,
                    "  capture latency avg=%.1fms max=%dms",
                    stats.averageLatencyMs(),
                    stats.maxLatencyMs()));
        }
        return line.toString();
    }

    private void resetWindow() {
        windowCounts.values().forEach(LongAdder::reset);
        windowLatencySum.reset();
        windowLatencyCount.reset();
        windowLatencyMax.set(0L);
    }

    private static CopyStats snapshotOf(
            Map<CopyOutcome, LongAdder> counts,
            LongAdder latencySum,
            LongAdder latencyCount,
            AtomicLong latencyMax) {
        final Map<CopyOutcome, Long> values = new EnumMap<>(CopyOutcome.class);
        counts.forEach((outcome, adder) -> values.put(outcome, adder.sum()));
        final long observations = latencyCount.sum();
        final double average = observations == 0L ? 0.0 : (double) latencySum.sum() / observations;
        return new CopyStats(values, observations, average, latencyMax.get());
    }
}
