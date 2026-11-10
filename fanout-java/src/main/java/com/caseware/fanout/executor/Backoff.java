package com.caseware.fanout.executor;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;

/**
 * Delay strategy for retries and deferrals, using the injected seeded {@link Random} (G-12, G-16, G-24).
 * Thread-safe ({@link Random} is).
 */
public final class Backoff {

    /** {@code 2^30 × base} already exceeds any sane cap; avoids overflow. */
    private static final int MAX_SHIFT = 30;

    private final Duration base;
    private final Duration cap;
    private final Duration deferralBase;
    private final Random random;

    public Backoff(ExecutorConfig config, Random random) {
        this.base = config.backoffBase();
        this.cap = config.maxRequeueDelay();
        this.deferralBase = config.firmCapDeferral();
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Full jitter over {@code min(cap, base·2^attempt)}, at least {@code retryAfter}, at most {@code cap}. */
    public Duration retry(int attempt, Duration retryAfter) {
        Duration ceiling = base.multipliedBy(1L << Math.min(Math.max(attempt, 0), MAX_SHIFT));
        if (ceiling.compareTo(cap) > 0) {
            ceiling = cap;
        }
        Duration delay = fraction(ceiling);
        if (delay.compareTo(retryAfter) < 0) {
            delay = retryAfter;
        }
        return delay.compareTo(cap) > 0 ? cap : delay;
    }

    /** No-permit / firm-cap deferral: {@code firmCapDeferral × (1 + U[0,1))} (G-24). */
    public Duration deferral() {
        return deferralBase.plus(jitter());
    }

    /** {@code U[0, firmCapDeferral)}, added to the {@code InFlightElsewhere} delay (G-05). */
    public Duration jitter() {
        return fraction(deferralBase);
    }

    private Duration fraction(Duration d) {
        return Duration.ofNanos((long) (d.toNanos() * random.nextDouble()));
    }
}
