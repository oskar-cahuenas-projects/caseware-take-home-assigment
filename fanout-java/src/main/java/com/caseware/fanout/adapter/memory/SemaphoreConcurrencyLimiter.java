package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.Checks;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.port.ConcurrencyLimiter;
import com.caseware.fanout.port.Permit;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One task's share of the loader budget: a non-blocking {@link Semaphore} of {@code ⌊C/N⌋} permits plus a
 * per-firm in-flight cap (§C, §F static split, G-15). A firm slot is reserved first, then a task permit;
 * the firm slot is rolled back if no task permit is free. Thread-safe.
 */
public final class SemaphoreConcurrencyLimiter implements ConcurrencyLimiter {

    private final int permits;
    private final int firmCap;
    private final Semaphore budget;
    private final ConcurrentHashMap<String, Integer> perFirm = new ConcurrentHashMap<>();

    public SemaphoreConcurrencyLimiter(int permits, int firmCap) {
        if (permits < 1 || firmCap < 1 || firmCap > permits) {
            throw new IllegalArgumentException("need 1 <= firmCap <= permits, got permits=" + permits + ", firmCap=" + firmCap);
        }
        this.permits = permits;
        this.firmCap = firmCap;
        this.budget = new Semaphore(permits);
    }

    /** Sized from the config: {@code permitsPerTask()} and {@code firmCap()}. */
    public static SemaphoreConcurrencyLimiter forTask(ExecutorConfig config) {
        return new SemaphoreConcurrencyLimiter(config.permitsPerTask(), config.firmCap());
    }

    @Override
    public Optional<Permit> tryAcquire(String firmId) {
        Checks.nonBlank(firmId, "firmId");
        if (!reserveFirmSlot(firmId)) {
            return Optional.empty();
        }
        if (!budget.tryAcquire()) {
            releaseFirmSlot(firmId);
            return Optional.empty();
        }
        return Optional.of(new TaskPermit(firmId));
    }

    /** Task permits currently held. */
    public int inUse() {
        return permits - budget.availablePermits();
    }

    /** Permits currently held by {@code firmId}. */
    public int inUse(String firmId) {
        return perFirm.getOrDefault(firmId, 0);
    }

    @Override
    public int freePermits() {
        return budget.availablePermits();
    }

    public int permits() {
        return permits;
    }

    public int firmCap() {
        return firmCap;
    }

    private boolean reserveFirmSlot(String firmId) {
        boolean[] reserved = new boolean[1];
        perFirm.compute(firmId, (f, n) -> {
            int current = n == null ? 0 : n;
            if (current >= firmCap) {
                return n;
            }
            reserved[0] = true;
            return current + 1;
        });
        return reserved[0];
    }

    private void releaseFirmSlot(String firmId) {
        perFirm.computeIfPresent(firmId, (f, n) -> n <= 1 ? null : n - 1);
    }

    /** Releases its firm slot and task permit on the first {@code close()} only (G-02). */
    private final class TaskPermit implements Permit {
        private final String firmId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TaskPermit(String firmId) {
            this.firmId = firmId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                budget.release();
                releaseFirmSlot(firmId);
            }
        }
    }
}
