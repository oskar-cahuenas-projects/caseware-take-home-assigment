package com.caseware.fanout.adapter.memory;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import com.caseware.fanout.port.Delivery;
import com.caseware.fanout.port.WorkQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One SQS-standard-like lane with its own DLQ. Visibility and delays are evaluated lazily against the
 * injected clock (G-10); delivery order is by visible time, then send order. {@code receive} never
 * blocks: {@code wait} is accepted for port compatibility and ignored, and callers idle on the scheduler
 * instead. Thread-safe: every operation runs under one {@link ReentrantLock}.
 */
public final class InMemoryWorkQueue<T> implements WorkQueue<T> {

    private static final System.Logger LOG = System.getLogger(InMemoryWorkQueue.class.getName());

    /** A dead-lettered message, with why it was moved and how often it had been received. */
    public record DeadLetter<T>(T body, String reason, int receiveCount) { }

    private final String name;
    private final Clock clock;
    private final Duration visibilityTimeout;
    private final int maxReceiveCount;
    private final Duration maxDelay;

    private final ReentrantLock lock = new ReentrantLock();
    private final TreeSet<Message<T>> messages =
            new TreeSet<>(Comparator.<Message<T>, Instant>comparing(m -> m.visibleAt).thenComparingLong(m -> m.seq));
    private final Map<String, Message<T>> byReceipt = new HashMap<>();
    private final List<DeadLetter<T>> deadLetters = new ArrayList<>();
    private long nextSeq;
    private long nextReceipt;

    public InMemoryWorkQueue(String name, Clock clock, Duration visibilityTimeout, int maxReceiveCount, Duration maxDelay) {
        this.name = Objects.requireNonNull(name, "name");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.visibilityTimeout = requirePositive(visibilityTimeout, "visibilityTimeout");
        if (maxReceiveCount < 1) {
            throw new IllegalArgumentException("maxReceiveCount must be >= 1");
        }
        this.maxReceiveCount = maxReceiveCount;
        this.maxDelay = requirePositive(maxDelay, "maxDelay");
    }

    /** Producer side (not part of the consumer port): enqueue a new message, visible now. */
    public void send(T body) {
        send(body, Duration.ZERO);
    }

    /** Enqueue a new message visible after {@code delay}, capped at {@code maxDelay} (G-12). */
    public void send(T body, Duration delay) {
        Objects.requireNonNull(body, "body");
        lock.lock();
        try {
            enqueue(body, delay);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Delivery<T>> receive(int max, Duration wait) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be >= 1");
        }
        lock.lock();
        try {
            Instant now = clock.instant();
            List<Delivery<T>> out = new ArrayList<>(max);
            while (out.size() < max && !messages.isEmpty() && !messages.first().visibleAt.isAfter(now)) {
                Message<T> m = messages.pollFirst();
                forgetReceipt(m);
                if (m.receiveCount >= maxReceiveCount) {
                    deadLetters.add(new DeadLetter<>(m.body, "maxReceiveCount " + maxReceiveCount + " exceeded", m.receiveCount));
                    LOG.log(WARNING, "queue={0} message=m-{1} dead-lettered after {2} receives", name, m.seq, m.receiveCount);
                    continue;
                }
                m.receiveCount++;
                m.receipt = "r-" + nextReceipt++;
                m.visibleAt = now.plus(visibilityTimeout);
                messages.add(m);
                byReceipt.put(m.receipt, m);
                out.add(new Delivery<>(m.body, m.receipt, m.receiveCount));
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void extendVisibility(Delivery<T> d, Duration by) {
        requireNonNegative(by, "by");
        lock.lock();
        try {
            Message<T> m = byReceipt.get(d.receipt());
            if (m != null) {
                messages.remove(m);
                m.visibleAt = clock.instant().plus(by);
                messages.add(m);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void ack(Delivery<T> d) {
        lock.lock();
        try {
            Message<T> m = byReceipt.remove(d.receipt());
            if (m != null) {
                messages.remove(m);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void requeueWithDelay(Delivery<T> d, Duration delay) {
        requeueWithDelay(d, d.body(), delay);
    }

    @Override
    public void requeueWithDelay(Delivery<T> d, T body, Duration delay) {
        Objects.requireNonNull(body, "body");
        requireNonNegative(delay, "delay");
        lock.lock();
        try {
            Message<T> m = byReceipt.remove(d.receipt());
            if (m != null) {
                messages.remove(m);
                enqueue(body, delay);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deadLetter(Delivery<T> d, String reason) {
        lock.lock();
        try {
            Message<T> m = byReceipt.remove(d.receipt());
            if (m != null) {
                messages.remove(m);
                deadLetters.add(new DeadLetter<>(m.body, reason, m.receiveCount));
                LOG.log(INFO, "queue={0} message=m-{1} dead-lettered: {2}", name, m.seq, reason);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Messages in the queue, visible or in flight; excludes the DLQ. */
    public int depth() {
        lock.lock();
        try {
            return messages.size();
        } finally {
            lock.unlock();
        }
    }

    /** Snapshot of the DLQ in arrival order. */
    public List<DeadLetter<T>> deadLetters() {
        lock.lock();
        try {
            return List.copyOf(deadLetters);
        } finally {
            lock.unlock();
        }
    }

    private void enqueue(T body, Duration delay) {
        requireNonNegative(delay, "delay");
        Duration effective = delay;
        if (delay.compareTo(maxDelay) > 0) {
            LOG.log(INFO, "queue={0} delay {1} capped at {2} (G-12)", name, delay, maxDelay);
            effective = maxDelay;
        }
        messages.add(new Message<>(body, nextSeq++, clock.instant().plus(effective)));
    }

    private void forgetReceipt(Message<T> m) {
        if (m.receipt != null) {
            byReceipt.remove(m.receipt);
            m.receipt = null;
        }
    }

    private static Duration requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return d;
    }

    private static void requireNonNegative(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    /** Mutable message state; only touched under {@code lock}, and removed from the set before re-keying. */
    private static final class Message<T> {
        final T body;
        final long seq;
        Instant visibleAt;
        int receiveCount;
        String receipt;

        Message(T body, long seq, Instant visibleAt) {
            this.body = body;
            this.seq = seq;
            this.visibleAt = visibleAt;
        }
    }
}
