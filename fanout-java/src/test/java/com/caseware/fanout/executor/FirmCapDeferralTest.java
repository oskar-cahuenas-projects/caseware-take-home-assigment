package com.caseware.fanout.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.port.Delivery;
import com.caseware.fanout.port.Permit;
import com.caseware.fanout.testkit.ExecutorHarness;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Story 13, deterministic mode: per-firm cap deferral (§C, G-21, G-24). */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class FirmCapDeferralTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();      // C/N = 25, cap 5

    @Test
    @DisplayName("AC3 / EC-20: a firm at its cap → claim released, same request requeued after firmCapDeferral × [1, 2), count reset")
    void firmAtCapIsDeferred() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            List<Permit> held = new ArrayList<>();
            for (int i = 0; i < CONFIG.firmCap(); i++) {
                held.add(h.executor(0).limiter().tryAcquire("firm-big").orElseThrow());
            }
            h.createJob("job-1", 2);
            LoadRequest big = h.send("job-1", "firm-big", "eng-big");
            h.send("job-1", "firm-small", "eng-small");

            assertThat(h.executor(0).pollOnce()).isEqualTo(2);

            assertThat(h.loader.calls("eng-big")).isZero();
            assertThat(h.ledger.status("eng-big")).isEmpty();                         // claim released (G-21)
            assertThat(h.jobs.outcome("job-1", "eng-big")).isEmpty();
            assertThat(h.jobs.outcome("job-1", "eng-small")).contains(Outcome.DONE);  // the other firm is not blocked
            assertThat(h.queue.depth()).isEqualTo(1);

            h.mutableClock().advance(CONFIG.firmCapDeferral().minusMillis(1));
            assertThat(h.queue.receive(10, Duration.ZERO)).isEmpty();                  // short delay …
            h.mutableClock().advance(CONFIG.firmCapDeferral().plusMillis(1));
            List<Delivery<LoadRequest>> again = h.queue.receive(10, Duration.ZERO);   // … plus jitter < firmCapDeferral
            assertThat(again).singleElement().satisfies(d -> {
                assertThat(d.body()).isEqualTo(big);                                  // attempt unchanged
                assertThat(d.receiveCount()).isEqualTo(1);                            // new message
            });
            held.forEach(Permit::close);
        }
    }
}
