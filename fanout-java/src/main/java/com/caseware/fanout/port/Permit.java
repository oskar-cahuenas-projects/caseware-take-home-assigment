package com.caseware.fanout.port;

/** One loader-call slot (G-02). {@code close()} is idempotent, thread-safe and never throws. */
public interface Permit extends AutoCloseable {
    @Override
    void close();
}
