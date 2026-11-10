package com.caseware.fanout.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.executor.LoadExecutor;
import com.caseware.fanout.testkit.Await;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.FakeEngagementLoader.Step;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Contract §E 4, on real virtual threads with ms-scaled config. */
@Tag("contract")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ContractE04ThrottlingNoDlqTest {

    private static final int ITEMS = 20;

    @Test
    @DisplayName("E4 throttling doesn't dead-letter: a sustained 429 period sends 0 messages to the DLQ; all items complete afterwards")
    void sustainedThrottlingNoDlq() {
        ExecutorConfig config = ExecutorConfig.forTests();
        try (var h = ExecutorHarness.realTime(config)) {
            h.createJob("job-1", ITEMS);
            h.loader.setLatency(Duration.ofMillis(1));
            h.loader.setDefault(Step.throttled(Duration.ofMillis(5)));
            for (int i = 0; i < ITEMS; i++) {
                h.send("job-1", "firm-" + (i % 5), "eng-" + i);
            }

            h.startAll();
            // Throttle until every item has been delivered and throttled more than maxReceiveCount times:
            // without requeue-as-new-message these items would all be in the DLQ by now.
            int beyondDlqThreshold = config.maxReceiveCount() + 1;
            Await.until(() -> IntStream.range(0, ITEMS).allMatch(i -> h.loader.calls("eng-" + i) >= beyondDlqThreshold),
                    Duration.ofSeconds(40), "every item throttled at least " + beyondDlqThreshold + " times");
            assertThat(h.queue.deadLetters()).isEmpty();
            h.loader.setDefault(Step.ok());

            Await.until(() -> h.jobs.find("job-1").map(FanoutJob::status).orElseThrow() == JobStatus.COMPLETE,
                    Duration.ofSeconds(40), "job-1 COMPLETE after throttling stops");
            h.executors.forEach(LoadExecutor::close);

            assertThat(h.queue.deadLetters()).isEmpty();
            assertThat(h.publisher.failedEvents()).isEmpty();
            FanoutJob job = h.jobs.find("job-1").orElseThrow();
            assertThat(job.loadsDone()).isEqualTo(ITEMS);
            assertThat(job.loadsDlq()).isZero();
            assertThat(h.queue.depth()).isZero();
            assertThat(h.executors).allSatisfy(ex ->
                    assertThat(ex.limiter().freePermits()).isEqualTo(config.permitsPerTask()));
        }
    }
}
