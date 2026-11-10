package com.caseware.fanout.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.FakeEngagementLoader.Step;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** G-23: an unexpected RuntimeException (bug or poison payload) is never acked; the queue dead-letters it. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class PoisonMessageTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();

    @Test
    @DisplayName("AC3: unexpected RuntimeException → not acked; DLQ after maxReceiveCount receives; permits end at 0")
    void poisonReachesDlqAfterMaxReceives() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.createJob("job-1", 2);
            h.loader.script("eng-poison", Step.runtime(new IllegalStateException("poison")), Step.runtime(new IllegalStateException("poison")),
                    Step.runtime(new IllegalStateException("poison")), Step.runtime(new IllegalStateException("poison")),
                    Step.runtime(new IllegalStateException("poison")));
            h.send("job-1", "firm-1", "eng-poison");
            h.send("job-1", "firm-1", "eng-ok");

            for (int i = 0; i <= CONFIG.maxReceiveCount(); i++) {
                h.executor(0).pollOnce();
                assertThat(h.ledger.status("eng-poison")).isEmpty();               // claim released every time
                h.mutableClock().advance(CONFIG.visibilityTimeout());
            }

            assertThat(h.loader.calls("eng-poison")).isEqualTo(CONFIG.maxReceiveCount());
            assertThat(h.queue.deadLetters()).singleElement().satisfies(dl -> {
                assertThat(dl.body().engagementId()).isEqualTo("eng-poison");
                assertThat(dl.receiveCount()).isEqualTo(CONFIG.maxReceiveCount());
                assertThat(dl.reason()).contains("maxReceiveCount");
            });
            assertThat(h.queue.depth()).isZero();
            assertThat(h.executor(0).limiter().freePermits()).isEqualTo(CONFIG.permitsPerTask());
            assertThat(h.publisher.failedEvents()).isEmpty();                     // G-23 limitation: no event,
            assertThat(h.jobs.outcome("job-1", "eng-poison")).isEmpty();           // no outcome; job stays open
            assertThat(h.jobs.outcome("job-1", "eng-ok")).isPresent();             // other work unaffected
        }
    }
}
