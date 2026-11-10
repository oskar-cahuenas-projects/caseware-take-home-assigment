package com.caseware.fanout.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.FailureReason;
import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.FakeEngagementLoader.Step;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Contract §E 6 (deterministic mode). */
@Tag("contract")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ContractE06PermanentErrorTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();

    @Test
    @DisplayName("E6 permanent error: the item goes to the DLQ immediately and emits LoadFailed")
    void deadLettersImmediately() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 1);
            h.loader.script("eng-1", Step.notFound());
            LoadRequest r = h.send("job-1", "firm-1", "eng-1");

            h.executor(0).pollOnce();

            assertThat(h.loader.calls("eng-1")).isEqualTo(1);
            assertThat(h.queue.deadLetters()).singleElement().satisfies(dl -> {
                assertThat(dl.receiveCount()).isEqualTo(1);                       // first attempt, no retries
                assertThat(dl.reason()).isEqualTo("NOT_FOUND");
            });
            assertThat(h.publisher.failedEvents()).singleElement().satisfies(e -> {
                assertThat(e.eventId()).isEqualTo("eng-1:failed:" + r.requestId());
                assertThat(e.reason()).isEqualTo(FailureReason.NOT_FOUND);
                assertThat(e.jobId()).isEqualTo("job-1");
                assertThat(e.firmId()).isEqualTo("firm-1");
                assertThat(e.region()).isEqualTo(ExecutorHarness.REGION);
                assertThat(e.correlationId()).isEqualTo(r.correlationId());
            });
            assertThat(h.publisher.observedEvents()).isEmpty();
            assertThat(h.jobs.outcome("job-1", "eng-1")).contains(Outcome.DLQ);
            assertThat(h.jobs.find("job-1").orElseThrow().status()).isEqualTo(JobStatus.COMPLETE);
            assertThat(h.ledger.status("eng-1")).isEmpty();
            assertThat(h.queue.depth()).isZero();
        }
    }

    @Test
    @DisplayName("E6 permanent error: a job of DONE, COALESCED and DLQ items closes exactly when done + dlq = expected")
    void mixedJobClosesOnRule() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-0", 1);
            h.send("job-0", "firm-1", "eng-shared");                               // makes eng-shared fresh
            h.executor(0).pollOnce();

            h.createJob("job-1", 4);
            h.loader.script("eng-missing", Step.notFound());
            h.loader.script("eng-broken", Step.permanent());
            h.send("job-1", "firm-1", "eng-ok");                                   // DONE
            h.send("job-1", "firm-1", "eng-shared");                               // COALESCED
            h.send("job-1", "firm-2", "eng-missing");                              // DLQ (NotFound)
            assertThat(h.executor(0).pollOnce()).isEqualTo(3);

            FanoutJob before = h.jobs.find("job-1").orElseThrow();
            assertThat(before.loadsDone()).isEqualTo(2);
            assertThat(before.loadsDlq()).isEqualTo(1);
            assertThat(before.status()).isEqualTo(JobStatus.PLANNED);              // 3 of 4: not closed

            h.send("job-1", "firm-2", "eng-broken");                               // DLQ (Permanent)
            h.executor(0).pollOnce();

            FanoutJob after = h.jobs.find("job-1").orElseThrow();
            assertThat(after.loadsDone()).isEqualTo(2);
            assertThat(after.loadsDlq()).isEqualTo(2);
            assertThat(after.status()).isEqualTo(JobStatus.COMPLETE);
            assertThat(h.jobs.outcome("job-1", "eng-shared")).contains(Outcome.COALESCED);
            assertThat(h.queue.deadLetters()).extracting(dl -> dl.reason()).containsExactly("NOT_FOUND", "PERMANENT");
        }
    }
}
