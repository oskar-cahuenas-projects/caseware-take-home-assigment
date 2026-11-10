package com.caseware.fanout.testkit;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Polls a condition until it holds or a real-time timeout passes. For concurrency tests only. Stateless. */
public final class Await {

    private static final long POLL_NANOS = Duration.ofMillis(1).toNanos();

    private Await() { }

    /** @throws AssertionError naming {@code description} and {@code timeout} if the condition never held */
    public static void until(BooleanSupplier condition, Duration timeout, String description) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError("Condition '" + description + "' not met within " + timeout);
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
    }
}
