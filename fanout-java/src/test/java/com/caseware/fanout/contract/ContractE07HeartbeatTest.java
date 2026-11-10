package com.caseware.fanout.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.ManualScheduler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Contract §E 7 (deterministic mode). */
@Tag("contract")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ContractE07HeartbeatTest {

    /** G-18: contract visibility (3 min) and heartbeat (60 s); hard timeout raised so a 9-min load can finish. */
    private static final ExecutorConfig CONFIG = ExecutorConfig.production().toBuilder()
            .loaderHardTimeout(Duration.ofMinutes(10))
            .lease(Duration.ofMinutes(11))
            .build();

    @Test
    @DisplayName("E7 heartbeat: a load running 3 × the visibility timeout is never processed twice")
    void longLoadIsNeverRedelivered() throws InterruptedException {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            ManualScheduler scheduler = (ManualScheduler) h.scheduler;
            h.createJob("job-1", 1);
            h.loader.holdCalls();
            h.send("job-1", "firm-1", "eng-1");

            Thread owner = Thread.ofVirtual().start(() -> h.executor(0).pollOnce());
            h.loader.awaitInFlight(1, Duration.ofSeconds(5));

            Duration loadTime = CONFIG.visibilityTimeout().multipliedBy(3);
            for (Duration t = Duration.ZERO; t.compareTo(loadTime) < 0; t = t.plus(CONFIG.heartbeatInterval())) {
                h.mutableClock().advance(CONFIG.heartbeatInterval());
                scheduler.runDue();
                assertThat(h.executor(1).pollOnce()).as("redelivered at +%s", t).isZero();
            }

            h.loader.releaseCalls();
            owner.join();

            assertThat(h.loader.calls("eng-1")).isEqualTo(1);
            assertThat(h.publisher.observedEvents()).hasSize(1);
            assertThat(h.jobs.outcome("job-1", "eng-1")).contains(Outcome.DONE);
            assertThat(h.queue.depth()).isZero();
            assertThat(h.queue.deadLetters()).isEmpty();
            assertThat(scheduler.pendingCount()).isZero();                         // heartbeat and timeout cancelled
        }
    }
}
