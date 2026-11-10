package com.caseware.fanout.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.port.Delivery;
import com.caseware.fanout.testkit.CrashInjector;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.FakeEngagementLoader.Step;
import com.caseware.fanout.testkit.ManualScheduler;
import com.caseware.fanout.testkit.SimulatedCrash;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Story 12, deterministic mode: heartbeat ticks and cancellation, hard timeout (G-10, G-28). */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class HeartbeatTimeoutTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();

    private static ManualScheduler scheduler(ExecutorHarness h) {
        return (ManualScheduler) h.scheduler;
    }

    @Test
    @DisplayName("AC2: each heartbeat tick calls extendVisibility(d, 3 min); no tick after the load returns")
    void heartbeatExtendsEveryInterval() throws InterruptedException {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            List<String> calls = new CopyOnWriteArrayList<>();
            var ports = new ExecutorPorts(new RecordingPorts.RecordingQueue(h.queue, calls), h.ledger, h.jobs,
                    h.publisher, h.loader, h.scheduler, h.clock);
            var executor = new LoadExecutor("w-1", ports, h.executor(0).limiter(), CONFIG, new Random(1),
                    CrashHook.NONE);
            h.loader.holdCalls();
            h.send("job-1", "firm-1", "eng-1");

            Thread owner = Thread.ofVirtual().start(executor::pollOnce);
            h.loader.awaitInFlight(1, Duration.ofSeconds(5));
            for (int i = 0; i < 3; i++) {
                h.mutableClock().advance(CONFIG.heartbeatInterval());
                scheduler(h).runDue();
            }
            assertThat(calls).containsExactly("extend:PT3M", "extend:PT3M", "extend:PT3M");

            h.loader.releaseCalls();
            owner.join();
            h.mutableClock().advance(CONFIG.loaderHardTimeout());
            assertThat(scheduler(h).runDue()).isZero();
            assertThat(calls).filteredOn(c -> c.startsWith("extend")).hasSize(3);
            assertThat(calls).endsWith("ack");
        }
    }

    static Stream<Step> exitPaths() {
        return Stream.of(Step.ok(), Step.throttled(Duration.ofSeconds(5)), Step.transientFailure(),
                Step.notFound(), Step.permanent(), Step.runtime(new IllegalStateException("bug")));
    }

    @ParameterizedTest
    @MethodSource("exitPaths")
    @DisplayName("AC2 / EC-27: heartbeat and timeout are cancelled on every exit path of the load")
    void cancelledOnEveryExitPath(Step step) {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 1);
            h.loader.script("eng-1", step);
            h.send("job-1", "firm-1", "eng-1");

            h.executor(0).pollOnce();

            assertThat(h.loader.calls("eng-1")).isEqualTo(1);
            assertThat(scheduler(h).pendingCount()).isZero();
            assertThat(h.executor(0).limiter().freePermits()).isEqualTo(CONFIG.permitsPerTask());
        }
    }

    @Test
    @DisplayName("AC2 / EC-27: heartbeat and timeout are cancelled when the executor crashes after the load")
    void cancelledOnCrash() {
        CrashInjector crash = new CrashInjector();
        crash.arm(CrashHook.Point.AFTER_LOAD);
        try (var h = ExecutorHarness.deterministic(CONFIG, crash)) {
            h.send("job-1", "firm-1", "eng-1");
            try {
                h.executor(0).pollOnce();
            } catch (SimulatedCrash expected) {
                // process death (G-11)
            }
            assertThat(scheduler(h).pendingCount()).isZero();
        }
    }

    @Test
    @DisplayName("AC3 / EC-09: a load exceeding the hard timeout is interrupted, treated as Transient and requeued")
    void hardTimeoutIsTransient() throws InterruptedException {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 1);
            h.loader.script("eng-1", Step.hang());
            LoadRequest sent = h.send("job-1", "firm-1", "eng-1");
            AtomicBoolean leakedInterrupt = new AtomicBoolean(true);

            Thread owner = Thread.ofVirtual().start(() -> {
                h.executor(0).pollOnce();
                leakedInterrupt.set(Thread.currentThread().isInterrupted());
            });
            h.loader.awaitInFlight(1, Duration.ofSeconds(5));
            h.mutableClock().advance(CONFIG.loaderHardTimeout().minusMillis(1));
            scheduler(h).runDue();
            assertThat(h.loader.inFlight()).isEqualTo(1);                          // not yet timed out

            h.mutableClock().advance(Duration.ofMillis(1));
            scheduler(h).runDue();
            owner.join();

            assertThat(leakedInterrupt).isFalse();
            assertThat(h.loader.calls("eng-1")).isEqualTo(1);
            assertThat(h.publisher.observedEvents()).isEmpty();
            assertThat(h.ledger.status("eng-1")).isEmpty();                        // claim released (G-21)
            assertThat(h.executor(0).limiter().freePermits()).isEqualTo(CONFIG.permitsPerTask());
            assertThat(h.queue.deadLetters()).isEmpty();
            assertThat(scheduler(h).pendingCount()).isZero();
            Delivery<LoadRequest> again = receiveRequeued(h);
            assertThat(again.body()).isEqualTo(sent.withNextAttempt());           // Transient: attempt + 1
            assertThat(again.receiveCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("AC3 / G-28: a loader that ignores the interrupt keeps its permit until it returns; its late result is discarded")
    void lateResultDiscardedPermitHeldUntilReturn() throws InterruptedException {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 1);
            h.loader.holdCalls();
            h.loader.script("eng-1", Step.stubborn());
            LoadRequest sent = h.send("job-1", "firm-1", "eng-1");

            Thread owner = Thread.ofVirtual().start(() -> h.executor(0).pollOnce());
            h.loader.awaitInFlight(1, Duration.ofSeconds(5));
            h.mutableClock().advance(CONFIG.loaderHardTimeout());
            scheduler(h).runDue();

            assertThat(h.loader.inFlight()).isEqualTo(1);                          // the call is still running
            assertThat(h.executor(0).limiter().freePermits()).isEqualTo(CONFIG.permitsPerTask() - 1);

            h.loader.releaseCalls();
            owner.join();

            assertThat(h.publisher.observedEvents()).isEmpty();                    // late result discarded
            assertThat(h.ledger.status("eng-1")).isEmpty();
            assertThat(h.executor(0).limiter().freePermits()).isEqualTo(CONFIG.permitsPerTask());
            assertThat(receiveRequeued(h).body()).isEqualTo(sent.withNextAttempt());
        }
    }

    private static Delivery<LoadRequest> receiveRequeued(ExecutorHarness h) {
        h.mutableClock().advance(CONFIG.maxRequeueDelay());
        List<Delivery<LoadRequest>> got = h.queue.receive(10, Duration.ZERO);
        assertThat(got).hasSize(1);
        return got.get(0);
    }
}
