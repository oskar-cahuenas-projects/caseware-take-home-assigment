package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.port.Scheduler;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Real-time {@link Scheduler} for production and concurrency tests: one timer thread hands each run
 * to a new virtual thread, so a slow or throwing task never delays or cancels others.
 * Uses wall-clock delays, so pair it with the system clock, not a {@code MutableClock}. Thread-safe.
 */
public final class VirtualThreadScheduler implements Scheduler, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(VirtualThreadScheduler.class.getName());

    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("scheduler-timer").factory());

    @Override
    public Cancellable schedule(Runnable task, Duration delay) {
        ScheduledFuture<?> f = timer.schedule(() -> runOnVirtualThread(task), delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> f.cancel(false);
    }

    @Override
    public Cancellable scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
        ScheduledFuture<?> f = timer.scheduleAtFixedRate(() -> runOnVirtualThread(task),
                initialDelay.toNanos(), period.toNanos(), TimeUnit.NANOSECONDS);
        return () -> f.cancel(false);
    }

    private static void runOnVirtualThread(Runnable task) {
        Thread.ofVirtual().name("scheduled-task").start(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "scheduled task failed: " + e.getClass().getName());
            }
        });
    }

    /** Stops the timer; pending tasks do not run. */
    @Override
    public void close() {
        timer.shutdownNow();
    }
}
