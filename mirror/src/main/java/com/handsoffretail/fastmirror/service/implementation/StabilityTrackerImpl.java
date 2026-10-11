package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.service.StabilityTracker;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per file, the size and timestamp last seen and when they were first seen unchanged.
 *
 * <p>A file seen for the first time is never stable, even if its timestamp is old. On Windows the
 * last-modified time reported for a file can lag behind while a writer still holds it open, so a
 * file is only trusted once FastMirror itself has watched it not change for the whole period.
 */
public final class StabilityTrackerImpl implements StabilityTracker {

    private record Observation(long size, FileTime lastModified, Instant unchangedSince) {}

    private final Duration stabilityPeriod;
    private final Clock clock;
    private final Map<Path, Observation> observations = new ConcurrentHashMap<>();

    public StabilityTrackerImpl(Duration stabilityPeriod, Clock clock) {
        this.stabilityPeriod = Objects.requireNonNull(stabilityPeriod, "stabilityPeriod");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (stabilityPeriod.isNegative() || stabilityPeriod.isZero()) {
            throw new IllegalArgumentException("stabilityPeriod must be positive: " + stabilityPeriod);
        }
    }

    @Override
    public boolean observe(Path relative, long size, FileTime lastModified) {
        Objects.requireNonNull(relative, "relative");
        Objects.requireNonNull(lastModified, "lastModified");
        final Instant now = clock.instant();

        final Observation current =
                observations.compute(
                        relative,
                        (path, previous) -> {
                            if (previous != null
                                    && previous.size() == size
                                    && previous.lastModified().equals(lastModified)) {
                                return previous;
                            }
                            // First sighting, or it changed: the wait starts again from now.
                            return new Observation(size, lastModified, now);
                        });

        return !now.isBefore(current.unchangedSince().plus(stabilityPeriod));
    }

    @Override
    public void forget(Path relative) {
        observations.remove(Objects.requireNonNull(relative, "relative"));
    }

    @Override
    public void retainOnly(Set<Path> present) {
        Objects.requireNonNull(present, "present");
        observations.keySet().retainAll(present);
    }

    @Override
    public int waiting() {
        final Instant now = clock.instant();
        int count = 0;
        for (final Observation observation : observations.values()) {
            if (now.isBefore(observation.unchangedSince().plus(stabilityPeriod))) {
                count++;
            }
        }
        return count;
    }
}
