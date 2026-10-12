package com.handsoffretail.fastmirror.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that only moves when a test moves it, so "3 minutes later" takes no real time. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.now = new AtomicReference<>(Objects.requireNonNull(start, "start"));
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** Starts at the real current time, in the PC's time zone. */
    public static MutableClock systemNow() {
        return new MutableClock(Instant.now(), ZoneId.systemDefault());
    }

    public void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now.get(), newZone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
