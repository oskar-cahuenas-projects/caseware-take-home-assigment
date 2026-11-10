package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.EngagementLoadFailed;
import com.caseware.fanout.domain.EngagementStateObserved;
import com.caseware.fanout.port.ObservationPublisher;
import com.caseware.fanout.port.PublishFailedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Event-bus stand-in (I6, G-01): records events in publish order and can fail the next N publishes.
 * A failed publish is not recorded. Thread-safe: one {@link ReentrantLock}.
 */
public final class InMemoryObservationPublisher implements ObservationPublisher {

    private final ReentrantLock lock = new ReentrantLock();
    private final List<Record> events = new ArrayList<>();
    private int failRemaining;
    private int failedAttempts;

    @Override
    public void publish(EngagementStateObserved e) {
        record(e, e.eventId());
    }

    @Override
    public void publish(EngagementLoadFailed e) {
        record(e, e.eventId());
    }

    /** The next {@code n} publishes throw {@link PublishFailedException}. */
    public void failNext(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        lock.lock();
        try {
            failRemaining = n;
        } finally {
            lock.unlock();
        }
    }

    /** All recorded events, in publish order. */
    public List<Record> events() {
        lock.lock();
        try {
            return List.copyOf(events);
        } finally {
            lock.unlock();
        }
    }

    public List<EngagementStateObserved> observedEvents() {
        return events().stream().filter(EngagementStateObserved.class::isInstance)
                .map(EngagementStateObserved.class::cast).toList();
    }

    public List<EngagementLoadFailed> failedEvents() {
        return events().stream().filter(EngagementLoadFailed.class::isInstance)
                .map(EngagementLoadFailed.class::cast).toList();
    }

    /** Publishes rejected by {@link #failNext}. */
    public int failedAttempts() {
        lock.lock();
        try {
            return failedAttempts;
        } finally {
            lock.unlock();
        }
    }

    private void record(Record event, String eventId) {
        lock.lock();
        try {
            if (failRemaining > 0) {
                failRemaining--;
                failedAttempts++;
                throw new PublishFailedException(eventId, null);
            }
            events.add(event);
        } finally {
            lock.unlock();
        }
    }
}
