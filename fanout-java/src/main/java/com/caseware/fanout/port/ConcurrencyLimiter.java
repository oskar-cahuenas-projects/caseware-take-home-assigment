package com.caseware.fanout.port;

import java.util.Optional;

/** Per-task limiter: {@code ⌊C/N⌋} permits plus a per-firm in-flight cap. Implementations must be thread-safe. */
public interface ConcurrencyLimiter {

    /** A permit, or empty when the task or the firm is at its cap. Never blocks. */
    Optional<Permit> tryAcquire(String firmId);

    /** Task permits free right now, ignoring firm caps; sizes each receive (G-24). */
    int freePermits();
}
