package com.caseware.fanout.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryFanoutJobStore;
import com.caseware.fanout.adapter.memory.InMemoryLoadLedger;
import com.caseware.fanout.adapter.memory.InMemoryObservationPublisher;
import com.caseware.fanout.adapter.memory.InMemoryWorkQueue;
import com.caseware.fanout.adapter.memory.SemaphoreConcurrencyLimiter;
import com.caseware.fanout.domain.EngagementLoadFailed;
import com.caseware.fanout.domain.EngagementStateObserved;
import com.caseware.fanout.domain.EngagementTemplateState;
import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.port.Claim;
import com.caseware.fanout.port.ConcurrencyLimiter;
import com.caseware.fanout.port.Delivery;
import com.caseware.fanout.port.EngagementLoader;
import com.caseware.fanout.port.FanoutJobStore;
import com.caseware.fanout.port.LoadLedger;
import com.caseware.fanout.port.LoaderException;
import com.caseware.fanout.port.ObservationPublisher;
import com.caseware.fanout.port.Permit;
import com.caseware.fanout.port.WorkQueue;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.FakeEngagementLoader;
import com.caseware.fanout.testkit.FakeEngagementLoader.Step;
import com.caseware.fanout.testkit.ManualScheduler;
import com.caseware.fanout.testkit.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class LoadExecutorTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();

    @Test
    @DisplayName("AC1: Claimed + OK load → claim, permit, load, record, publish, markPublished, outcome, ack")
    void stepOrder() {
        List<String> calls = new CopyOnWriteArrayList<>();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
        var queue = new InMemoryWorkQueue<LoadRequest>("publish", clock, CONFIG.visibilityTimeout(), 5, CONFIG.maxRequeueDelay());
        var jobs = new InMemoryFanoutJobStore(clock);
        var ports = new ExecutorPorts(
                new RecordingPorts.RecordingQueue(queue, calls), new RecordingPorts.RecordingLedger(new InMemoryLoadLedger(clock), calls),
                new RecordingPorts.RecordingJobs(jobs, calls), new RecordingPorts.RecordingPublisher(new InMemoryObservationPublisher(), calls),
                new RecordingPorts.RecordingLoader(new FakeEngagementLoader(clock), calls), new ManualScheduler(clock), clock);
        var limiter = new RecordingPorts.RecordingLimiter(new SemaphoreConcurrencyLimiter(25, 5), calls);
        var executor = new LoadExecutor("w-1", ports, limiter, CONFIG, new Random(1), CrashHook.NONE);
        queue.send(new LoadRequest("req-1", "job-1", "firm-1", "eng-1", "tpl-1",
                com.caseware.fanout.domain.Lane.PUBLISH, 0, clock.instant(), "corr-1", 0, 1, "ca-central-1"));

        assertThat(executor.pollOnce()).isEqualTo(1);

        assertThat(calls).containsExactly("claim", "permit", "load", "permit.close", "recordObservation",
                "publish", "markPublished", "outcome:DONE", "ack");
        assertThat(queue.depth()).isZero();
        assertThat(limiter.delegate().inUse()).isZero();
    }

    @Test
    @DisplayName("G-24: with zero free permits the executor does not receive")
    @SuppressWarnings("try")
    void noFreePermitsNoReceive() {
        try (var h = ExecutorHarness.deterministic(CONFIG.toBuilder().capacity(2).tasks(2).build())) {
            h.send("job-1", "firm-1", "eng-1");
            try (Permit blocker = h.executor(0).limiter().tryAcquire("other").orElseThrow()) {
                assertThat(h.executor(0).pollOnce()).isZero();
            }
            assertThat(h.queue.receive(10, Duration.ZERO)).singleElement()
                    .satisfies(d -> assertThat(d.receiveCount()).isEqualTo(1));      // never received before
        }
    }

    @Test
    @DisplayName("AC4: no permit after a claim → claim released, message requeued with delay, not acked, no load")
    void noPermitDefers() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 1);
            h.send("job-1", "firm-1", "eng-1");
            ConcurrencyLimiter refusing = new ConcurrencyLimiter() {
                @Override
                public Optional<Permit> tryAcquire(String firmId) {
                    return Optional.empty();                                    // e.g. firm at its cap
                }

                @Override
                public int freePermits() {
                    return 1;
                }
            };
            new LoadExecutor("w-x", h.ports, refusing, h.config, new Random(1), CrashHook.NONE).pollOnce();

            assertThat(h.loader.totalCalls()).isZero();
            assertThat(h.ledger.status("eng-1")).isEmpty();                       // claim released (G-21)
            assertThat(h.queue.depth()).isEqualTo(1);                             // not acked, not lost
            assertThat(h.jobs.outcome("job-1", "eng-1")).isEmpty();
            assertThat(h.queue.receive(10, Duration.ZERO)).isEmpty();             // delayed
            h.mutableClock().advance(h.config.firmCapDeferral().multipliedBy(2));
            assertThat(h.queue.receive(10, Duration.ZERO)).singleElement()
                    .satisfies(d -> assertThat(d.receiveCount()).isEqualTo(1)); // new message, count reset
        }
    }

    @Test
    @DisplayName("AC5: A1 false → event has seqKnown=false and the fallback eventId")
    void a1False() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 1);
            h.loader.script("eng-1", Step.ok(OptionalLong.empty()));
            LoadRequest r = h.send("job-1", "firm-1", "eng-1");

            h.executor(0).pollOnce();

            assertThat(h.publisher.observedEvents()).singleElement().satisfies(e -> {
                assertThat(e.seqKnown()).isFalse();
                assertThat(e.eventId()).isEqualTo("eng-1:obs:" + r.requestId());
                assertThat(e.jobIds()).containsExactly("job-1");
                assertThat(e.correlationId()).isEqualTo("corr-job-1");
                assertThat(e.region()).isEqualTo(ExecutorHarness.REGION);
            });
            assertThat(h.jobs.outcome("job-1", "eng-1")).contains(Outcome.DONE);
        }
    }

    @Test
    @DisplayName("AC5: with a sequence → eventId = engagementId:seq and seqKnown=true")
    void a1True() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 1);
            h.loader.script("eng-1", Step.ok(OptionalLong.of(17)));
            h.send("job-1", "firm-1", "eng-1");

            h.executor(0).pollOnce();

            assertThat(h.publisher.observedEvents()).singleElement().satisfies(e -> {
                assertThat(e.seqKnown()).isTrue();
                assertThat(e.eventId()).isEqualTo("eng-1:17");
            });
        }
    }

    @Test
    @DisplayName("AC6 / EC-17: an observation of a different template is still published; outcome DONE")
    void differentTemplateStillPublished() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 1);
            h.queue.send(h.request("job-1", "firm-1", "eng-1", "tpl-OTHER"));   // loader reports tpl-1

            h.executor(0).pollOnce();

            assertThat(h.publisher.observedEvents()).singleElement()
                    .satisfies(e -> assertThat(e.state().templateId()).isEqualTo("tpl-1"));
            assertThat(h.jobs.outcome("job-1", "eng-1")).contains(Outcome.DONE);
            assertThat(h.queue.depth()).isZero();
        }
    }
}
