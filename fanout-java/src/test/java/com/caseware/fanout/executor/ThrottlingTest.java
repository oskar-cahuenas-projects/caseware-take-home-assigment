package com.caseware.fanout.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.FailureReason;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.executor.CircuitBreaker.State;
import com.caseware.fanout.port.Delivery;
import com.caseware.fanout.port.WorkQueue;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.FakeEngagementLoader.Step;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Story 10, deterministic mode. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ThrottlingTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();

    private static Delivery<LoadRequest> receiveAfter(ExecutorHarness h, Duration wait) {
        h.mutableClock().advance(wait);
        List<Delivery<LoadRequest>> got = h.queue.receive(10, Duration.ZERO);
        assertThat(got).hasSize(1);
        return got.get(0);
    }

    @Test
    @DisplayName("AC1: Throttled → permit and claim released, same body requeued after ≥ retryAfter, count reset")
    void throttledRequeues() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 1);
            h.loader.script("eng-1", Step.throttled(Duration.ofMinutes(2)));
            LoadRequest sent = h.send("job-1", "firm-1", "eng-1");

            h.executor(0).pollOnce();

            assertThat(h.executor(0).limiter().freePermits()).isEqualTo(CONFIG.permitsPerTask());
            assertThat(h.ledger.status("eng-1")).isEmpty();
            assertThat(h.queue.depth()).isEqualTo(1);
            h.mutableClock().advance(Duration.ofMinutes(2).minusMillis(1));
            assertThat(h.queue.receive(10, Duration.ZERO)).isEmpty();
            Delivery<LoadRequest> again = receiveAfter(h, CONFIG.maxRequeueDelay());
            assertThat(again.receiveCount()).isEqualTo(1);
            assertThat(again.body()).isEqualTo(sent);                         // attempt unchanged by throttling
            assertThat(h.queue.deadLetters()).isEmpty();
        }
    }

    @Test
    @DisplayName("AC1 / EC-10: a retryAfter above 15 min is capped at 15 min")
    void retryAfterCapped() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.loader.script("eng-1", Step.throttled(Duration.ofMinutes(20)));
            h.send("job-1", "firm-1", "eng-1");
            h.executor(0).pollOnce();

            h.mutableClock().advance(Duration.ofMinutes(15).minusMillis(1));
            assertThat(h.queue.receive(10, Duration.ZERO)).isEmpty();
            assertThat(receiveAfter(h, Duration.ofMillis(1)).receiveCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("AC2 / G-16: Transient → requeued withNextAttempt after a backoff below base·2^attempt")
    void transientRetriesWithNextAttempt() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.loader.script("eng-1", Step.transientFailure());
            LoadRequest sent = h.send("job-1", "firm-1", "eng-1");

            h.executor(0).pollOnce();

            Delivery<LoadRequest> again = receiveAfter(h, CONFIG.backoffBase());
            assertThat(again.body()).isEqualTo(sent.withNextAttempt());
            assertThat(again.receiveCount()).isEqualTo(1);
            assertThat(h.ledger.status("eng-1")).isEmpty();
        }
    }

    @Test
    @DisplayName("AC3: K consecutive failures open the breaker and receive is not called until cool-down")
    void breakerStopsReceiving() {
        AtomicInteger receives = new AtomicInteger();
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.loader.setDefault(Step.throttled(Duration.ZERO));
            for (int i = 0; i < 5; i++) {
                h.send("job-1", "firm-" + i, "eng-" + i);
            }
            LoadExecutor ex = countingExecutor(h, receives);

            assertThat(ex.pollOnce()).isEqualTo(5);
            assertThat(ex.breakerState()).isEqualTo(State.OPEN);
            int receivesWhenOpened = receives.get();

            h.mutableClock().advance(CONFIG.breakerCooldown().minusMillis(1));
            assertThat(ex.pollOnce()).isZero();
            assertThat(ex.pollOnce()).isZero();
            assertThat(receives.get()).isEqualTo(receivesWhenOpened);           // stopped receiving
            assertThat(h.queue.deadLetters()).isEmpty();
        }
    }

    @Test
    @DisplayName("AC4: HALF_OPEN admits exactly one probe; success closes, failure re-opens")
    void halfOpenProbe() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.loader.setDefault(Step.throttled(Duration.ZERO));
            for (int i = 0; i < 20; i++) {
                h.send("job-1", "firm-" + i, "eng-" + i);
            }
            LoadExecutor ex = h.executor(0);
            ex.pollOnce();                                                         // 10 throttled → OPEN
            assertThat(ex.breakerState()).isEqualTo(State.OPEN);

            h.mutableClock().advance(CONFIG.breakerCooldown());                    // probe fails → OPEN
            int before = h.loader.totalCalls();
            assertThat(ex.pollOnce()).isEqualTo(1);
            assertThat(h.loader.totalCalls()).isEqualTo(before + 1);
            assertThat(ex.breakerState()).isEqualTo(State.OPEN);

            h.loader.setDefault(Step.ok());
            h.mutableClock().advance(CONFIG.breakerCooldown());                    // probe succeeds → CLOSED
            assertThat(ex.pollOnce()).isEqualTo(1);
            assertThat(ex.breakerState()).isEqualTo(State.CLOSED);
            assertThat(ex.pollOnce()).isGreaterThan(1);
        }
    }

    @Test
    @DisplayName("AC6: the 8th Transient → EngagementLoadFailed(TRANSIENT_EXHAUSTED), outcome DLQ, dead-lettered")
    void transientExhausted() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 1);
            h.loader.setDefault(Step.transientFailure());
            LoadRequest sent = h.send("job-1", "firm-1", "eng-1");

            for (int i = 0; i < CONFIG.maxTransientAttempts(); i++) {
                assertThat(h.executor(0).pollOnce()).isEqualTo(1);
                h.mutableClock().advance(CONFIG.maxRequeueDelay());                // past any backoff and cool-down
            }

            assertThat(h.loader.calls("eng-1")).isEqualTo(CONFIG.maxTransientAttempts());
            assertThat(h.queue.depth()).isZero();
            assertThat(h.queue.deadLetters()).singleElement()
                    .satisfies(dl -> assertThat(dl.reason()).isEqualTo("TRANSIENT_EXHAUSTED"));
            assertThat(h.publisher.failedEvents()).singleElement().satisfies(e -> {
                assertThat(e.reason()).isEqualTo(FailureReason.TRANSIENT_EXHAUSTED);
                assertThat(e.eventId()).isEqualTo("eng-1:failed:" + sent.requestId());
                assertThat(e.jobId()).isEqualTo("job-1");
            });
            assertThat(h.jobs.outcome("job-1", "eng-1")).contains(Outcome.DLQ);
            assertThat(h.ledger.status("eng-1")).isEmpty();
        }
    }

    @Test
    @DisplayName("AC6: Throttled never dead-letters, however often it repeats")
    void throttledNeverDeadLetters() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.loader.setDefault(Step.throttled(Duration.ZERO));
            h.send("job-1", "firm-1", "eng-1");

            for (int i = 0; i < 30; i++) {
                h.executor(0).pollOnce();
                h.mutableClock().advance(CONFIG.maxRequeueDelay());
            }

            assertThat(h.loader.calls("eng-1")).isGreaterThan(CONFIG.maxTransientAttempts());
            assertThat(h.queue.deadLetters()).isEmpty();
            assertThat(h.publisher.failedEvents()).isEmpty();
            assertThat(h.queue.depth()).isEqualTo(1);
        }
    }

    /** Executor 0's wiring, with a queue wrapper counting {@code receive} calls. */
    private static LoadExecutor countingExecutor(ExecutorHarness h, AtomicInteger receives) {
        WorkQueue<LoadRequest> q = h.queue;
        WorkQueue<LoadRequest> counting = new WorkQueue<>() {
            public List<Delivery<LoadRequest>> receive(int max, Duration wait) { receives.incrementAndGet(); return q.receive(max, wait); }
            public void extendVisibility(Delivery<LoadRequest> d, Duration by) { q.extendVisibility(d, by); }
            public void ack(Delivery<LoadRequest> d) { q.ack(d); }
            public void requeueWithDelay(Delivery<LoadRequest> d, Duration delay) { q.requeueWithDelay(d, delay); }
            public void requeueWithDelay(Delivery<LoadRequest> d, LoadRequest body, Duration delay) { q.requeueWithDelay(d, body, delay); }
            public void deadLetter(Delivery<LoadRequest> d, String reason) { q.deadLetter(d, reason); }
        };
        ExecutorPorts p = h.ports;
        return new LoadExecutor("w-count", new ExecutorPorts(counting, p.ledger(), p.jobs(), p.publisher(),
                p.loader(), p.scheduler(), p.clock()), h.executor(0).limiter(), h.config, new Random(1), CrashHook.NONE);
    }
}
