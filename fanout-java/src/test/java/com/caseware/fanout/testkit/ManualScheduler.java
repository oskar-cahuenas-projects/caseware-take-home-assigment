package com.caseware.fanout.testkit;

import com.caseware.fanout.port.Scheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic {@link Scheduler}: tasks run only inside {@link #runDue()}, on the calling thread, when
 * their due time is ≤ the clock's now, in due-time then submission order (G-10). Thread-safe.
 */
public final class ManualScheduler implements Scheduler {

    private final Clock clock;
    private final PriorityQueue<Entry> queue =
            new PriorityQueue<>(Comparator.comparing(Entry::due).thenComparingLong(Entry::seq));
    private long nextSeq;

    public ManualScheduler(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Cancellable schedule(Runnable task, Duration delay) {
        return add(task, clock.instant().plus(delay), null, new AtomicBoolean());
    }

    @Override
    public Cancellable scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("period must be > 0");
        }
        return add(task, clock.instant().plus(initialDelay), period, new AtomicBoolean());
    }

    /**
     * Runs every task due at or before now, including tasks scheduled by tasks that ran and the
     * repeated firings of a fixed-rate task. Returns the number of runs.
     */
    public int runDue() {
        int runs = 0;
        Entry e;
        while ((e = pollDue(clock.instant())) != null) {
            if (e.cancelled().get()) {
                continue;
            }
            if (e.period() != null) {
                add(e.task(), e.due().plus(e.period()), e.period(), e.cancelled());
            }
            e.task().run();
            runs++;
        }
        return runs;
    }

    /** Scheduled, not yet cancelled entries. */
    public synchronized long pendingCount() {
        return queue.stream().filter(e -> !e.cancelled().get()).count();
    }

    private synchronized Entry pollDue(Instant now) {
        Entry head = queue.peek();
        return head != null && !head.due().isAfter(now) ? queue.poll() : null;
    }

    private synchronized Cancellable add(Runnable task, Instant due, Duration period, AtomicBoolean cancelled) {
        queue.add(new Entry(task, due, period, cancelled, nextSeq++));
        return () -> cancelled.set(true);
    }

    private record Entry(Runnable task, Instant due, Duration period, AtomicBoolean cancelled, long seq) { }
}
