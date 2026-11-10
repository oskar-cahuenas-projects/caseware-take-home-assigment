package com.caseware.fanout.executor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Consecutive-failure breaker, one per executor task (G-03, G-27). CLOSED → OPEN after {@code threshold}
 * consecutive {@code Throttled}/{@code Transient}; OPEN → HALF_OPEN once the cool-down has elapsed on the
 * clock; HALF_OPEN admits exactly one probe message; probe success → CLOSED, failure → OPEN with a new
 * cool-down. The executor asks {@link #admit} before every receive, so an open breaker stops receiving.
 * Thread-safe: one {@link ReentrantLock}.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** How many messages may be received; {@code probe} marks the single HALF_OPEN probe. */
    public record Admission(int count, boolean probe) {
        static final Admission NONE = new Admission(0, false);
    }

    private final int threshold;
    private final Duration cooldown;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean probeInFlight;

    public CircuitBreaker(int threshold, Duration cooldown, Clock clock) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1");
        }
        this.threshold = threshold;
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Admission admit(int wanted) {
        lock.lock();
        try {
            if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(cooldown))) {
                state = State.HALF_OPEN;
                probeInFlight = false;
            }
            return switch (state) {
                case CLOSED -> new Admission(wanted, false);
                case OPEN -> Admission.NONE;
                case HALF_OPEN -> {
                    if (probeInFlight || wanted < 1) {
                        yield Admission.NONE;
                    }
                    probeInFlight = true;
                    yield new Admission(1, true);
                }
            };
        } finally {
            lock.unlock();
        }
    }

    /** A load returned a result. */
    public void onSuccess() {
        lock.lock();
        try {
            state = State.CLOSED;
            consecutiveFailures = 0;
            probeInFlight = false;
        } finally {
            lock.unlock();
        }
    }

    /** A load failed with {@code Throttled} or {@code Transient}. */
    public void onFailure() {
        lock.lock();
        try {
            switch (state) {
                case CLOSED -> {
                    if (++consecutiveFailures >= threshold) {
                        open();
                    }
                }
                case HALF_OPEN -> open();
                case OPEN -> { }                         // late result of a load started before opening
            }
        } finally {
            lock.unlock();
        }
    }

    /** The probe message finished without a loader verdict (coalesced, deferred, NotFound…); allow another probe. */
    public void probeAbandoned() {
        lock.lock();
        try {
            if (state == State.HALF_OPEN) {
                probeInFlight = false;
            }
        } finally {
            lock.unlock();
        }
    }

    public State state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.instant();
        consecutiveFailures = 0;
        probeInFlight = false;
    }
}
