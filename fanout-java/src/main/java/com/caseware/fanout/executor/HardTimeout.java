package com.caseware.fanout.executor;

import com.caseware.fanout.port.Scheduler;
import java.time.Duration;

/**
 * Loader hard timeout (G-28): when it fires on the {@link Scheduler} while the call is still running, it
 * interrupts the loading thread. Create and close it on the loading thread. {@link #close()} disarms it
 * and clears an interrupt it delivered, so the interrupt cannot leak into the steps after the load.
 * Thread-safe.
 */
final class HardTimeout implements AutoCloseable {

    private final Thread loading = Thread.currentThread();
    private boolean armed = true;   // guarded by this
    private boolean fired;          // guarded by this
    private final Scheduler.Cancellable task;

    HardTimeout(Scheduler scheduler, Duration timeout) {
        task = scheduler.schedule(this::fire, timeout);
    }

    private synchronized void fire() {
        if (armed) {
            fired = true;
            loading.interrupt();
        }
    }

    /** {@code true} if the timeout fired while the call was running. */
    synchronized boolean fired() {
        return fired;
    }

    @Override
    public void close() {
        task.cancel();
        synchronized (this) {
            armed = false;
            if (fired) {
                Thread.interrupted();
            }
        }
    }
}
