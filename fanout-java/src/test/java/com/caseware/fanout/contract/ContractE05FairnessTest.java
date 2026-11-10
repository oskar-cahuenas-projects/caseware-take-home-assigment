package com.caseware.fanout.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.executor.LoadExecutor;
import com.caseware.fanout.testkit.Await;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.FakeEngagementLoader.Call;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Contract §E 5 (concurrency mode). The bound is on the rank of each small firm's first loader-call start
 * among all starts, never on wall-clock time (G-09).
 */
@Tag("contract")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ContractE05FairnessTest {

    private static final int BIG_FIRM_REQUESTS = 40_000;
    private static final int SMALL_FIRMS = 100;
    private static final int SMALL_FIRM_REQUESTS = 40;

    /**
     * Loader latency is the wave length. It must dwarf the time the executors need to defer the big firm's
     * backlog (as 60 s dwarfs it in production); 50 ms leaves an order of magnitude of headroom.
     */
    private static final Duration LATENCY = Duration.ofMillis(50);

    /** G-09: (⌈F · min(N·k, n) / S⌉ + 2) × C with S = C − N·k. */
    static int bound(ExecutorConfig c, int firms, int perFirm) {
        int bigShare = c.tasks() * c.firmCap();
        int smallSlots = c.capacity() - bigShare;
        int waves = (firms * Math.min(bigShare, perFirm) + smallSlots - 1) / smallSlots + 2;
        return waves * c.capacity();
    }

    @Test
    @DisplayName("E5 fairness: one 40k firm enqueued first + 100 firms of 40 → every small firm's first load starts within the first 1,350 starts")
    void smallFirmsStartWithinBound() {
        ExecutorConfig config = ExecutorConfig.forTests();                       // C = 50, N = 2, cap 5
        int bound = bound(config, SMALL_FIRMS, SMALL_FIRM_REQUESTS);
        assertThat(bound).isEqualTo(1_350);

        try (var h = ExecutorHarness.realTime(config)) {
            h.loader.setLatency(LATENCY);
            for (int i = 0; i < BIG_FIRM_REQUESTS; i++) {
                h.send("job-1", "firm-big", "eng-big-" + i);
            }
            for (int f = 0; f < SMALL_FIRMS; f++) {
                for (int i = 0; i < SMALL_FIRM_REQUESTS; i++) {
                    h.send("job-1", "firm-" + f, "eng-" + f + "-" + i);
                }
            }

            h.startAll();
            Await.until(() -> firstStartRanks(h.loader.startOrder()).size() == SMALL_FIRMS,
                    Duration.ofSeconds(45), "every small firm started a load");
            h.executors.forEach(LoadExecutor::close);

            Map<String, Integer> ranks = firstStartRanks(h.loader.startOrder());
            assertThat(ranks).hasSize(SMALL_FIRMS);
            assertThat(ranks.values()).allSatisfy(rank -> assertThat(rank).isLessThanOrEqualTo(bound));
            assertThat(h.loader.peakInFlight()).isLessThanOrEqualTo(config.capacity());
            assertThat(h.loader.peakInFlight("firm-big")).isLessThanOrEqualTo(config.tasks() * config.firmCap());
            assertThat(h.queue.deadLetters()).isEmpty();                          // deferrals never count as failures
        }
    }

    /** 1-based rank of each small firm's first call among all call starts. */
    private static Map<String, Integer> firstStartRanks(List<Call> starts) {
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < starts.size(); i++) {
            String firm = starts.get(i).firmId();
            if (!firm.equals("firm-big")) {
                ranks.putIfAbsent(firm, i + 1);
            }
        }
        return ranks;
    }
}
