package com.caseware.fanout.port;

import java.time.Duration;
import java.util.List;

/**
 * One lane of load requests (I4), with SQS semantics: at-least-once delivery, visibility timeout,
 * and a per-lane DLQ after {@code maxReceiveCount} receives. Implementations must be thread-safe.
 */
public interface WorkQueue<T> {

    /** Up to {@code max} visible messages; each becomes invisible for the visibility timeout. */
    List<Delivery<T>> receive(int max, Duration wait);

    /** Keeps an in-flight message invisible for {@code by} from now (heartbeat). */
    void extendVisibility(Delivery<T> d, Duration by);

    /** Deletes the message. */
    void ack(Delivery<T> d);

    /**
     * Replaces the message with a new one visible after {@code delay} (capped at 15 min, G-12).
     * The receive count resets, so deferrals never count towards the DLQ threshold.
     */
    void requeueWithDelay(Delivery<T> d, Duration delay);

    /** Moves the message to this lane's DLQ. Acts as the ack. */
    void deadLetter(Delivery<T> d, String reason);
}
