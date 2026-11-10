package com.caseware.fanout.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryFanoutJobStore;
import com.caseware.fanout.adapter.memory.InMemoryLoadLedger;
import com.caseware.fanout.adapter.memory.InMemoryObservationPublisher;
import com.caseware.fanout.adapter.memory.InMemoryWorkQueue;
import com.caseware.fanout.adapter.memory.SemaphoreConcurrencyLimiter;
import com.caseware.fanout.domain.Lane;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.executor.CircuitBreaker.State;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.FakeEngagementLoader;
import com.caseware.fanout.testkit.FakeEngagementLoader.Step;
import com.caseware.fanout.testkit.ManualScheduler;
import com.caseware.fanout.testkit.MutableClock;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class PermanentErrorTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();

    static Stream<Arguments> permanentFailures() {
        return Stream.of(Arguments.of("NotFound", Step.notFound()), Arguments.of("Permanent", Step.permanent()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("permanentFailures")
    @DisplayName("AC1: one load, then release → LoadFailed published → outcome DLQ → dead-letter, in that order")
    void deadLetterOrder(String name, Step step) {
        List<String> calls = new CopyOnWriteArrayList<>();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
        var queue = new InMemoryWorkQueue<LoadRequest>("publish", clock, CONFIG.visibilityTimeout(), 5, CONFIG.maxRequeueDelay());
        var loader = new FakeEngagementLoader(clock);
        loader.script("eng-1", step);
        var ports = new ExecutorPorts(
                new RecordingPorts.RecordingQueue(queue, calls),
                new RecordingPorts.RecordingLedger(new InMemoryLoadLedger(clock), calls),
                new RecordingPorts.RecordingJobs(new InMemoryFanoutJobStore(clock), calls),
                new RecordingPorts.RecordingPublisher(new InMemoryObservationPublisher(), calls),
                new RecordingPorts.RecordingLoader(loader, calls), new ManualScheduler(clock), clock);
        var limiter = new RecordingPorts.RecordingLimiter(new SemaphoreConcurrencyLimiter(25, 5), calls);
        queue.send(new LoadRequest("req-1", "job-1", "firm-1", "eng-1", "tpl-1", Lane.PUBLISH, 0, clock.instant(),
                "corr-1", 0, 1, "ca-central-1"));

        new LoadExecutor("w-1", ports, limiter, CONFIG, new Random(1), CrashHook.NONE).pollOnce();

        assertThat(calls).containsExactly("claim", "permit", "load", "permit.close", "release",
                "publishFailed", "outcome:DLQ", "deadLetter");
        assertThat(loader.calls("eng-1")).isEqualTo(1);
        assertThat(limiter.delegate().inUse()).isZero();
    }

    @Test
    @DisplayName("G-03: NotFound / Permanent do not count towards the breaker")
    void permanentFailuresDoNotTripBreaker() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.loader.setDefault(Step.notFound());
            for (int i = 0; i < 10; i++) {
                h.send("job-1", "firm-" + i, "eng-" + i);
            }
            h.executor(0).pollOnce();

            assertThat(h.executor(0).breakerState()).isEqualTo(State.CLOSED);
            assertThat(h.queue.deadLetters()).hasSize(10);
        }
    }
}
