package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.port.Scheduler;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class VirtualThreadSchedulerTest {

    @Test
    @DisplayName("a one-shot task runs on a virtual thread")
    void runsOnVirtualThread() throws InterruptedException {
        try (var scheduler = new VirtualThreadScheduler()) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean virtual = new AtomicBoolean();
            scheduler.schedule(() -> {
                virtual.set(Thread.currentThread().isVirtual());
                done.countDown();
            }, Duration.ofMillis(5));
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(virtual).isTrue();
        }
    }

    @Test
    @DisplayName("a cancelled task does not run, even when a later task has run")
    void cancelledTaskDoesNotRun() throws InterruptedException {
        try (var scheduler = new VirtualThreadScheduler()) {
            AtomicBoolean cancelledRan = new AtomicBoolean();
            CountDownLatch later = new CountDownLatch(1);
            Scheduler.Cancellable c = scheduler.schedule(() -> cancelledRan.set(true), Duration.ofMillis(20));
            c.cancel();
            scheduler.schedule(later::countDown, Duration.ofMillis(60));
            assertThat(later.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelledRan).isFalse();
        }
    }

    @Test
    @DisplayName("a fixed-rate task repeats until cancelled; a throwing run does not stop it")
    void fixedRateRepeatsUntilCancelled() throws InterruptedException {
        try (var scheduler = new VirtualThreadScheduler()) {
            AtomicInteger runs = new AtomicInteger();
            CountDownLatch three = new CountDownLatch(3);
            Scheduler.Cancellable c = scheduler.scheduleAtFixedRate(() -> {
                runs.incrementAndGet();
                three.countDown();
                throw new IllegalStateException("boom");
            }, Duration.ZERO, Duration.ofMillis(5));
            assertThat(three.await(2, TimeUnit.SECONDS)).isTrue();
            c.cancel();

            int afterCancel = runs.get();
            CountDownLatch marker = new CountDownLatch(1);
            scheduler.schedule(marker::countDown, Duration.ofMillis(50));
            assertThat(marker.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(runs.get()).isBetween(afterCancel, afterCancel + 1);   // at most one run already in flight
        }
    }
}
