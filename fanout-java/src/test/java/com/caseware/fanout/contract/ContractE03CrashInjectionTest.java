package com.caseware.fanout.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.EngagementStateObserved;
import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.executor.CrashHook.Point;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.testkit.CrashInjector;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.SimulatedCrash;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Contract §E 3 (G-11): executor 0 crashes at a point and is discarded; the clock passes lease and
 * visibility; executor 1 (a new workerId) takes over. Deterministic mode.
 */
@Tag("contract")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ContractE03CrashInjectionTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();

    @ParameterizedTest(name = "crash {0} → loads {1}, publishes {2}")
    @CsvSource({
            "AFTER_CLAIM,   1, 1",     // nothing durable beyond the lease: the new owner loads once
            "AFTER_LOAD,    2, 1",     // result lost with the process: one reload (≤ 1 extra)
            "AFTER_RECORD,  1, 1",     // outbox holds the result: PendingPublish, no reload
            "AFTER_PUBLISH, 1, 2"      // republished with the same eventId: harmless duplicate
    })
    @DisplayName("E3 crash injection: converges with ≤ 1 extra load and no lost item")
    void convergesAfterCrash(Point point, int expectedLoads, int expectedPublishes) {
        CrashInjector crash = new CrashInjector();
        try (var h = ExecutorHarness.deterministic(CONFIG, crash)) {
            h.createJob("job-1", 1);
            h.send("job-1", "firm-1", "eng-1");
            crash.arm(point);

            assertThatThrownBy(() -> h.executor(0).pollOnce()).isInstanceOf(SimulatedCrash.class);
            h.mutableClock().advance(CONFIG.lease());                           // ≥ visibility; lease expired

            assertThat(h.executor(1).pollOnce()).isEqualTo(1);

            assertThat(h.loader.calls("eng-1")).isEqualTo(expectedLoads).isLessThanOrEqualTo(2);
            assertThat(h.publisher.observedEvents()).hasSize(expectedPublishes)
                    .extracting(EngagementStateObserved::eventId).containsOnly("eng-1:1");
            FanoutJob job = h.jobs.find("job-1").orElseThrow();
            assertThat(job.loadsDone()).isEqualTo(1);
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETE);
            assertThat(h.jobs.outcome("job-1", "eng-1")).contains(Outcome.DONE);
            assertThat(h.queue.depth()).isZero();
            assertThat(h.queue.deadLetters()).isEmpty();
            assertThat(h.executor(1).limiter().freePermits()).isEqualTo(CONFIG.permitsPerTask());
        }
    }

    @ParameterizedTest
    @EnumSource(Point.class)
    @DisplayName("E3 crash injection: a crash mid-batch abandons the rest of the batch; all items still complete")
    void crashMidBatch(Point point) {
        int items = 10;
        CrashInjector crash = new CrashInjector();
        try (var h = ExecutorHarness.deterministic(CONFIG, crash)) {
            h.createJob("job-1", items);
            for (int i = 0; i < items; i++) {
                h.send("job-1", "firm-" + i, "eng-" + i);
            }
            crash.arm(point);

            assertThatThrownBy(() -> h.executor(0).pollOnce()).isInstanceOf(SimulatedCrash.class);
            h.mutableClock().advance(CONFIG.lease());
            while (h.executor(1).pollOnce() > 0) {
                // drain
            }

            FanoutJob job = h.jobs.find("job-1").orElseThrow();
            assertThat(job.loadsDone()).isEqualTo(items);
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETE);
            assertThat(h.loader.totalCalls()).isLessThanOrEqualTo(items + 1);
            assertThat(h.queue.depth()).isZero();
            assertThat(h.queue.deadLetters()).isEmpty();
        }
    }

    @Test
    @DisplayName("E3 crash injection: before the lease expires, the redelivered message is not loaded twice")
    void redeliveryBeforeLeaseExpiryDoesNotLoad() {
        CrashInjector crash = new CrashInjector();
        try (var h = ExecutorHarness.deterministic(CONFIG, crash)) {
            h.createJob("job-1", 1);
            h.send("job-1", "firm-1", "eng-1");
            crash.arm(Point.AFTER_CLAIM);
            assertThatThrownBy(() -> h.executor(0).pollOnce()).isInstanceOf(SimulatedCrash.class);

            h.mutableClock().advance(CONFIG.visibilityTimeout());               // visible again, lease still live
            assertThat(h.executor(1).pollOnce()).isEqualTo(1);                   // InFlightElsewhere → requeued
            assertThat(h.loader.totalCalls()).isZero();

            h.mutableClock().advance(CONFIG.lease().plus(CONFIG.firmCapDeferral()));
            assertThat(h.executor(1).pollOnce()).isEqualTo(1);
            assertThat(h.loader.totalCalls()).isEqualTo(1);
            assertThat(h.jobs.outcome("job-1", "eng-1")).contains(Outcome.DONE);
        }
    }
}
