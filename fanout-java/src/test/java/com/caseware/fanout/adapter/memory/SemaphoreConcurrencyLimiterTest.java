package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.port.Permit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class SemaphoreConcurrencyLimiterTest {

    @Test
    @DisplayName("AC1: with the task budget held, tryAcquire is empty")
    void budgetExhausted() {
        var limiter = new SemaphoreConcurrencyLimiter(3, 3);
        List<Permit> held = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            held.add(limiter.tryAcquire("firm-" + i).orElseThrow());
        }

        assertThat(limiter.tryAcquire("firm-x")).isEmpty();
        assertThat(limiter.inUse()).isEqualTo(3);
        assertThat(limiter.freePermits()).isZero();
        assertThat(limiter.inUse("firm-x")).isZero();          // failed attempt rolled back its firm slot

        held.get(0).close();
        assertThat(limiter.tryAcquire("firm-x")).isPresent();
    }

    @Test
    @DisplayName("AC2: a firm at its cap is refused while other firms still acquire")
    void firmCap() {
        var limiter = new SemaphoreConcurrencyLimiter(10, 2);
        Permit a1 = limiter.tryAcquire("big").orElseThrow();
        limiter.tryAcquire("big").orElseThrow();

        assertThat(limiter.tryAcquire("big")).isEmpty();
        assertThat(limiter.tryAcquire("small")).isPresent();
        assertThat(limiter.inUse("big")).isEqualTo(2);
        assertThat(limiter.inUse()).isEqualTo(3);

        a1.close();
        assertThat(limiter.tryAcquire("big")).isPresent();
    }

    @Test
    @DisplayName("AC3: closing a permit twice releases it once")
    void doubleCloseReleasesOnce() {
        var limiter = new SemaphoreConcurrencyLimiter(2, 2);
        Permit p = limiter.tryAcquire("f").orElseThrow();
        limiter.tryAcquire("f").orElseThrow();

        p.close();
        p.close();

        assertThat(limiter.inUse()).isEqualTo(1);
        assertThat(limiter.inUse("f")).isEqualTo(1);
        assertThat(limiter.freePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC3: try-with-resources releases on an exception path")
    @SuppressWarnings("try")
    void releasedOnException() {
        var limiter = new SemaphoreConcurrencyLimiter(1, 1);
        assertThatThrownBy(() -> {
            try (Permit p = limiter.tryAcquire("f").orElseThrow()) {
                throw new IllegalStateException("loader blew up");
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(limiter.inUse()).isZero();
    }

    @Test
    @DisplayName("AC5: forTask derives ⌊C/N⌋ permits and max(1, ⌊0.2·⌊C/N⌋⌋) firm cap")
    void forTaskSizing() {
        var c50n4 = SemaphoreConcurrencyLimiter.forTask(
                ExecutorConfig.production().toBuilder().capacity(50).tasks(4).build());
        assertThat(c50n4.permits()).isEqualTo(12);
        assertThat(c50n4.firmCap()).isEqualTo(2);

        var c10n4 = SemaphoreConcurrencyLimiter.forTask(
                ExecutorConfig.production().toBuilder().capacity(10).tasks(4).build());
        assertThat(c10n4.permits()).isEqualTo(2);
        assertThat(c10n4.firmCap()).isEqualTo(1);
    }

    @Test
    @DisplayName("invalid sizing is rejected")
    void invalidSizing() {
        assertThatThrownBy(() -> new SemaphoreConcurrencyLimiter(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemaphoreConcurrencyLimiter(5, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemaphoreConcurrencyLimiter(5, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("try")
    @DisplayName("AC4: 200 virtual threads never exceed the budget or a firm cap; in-use ends at 0")
    void hammer() throws InterruptedException {
        int permits = 25;
        int cap = 5;
        int firms = 10;
        var limiter = new SemaphoreConcurrencyLimiter(permits, cap);

        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ConcurrentHashMap<String, AtomicInteger> firmInUse = new ConcurrentHashMap<>();
        AtomicInteger firmPeak = new AtomicInteger();
        AtomicInteger acquired = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 200; t++) {
            int id = t;
            threads.add(Thread.ofVirtual().start(() -> {
                await(start);
                for (int i = 0; i < 500; i++) {
                    String firm = "firm-" + ((id + i) % firms);
                    Optional<Permit> p = limiter.tryAcquire(firm);
                    if (p.isEmpty()) {
                        Thread.yield();
                        continue;
                    }
                    try (Permit permit = p.get()) {
                        acquired.incrementAndGet();
                        peak.accumulateAndGet(inUse.incrementAndGet(), Math::max);
                        AtomicInteger f = firmInUse.computeIfAbsent(firm, k -> new AtomicInteger());
                        firmPeak.accumulateAndGet(f.incrementAndGet(), Math::max);
                        Thread.yield();
                        f.decrementAndGet();
                        inUse.decrementAndGet();
                        permit.close();                          // explicit close + try-with-resources = double close
                    }
                }
            }));
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }

        assertThat(acquired.get()).isPositive();
        assertThat(peak.get()).isLessThanOrEqualTo(permits);
        assertThat(firmPeak.get()).isLessThanOrEqualTo(cap);
        assertThat(limiter.inUse()).isZero();
        assertThat(limiter.freePermits()).isEqualTo(permits);
        for (int f = 0; f < firms; f++) {
            assertThat(limiter.inUse("firm-" + f)).isZero();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
