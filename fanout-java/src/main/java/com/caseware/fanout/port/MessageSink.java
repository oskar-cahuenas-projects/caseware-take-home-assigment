package com.caseware.fanout.port;

/**
 * Producer side of a queue (G-29): the Planner sends {@code ApplyHeadBatch} to I3 and {@code LoadRequest}
 * to I4 through it. At-least-once; a send that throws may or may not have been delivered.
 * Implementations must be thread-safe.
 */
@FunctionalInterface
public interface MessageSink<T> {
    void send(T message);
}
