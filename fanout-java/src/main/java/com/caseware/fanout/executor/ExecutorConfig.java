package com.caseware.fanout.executor;

import com.caseware.fanout.domain.Lane;
import java.time.Duration;
import java.util.Objects;

/**
 * Every duration and number the executor uses. {@link #production()} holds the contract (§C) and
 * approved-gap defaults; {@link #forTests()} scales every duration down 1000x (1 s → 1 ms) for
 * concurrency tests on the system clock. Immutable; thread-safe.
 *
 * @param capacity       region-wide loader cap {@code C} (A3)
 * @param tasks          executor task count {@code N} (A4, G-15)
 * @param lease          ledger lease; must be ≥ {@code loaderHardTimeout + leaseMargin} (G-14)
 * @param firmCapDeferral base delay for a no-permit or firm-cap deferral, before jitter (G-24)
 */
public record ExecutorConfig(
        int capacity,
        int tasks,
        Duration visibilityTimeout,
        Duration heartbeatInterval,
        Duration heartbeatExtension,
        Duration loaderHardTimeout,
        Duration leaseMargin,
        Duration lease,
        int maxReceiveCount,
        Duration freshnessWindow,
        LaneWeights laneWeights,
        Duration maxRequeueDelay,
        int breakerFailureThreshold,
        Duration breakerCooldown,
        Duration backoffBase,
        int maxTransientAttempts,
        Duration firmCapDeferral,
        int maxReceiveBatch,
        Duration receiveWait) {

    /** Share of a task's permits one firm may hold: {@code max(1, 0.2 × C/N)} (§C). */
    private static final double FIRM_CAP_SHARE = 0.2;
    private static final long TEST_SCALE = 1_000;

    public ExecutorConfig {
        positive(capacity, "capacity");
        positive(tasks, "tasks");
        if (capacity < tasks) {
            throw new IllegalArgumentException("capacity C (" + capacity + ") must be >= tasks N (" + tasks + ")");
        }
        positive(visibilityTimeout, "visibilityTimeout");
        positive(heartbeatInterval, "heartbeatInterval");
        positive(heartbeatExtension, "heartbeatExtension");
        positive(loaderHardTimeout, "loaderHardTimeout");
        positive(leaseMargin, "leaseMargin");
        positive(lease, "lease");
        positive(maxReceiveCount, "maxReceiveCount");
        positive(freshnessWindow, "freshnessWindow");
        Objects.requireNonNull(laneWeights, "laneWeights");
        positive(maxRequeueDelay, "maxRequeueDelay");
        positive(breakerFailureThreshold, "breakerFailureThreshold");
        positive(breakerCooldown, "breakerCooldown");
        positive(backoffBase, "backoffBase");
        positive(maxTransientAttempts, "maxTransientAttempts");
        positive(firmCapDeferral, "firmCapDeferral");
        positive(maxReceiveBatch, "maxReceiveBatch");
        positive(receiveWait, "receiveWait");
        if (heartbeatInterval.compareTo(visibilityTimeout) >= 0) {
            throw new IllegalArgumentException("heartbeatInterval must be shorter than visibilityTimeout");
        }
        if (lease.compareTo(loaderHardTimeout.plus(leaseMargin)) < 0) {
            throw new IllegalArgumentException("lease must be >= loaderHardTimeout + leaseMargin (G-14)");
        }
    }

    /** Contract §C and approved-gap defaults. C = 50, N = 2 are the 3.3 low scenario (A3, A4). */
    public static ExecutorConfig production() {
        return new ExecutorConfig(
                50, 2,
                Duration.ofMinutes(3),      // visibility (§C)
                Duration.ofSeconds(60),     // heartbeat every (§C)
                Duration.ofMinutes(3),      // heartbeat extends by (§C)
                Duration.ofMinutes(5),      // loader hard timeout (§C)
                Duration.ofMinutes(1),      // lease margin (G-14)
                Duration.ofMinutes(6),      // lease (G-14)
                5,                          // maxReceiveCount (§C)
                Duration.ofHours(24),       // freshness window W (§F)
                new LaneWeights(6, 3, 1),   // §C
                Duration.ofMinutes(15),     // SQS DelaySeconds max (G-12)
                5,                          // breaker K (G-03)
                Duration.ofSeconds(30),     // breaker cool-down (G-03)
                Duration.ofSeconds(30),     // backoff base (G-16)
                8,                          // max Transient attempts (G-16)
                Duration.ofSeconds(30),     // firm-cap deferral (G-24)
                10,                         // SQS receive batch max (G-24)
                Duration.ofSeconds(20));    // SQS long-poll max [A 90%]
    }

    /** {@link #production()} with every duration divided by 1000; counts and weights unchanged. */
    public static ExecutorConfig forTests() {
        ExecutorConfig p = production();
        return new ExecutorConfig(p.capacity, p.tasks,
                scale(p.visibilityTimeout), scale(p.heartbeatInterval), scale(p.heartbeatExtension),
                scale(p.loaderHardTimeout), scale(p.leaseMargin), scale(p.lease),
                p.maxReceiveCount, scale(p.freshnessWindow), p.laneWeights, scale(p.maxRequeueDelay),
                p.breakerFailureThreshold, scale(p.breakerCooldown), scale(p.backoffBase),
                p.maxTransientAttempts, scale(p.firmCapDeferral), p.maxReceiveBatch, scale(p.receiveWait));
    }

    /** {@code ⌊C/N⌋}; the remainder {@code C mod N} is unused (G-15). */
    public int permitsPerTask() {
        return capacity / tasks;
    }

    /** Per-task, per-firm in-flight cap: {@code max(1, ⌊0.2 × ⌊C/N⌋⌋)} (§C, G-15). */
    public int firmCap() {
        return Math.max(1, (int) Math.floor(FIRM_CAP_SHARE * permitsPerTask()));
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Lane polling weights, publish : backfill : reconcile (§C, G-13). */
    public record LaneWeights(int publish, int backfill, int reconcile) {
        public LaneWeights {
            positive(publish, "publish weight");
            positive(backfill, "backfill weight");
            positive(reconcile, "reconcile weight");
        }

        public int weight(Lane lane) {
            return switch (lane) {
                case PUBLISH -> publish;
                case BACKFILL -> backfill;
                case RECONCILE -> reconcile;
            };
        }
    }

    /** Copy-and-override builder for test profiles (e.g. G-18's E7 profile). Not thread-safe. */
    public static final class Builder {
        private int capacity;
        private int tasks;
        private Duration visibilityTimeout;
        private Duration heartbeatInterval;
        private Duration heartbeatExtension;
        private Duration loaderHardTimeout;
        private Duration leaseMargin;
        private Duration lease;
        private int maxReceiveCount;
        private Duration freshnessWindow;
        private LaneWeights laneWeights;
        private Duration maxRequeueDelay;
        private int breakerFailureThreshold;
        private Duration breakerCooldown;
        private Duration backoffBase;
        private int maxTransientAttempts;
        private Duration firmCapDeferral;
        private int maxReceiveBatch;
        private Duration receiveWait;

        private Builder(ExecutorConfig c) {
            capacity = c.capacity;
            tasks = c.tasks;
            visibilityTimeout = c.visibilityTimeout;
            heartbeatInterval = c.heartbeatInterval;
            heartbeatExtension = c.heartbeatExtension;
            loaderHardTimeout = c.loaderHardTimeout;
            leaseMargin = c.leaseMargin;
            lease = c.lease;
            maxReceiveCount = c.maxReceiveCount;
            freshnessWindow = c.freshnessWindow;
            laneWeights = c.laneWeights;
            maxRequeueDelay = c.maxRequeueDelay;
            breakerFailureThreshold = c.breakerFailureThreshold;
            breakerCooldown = c.breakerCooldown;
            backoffBase = c.backoffBase;
            maxTransientAttempts = c.maxTransientAttempts;
            firmCapDeferral = c.firmCapDeferral;
            maxReceiveBatch = c.maxReceiveBatch;
            receiveWait = c.receiveWait;
        }

        public Builder capacity(int v) { capacity = v; return this; }
        public Builder tasks(int v) { tasks = v; return this; }
        public Builder visibilityTimeout(Duration v) { visibilityTimeout = v; return this; }
        public Builder heartbeatInterval(Duration v) { heartbeatInterval = v; return this; }
        public Builder heartbeatExtension(Duration v) { heartbeatExtension = v; return this; }
        public Builder loaderHardTimeout(Duration v) { loaderHardTimeout = v; return this; }
        public Builder leaseMargin(Duration v) { leaseMargin = v; return this; }
        public Builder lease(Duration v) { lease = v; return this; }
        public Builder maxReceiveCount(int v) { maxReceiveCount = v; return this; }
        public Builder freshnessWindow(Duration v) { freshnessWindow = v; return this; }
        public Builder laneWeights(LaneWeights v) { laneWeights = v; return this; }
        public Builder maxRequeueDelay(Duration v) { maxRequeueDelay = v; return this; }
        public Builder breakerFailureThreshold(int v) { breakerFailureThreshold = v; return this; }
        public Builder breakerCooldown(Duration v) { breakerCooldown = v; return this; }
        public Builder backoffBase(Duration v) { backoffBase = v; return this; }
        public Builder maxTransientAttempts(int v) { maxTransientAttempts = v; return this; }
        public Builder firmCapDeferral(Duration v) { firmCapDeferral = v; return this; }
        public Builder maxReceiveBatch(int v) { maxReceiveBatch = v; return this; }
        public Builder receiveWait(Duration v) { receiveWait = v; return this; }

        public ExecutorConfig build() {
            return new ExecutorConfig(capacity, tasks, visibilityTimeout, heartbeatInterval, heartbeatExtension,
                    loaderHardTimeout, leaseMargin, lease, maxReceiveCount, freshnessWindow, laneWeights,
                    maxRequeueDelay, breakerFailureThreshold, breakerCooldown, backoffBase, maxTransientAttempts,
                    firmCapDeferral, maxReceiveBatch, receiveWait);
        }
    }

    private static Duration scale(Duration d) {
        return d.dividedBy(TEST_SCALE);
    }

    private static void positive(int v, String name) {
        if (v < 1) {
            throw new IllegalArgumentException(name + " must be >= 1, was " + v);
        }
    }

    private static void positive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " must be > 0, was " + d);
        }
    }
}
