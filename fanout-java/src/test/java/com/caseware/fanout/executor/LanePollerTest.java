package com.caseware.fanout.executor;

import static com.caseware.fanout.domain.Lane.BACKFILL;
import static com.caseware.fanout.domain.Lane.PUBLISH;
import static com.caseware.fanout.domain.Lane.RECONCILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.Lane;
import com.caseware.fanout.executor.ExecutorConfig.LaneWeights;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Story 13: smooth weighted round-robin lane order (G-13). */
class LanePollerTest {

    private final LanePoller poller = new LanePoller(new LaneWeights(6, 3, 1));

    @Test
    @DisplayName("AC1: the 10-slot cycle is the smooth weighted round-robin of 6:3:1")
    void cycleIsSmoothSixThreeOne() {
        assertThat(poller.cycle()).containsExactly(
                PUBLISH, BACKFILL, PUBLISH, PUBLISH, BACKFILL, PUBLISH, RECONCILE, PUBLISH, BACKFILL, PUBLISH);
    }

    @Test
    @DisplayName("AC1: over 10 receives the first-choice lanes are 6:3:1, and the cycle repeats")
    void firstChoicesFollowWeights() {
        List<Lane> first = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            first.add(poller.nextOrder().get(0));
        }
        Map<Lane, Long> counts = first.subList(0, 10).stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(PUBLISH, 6L, BACKFILL, 3L, RECONCILE, 1L));
        assertThat(first.subList(10, 20)).isEqualTo(first.subList(0, 10));
    }

    @Test
    @DisplayName("AC2: every order lists all lanes once, the slot's lane first, then the next lanes in cycle order")
    void orderCoversAllLanesForWorkConservation() {
        assertThat(poller.nextOrder()).containsExactly(PUBLISH, BACKFILL, RECONCILE);   // slot 0: P, B, P, P, B, P, R
        assertThat(poller.nextOrder()).containsExactly(BACKFILL, PUBLISH, RECONCILE);   // slot 1
        for (int i = 2; i < 6; i++) {
            poller.nextOrder();
        }
        assertThat(poller.nextOrder()).containsExactly(RECONCILE, PUBLISH, BACKFILL);   // slot 6
    }

    @Test
    @DisplayName("Weights 1:1:1 give a plain round-robin")
    void equalWeights() {
        assertThat(new LanePoller(new LaneWeights(1, 1, 1)).cycle()).containsExactly(PUBLISH, BACKFILL, RECONCILE);
    }
}
