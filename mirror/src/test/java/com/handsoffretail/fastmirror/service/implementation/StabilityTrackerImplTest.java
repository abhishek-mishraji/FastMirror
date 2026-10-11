package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.support.MutableClock;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StabilityTrackerImplTest {

    private static final Path FILE = Path.of("CPJ1.xml");
    private static final FileTime T1 = FileTime.from(Instant.parse("2026-10-15T10:00:00Z"));
    private static final FileTime T2 = FileTime.from(Instant.parse("2026-10-15T10:00:05Z"));

    private final MutableClock clock =
            new MutableClock(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
    private final StabilityTrackerImpl tracker = new StabilityTrackerImpl(Duration.ofMinutes(3), clock);

    @Test
    @DisplayName("a file seen for the first time is never stable, however old its timestamp")
    void firstSightingIsNotStable() {
        assertFalse(tracker.observe(FILE, 10L, T1));
        assertEquals(1, tracker.waiting());
    }

    @Test
    @DisplayName("unchanged for exactly the stability period is stable; a second less is not")
    void boundary() {
        tracker.observe(FILE, 10L, T1);

        clock.advance(Duration.ofMinutes(3).minusSeconds(1));
        assertFalse(tracker.observe(FILE, 10L, T1));

        clock.advance(Duration.ofSeconds(1));
        assertTrue(tracker.observe(FILE, 10L, T1));
        assertEquals(0, tracker.waiting());
    }

    @Test
    @DisplayName("a size change restarts the wait")
    void sizeChangeRestarts() {
        tracker.observe(FILE, 10L, T1);
        clock.advance(Duration.ofMinutes(2));
        assertFalse(tracker.observe(FILE, 11L, T1));

        clock.advance(Duration.ofMinutes(2));
        assertFalse(tracker.observe(FILE, 11L, T1), "only 2 minutes since the change");

        clock.advance(Duration.ofMinutes(1));
        assertTrue(tracker.observe(FILE, 11L, T1));
    }

    @Test
    @DisplayName("a timestamp change with the same size restarts the wait")
    void timestampChangeRestarts() {
        tracker.observe(FILE, 10L, T1);
        clock.advance(Duration.ofMinutes(3));
        assertFalse(tracker.observe(FILE, 10L, T2));

        clock.advance(Duration.ofMinutes(3));
        assertTrue(tracker.observe(FILE, 10L, T2));
    }

    @Test
    @DisplayName("forget and retainOnly drop files, so a returning file starts its wait again")
    void forgetting() {
        final Path other = Path.of("CPJ2.xml");
        tracker.observe(FILE, 10L, T1);
        tracker.observe(other, 10L, T1);
        clock.advance(Duration.ofMinutes(5));

        tracker.retainOnly(Set.of(other));
        assertFalse(tracker.observe(FILE, 10L, T1), "FILE was forgotten");
        assertTrue(tracker.observe(other, 10L, T1), "other was kept");

        tracker.forget(other);
        assertFalse(tracker.observe(other, 10L, T1));
    }
}
