package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.entity.CopyResult;
import com.handsoffretail.fastmirror.entity.CopyStats;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.StatsReporter;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts outcomes with {@link LongAdder} and logs the periodic summary.
 *
 * <p>{@code record} is called from copy workers, so it must never block or contend. Two windows are
 * kept: one that resets on every report, for the periodic line, and one that never resets, for the
 * shutdown summary and the tray icon.
 */
public final class LoggingStatsReporterImpl implements StatsReporter {

    private final AppLogger logger;
    private final long intervalMs;

    private final Map<CopyOutcome, LongAdder> windowCounts = new EnumMap<>(CopyOutcome.class);
    private final Map<CopyOutcome, LongAdder> lifetimeCounts = new EnumMap<>(CopyOutcome.class);
    private final LongAdder windowPackaged = new LongAdder();
    private final LongAdder lifetimePackaged = new LongAdder();

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
    }

    @Override
    public void recordPackaged() {
        windowPackaged.increment();
        lifetimePackaged.increment();
    }

    @Override
    public void report() {
        final CopyStats stats = snapshot();
        windowCounts.values().forEach(LongAdder::reset);
        windowPackaged.reset();
        if (stats.isEmpty()) {
            // Nothing happened. A line saying so every minute would bury the lines that matter.
            return;
        }
        logger.info(format(stats, "last " + (intervalMs / 1000L) + "s"));
    }

    @Override
    public CopyStats snapshot() {
        return snapshotOf(windowCounts, windowPackaged);
    }

    @Override
    public CopyStats lifetime() {
        return snapshotOf(lifetimeCounts, lifetimePackaged);
    }

    /** Renders the summary line. Public so the shutdown summary can reuse it. */
    public static String format(CopyStats stats, String window) {
        final StringBuilder line = new StringBuilder(120);
        line.append("Stats (").append(window).append("):");
        if (stats.isEmpty()) {
            line.append(" nothing copied");
        }
        for (final Map.Entry<CopyOutcome, Long> entry : stats.counts().entrySet()) {
            line.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
        }
        if (stats.packaged() > 0L) {
            line.append(" PACKAGED=").append(stats.packaged());
        }
        return line.toString();
    }

    private static CopyStats snapshotOf(Map<CopyOutcome, LongAdder> counts, LongAdder packaged) {
        final Map<CopyOutcome, Long> values = new EnumMap<>(CopyOutcome.class);
        counts.forEach((outcome, adder) -> values.put(outcome, adder.sum()));
        return new CopyStats(values, packaged.sum());
    }
}
