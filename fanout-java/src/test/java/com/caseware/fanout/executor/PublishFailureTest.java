package com.caseware.fanout.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryLoadLedger.Status;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.testkit.ExecutorHarness;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** G-22: a failed publish is not acked; the redelivery republishes from the outbox without reloading. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class PublishFailureTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();

    @Test
    @DisplayName("AC5: publish fails once → no ack; redelivery republishes with 0 extra loads")
    void republishWithoutReload() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 1);
            h.send("job-1", "firm-1", "eng-1");
            h.publisher.failNext(1);

            h.executor(0).pollOnce();
            assertThat(h.publisher.events()).isEmpty();
            assertThat(h.ledger.status("eng-1")).contains(Status.PENDING_PUBLISH);
            assertThat(h.jobs.outcome("job-1", "eng-1")).isEmpty();
            assertThat(h.queue.depth()).isEqualTo(1);                              // not acked

            h.mutableClock().advance(CONFIG.visibilityTimeout());
            h.executor(0).pollOnce();

            assertThat(h.loader.calls("eng-1")).isEqualTo(1);
            assertThat(h.publisher.observedEvents()).singleElement()
                    .satisfies(e -> assertThat(e.eventId()).isEqualTo("eng-1:1"));
            assertThat(h.ledger.status("eng-1")).contains(Status.DONE);
            assertThat(h.jobs.outcome("job-1", "eng-1")).contains(Outcome.DONE);
            assertThat(h.queue.depth()).isZero();
        }
    }

    @Test
    @DisplayName("G-22: publish keeps failing → queue dead-letters after maxReceiveCount; outbox stays PENDING_PUBLISH")
    void persistentPublishFailureDeadLetters() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 1);
            h.send("job-1", "firm-1", "eng-1");
            h.publisher.failNext(100);

            for (int i = 0; i <= CONFIG.maxReceiveCount(); i++) {
                h.executor(0).pollOnce();
                h.mutableClock().advance(CONFIG.visibilityTimeout());
            }

            assertThat(h.queue.deadLetters()).hasSize(1);
            assertThat(h.queue.depth()).isZero();
            assertThat(h.loader.calls("eng-1")).isEqualTo(1);
            assertThat(h.ledger.status("eng-1")).contains(Status.PENDING_PUBLISH);   // left for reconciliation (A9)
            assertThat(h.publisher.failedAttempts()).isEqualTo(CONFIG.maxReceiveCount());
        }
    }
}
