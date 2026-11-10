package com.caseware.fanout.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.port.Claim;
import com.caseware.fanout.testkit.ExecutorHarness;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Contract §E 2 (deterministic mode: MutableClock, ManualScheduler, pollOnce). */
@Tag("contract")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ContractE02CoalescingTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();

    @Test
    @DisplayName("E2 coalescing: duplicate and cross-version requests within W → one loader call; every job gets an outcome")
    void duplicatesAndCrossVersion() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-v6", 1);
            h.createJob("job-v7", 1);
            h.send("job-v6", "firm-1", "eng-1");
            h.send("job-v6", "firm-1", "eng-1");                                // duplicate delivery
            h.send("job-v7", "firm-1", "eng-1");                                // cross-version

            h.executor(0).pollOnce();
            h.mutableClock().advance(Duration.ofHours(23));
            h.send("job-v7", "firm-1", "eng-1");                                // late duplicate, still within W
            h.executor(1).pollOnce();

            assertThat(h.loader.calls("eng-1")).isEqualTo(1);
            assertThat(h.publisher.observedEvents()).hasSize(1);
            assertThat(h.jobs.outcome("job-v6", "eng-1")).contains(Outcome.DONE);
            assertThat(h.jobs.outcome("job-v7", "eng-1")).contains(Outcome.COALESCED);
            assertThat(h.jobs.find("job-v6").orElseThrow().status()).isEqualTo(JobStatus.COMPLETE);
            assertThat(h.jobs.find("job-v7").orElseThrow().status()).isEqualTo(JobStatus.COMPLETE);
            assertThat(h.queue.depth()).isZero();
        }
    }

    @Test
    @DisplayName("E2 coalescing: a duplicate seen mid-load is requeued until the lease ends, then coalesces (G-05)")
    void duplicateMidLoad() throws InterruptedException {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-v6", 1);
            h.createJob("job-v7", 1);
            h.loader.holdCalls();
            h.send("job-v6", "firm-1", "eng-1");

            Thread owner = Thread.ofVirtual().start(() -> h.executor(0).pollOnce());
            h.loader.awaitInFlight(1, Duration.ofSeconds(5));

            h.send("job-v7", "firm-1", "eng-1");
            assertThat(h.executor(1).pollOnce()).isEqualTo(1);                   // InFlightElsewhere → requeued
            assertThat(h.queue.depth()).isEqualTo(2);
            assertThat(h.jobs.outcome("job-v7", "eng-1")).isEmpty();

            h.loader.releaseCalls();
            owner.join();
            assertThat(h.jobs.outcome("job-v6", "eng-1")).contains(Outcome.DONE);

            assertThat(h.executor(1).pollOnce()).isZero();                       // still delayed
            h.mutableClock().advance(CONFIG.lease().plus(CONFIG.firmCapDeferral()));
            assertThat(h.executor(1).pollOnce()).isEqualTo(1);

            assertThat(h.loader.calls("eng-1")).isEqualTo(1);
            assertThat(h.jobs.outcome("job-v7", "eng-1")).contains(Outcome.COALESCED);
            assertThat(h.jobs.find("job-v7").orElseThrow().status()).isEqualTo(JobStatus.COMPLETE);
            assertThat(h.queue.depth()).isZero();
        }
    }

    @Test
    @DisplayName("E2 coalescing: outside W the engagement is loaded again")
    void outsideWindowReloads() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-v6", 1);
            h.createJob("job-v7", 1);
            h.send("job-v6", "firm-1", "eng-1");
            h.executor(0).pollOnce();

            h.mutableClock().advance(CONFIG.freshnessWindow());
            h.send("job-v7", "firm-1", "eng-1");
            h.executor(0).pollOnce();

            assertThat(h.loader.calls("eng-1")).isEqualTo(2);
            assertThat(h.jobs.outcome("job-v7", "eng-1")).contains(Outcome.DONE);
        }
    }
}
