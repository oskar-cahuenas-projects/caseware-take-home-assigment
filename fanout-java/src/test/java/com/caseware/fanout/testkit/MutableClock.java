package com.caseware.fanout.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Test clock that moves only when told to. Thread-safe: advances are atomic and never lost. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(new AtomicReference<>(Objects.requireNonNull(start, "start")), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    /** Moves time forward by {@code d}; negative durations are rejected. */
    public void advance(Duration d) {
        if (d.isNegative()) {
            throw new IllegalArgumentException("time cannot move backwards: " + d);
        }
        now.updateAndGet(t -> t.plus(d));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** A view in another zone sharing this clock's time. */
    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }
}
