package com.caseware.fanout.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.executor.LoadExecutor;
import com.caseware.fanout.testkit.Await;
import com.caseware.fanout.testkit.ExecutorHarness;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Contract §E 1, on real virtual threads with ms-scaled config (design §5, concurrency mode). */
@Tag("contract")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ContractE01CapacityInvariantTest {

    private static final int REQUESTS = 10_000;
    private static final int BIG_FIRM_REQUESTS = 1_000;
    private static final int SMALL_FIRM_REQUESTS = 50;

    private static String firmOf(int i) {
        return i < BIG_FIRM_REQUESTS ? "firm-big" : "firm-" + (i - BIG_FIRM_REQUESTS) / SMALL_FIRM_REQUESTS;
    }

    @Test
    @DisplayName("E1 capacity invariant: N=2, 10k requests, C=50, clustered firms → peak loader calls ≤ C, per firm ≤ N·cap, permits end at 0")
    void capacityInvariant() {
        ExecutorConfig config = ExecutorConfig.forTests();                       // C = 50, N = 2, cap 5
        try (var h = ExecutorHarness.realTime(config)) {
            h.createJob("job-1", REQUESTS);
            h.loader.setLatency(Duration.ofMillis(2));
            // Firms arrive in contiguous blocks (one big firm first), so the per-firm cap is the binding limit.
            for (int i = 0; i < REQUESTS; i++) {
                h.send("job-1", firmOf(i), "eng-" + i);
            }

            h.startAll();
            Await.until(() -> h.jobs.find("job-1").map(FanoutJob::status).orElseThrow() == JobStatus.COMPLETE,
                    Duration.ofSeconds(45), "job-1 COMPLETE");
            h.executors.forEach(LoadExecutor::close);

            assertThat(h.loader.peakInFlight()).isLessThanOrEqualTo(config.capacity());
            assertThat(h.loader.maxFirmPeak()).isLessThanOrEqualTo(config.firmCap() * config.tasks());
            assertThat(h.loader.totalCalls()).isEqualTo(REQUESTS);                // no engagement loaded twice
            assertThat(h.loader.inFlight()).isZero();
            assertThat(h.executors).allSatisfy(ex ->
                    assertThat(ex.limiter().freePermits()).isEqualTo(config.permitsPerTask()));
            FanoutJob job = h.jobs.find("job-1").orElseThrow();
            assertThat(job.loadsDone()).isEqualTo(REQUESTS);
            assertThat(job.loadsDlq()).isZero();
            assertThat(h.queue.depth()).isZero();
            assertThat(h.queue.deadLetters()).isEmpty();
        }
    }
}
