package com.caseware.fanout.port;

import java.time.Duration;

/**
 * Timed actions that cannot be evaluated lazily against the clock: the visibility heartbeat and the
 * loader hard timeout (G-10). Implementations must be thread-safe.
 */
public interface Scheduler {

    /** Runs {@code task} once after {@code delay}. */
    Cancellable schedule(Runnable task, Duration delay);

    /** Runs {@code task} after {@code initialDelay}, then every {@code period}, until cancelled. */
    Cancellable scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period);

    /** Handle to a scheduled task. */
    interface Cancellable {
        /** Prevents future runs. Idempotent; a run already in progress is not interrupted. */
        void cancel();
    }
}
