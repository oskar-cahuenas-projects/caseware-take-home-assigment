package com.caseware.fanout.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.Lane;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.port.Delivery;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.FakeEngagementLoader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Story 13, deterministic mode: lane polling through the executor and per-lane routing (G-13). */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class LaneQueuesTest {

    /** One message per receive, so each receive slot is one loader call. */
    private static final ExecutorConfig CONFIG = ExecutorConfig.production().toBuilder().maxReceiveBatch(1).build();

    private static void fill(ExecutorHarness h, Lane lane, int n) {
        for (int i = 0; i < n; i++) {
            h.send(lane, "job-1", "firm-" + lane + "-" + i, lane + "-" + i);
        }
    }

    private static Map<String, Long> lanesOfCalls(ExecutorHarness h) {
        return h.loader.startOrder().stream()
                .map(FakeEngagementLoader.Call::engagementId)
                .map(e -> e.substring(0, e.indexOf('-')))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    @Test
    @DisplayName("AC1: with all lanes non-empty, 10 receive slots load publish : backfill : reconcile = 6:3:1")
    void weightedWhenAllLanesBusy() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            for (Lane lane : Lane.values()) {
                fill(h, lane, 20);
            }
            for (int i = 0; i < 10; i++) {
                assertThat(h.executor(0).pollOnce()).isEqualTo(1);
            }
            assertThat(lanesOfCalls(h)).containsExactlyInAnyOrderEntriesOf(
                    Map.of("PUBLISH", 6L, "BACKFILL", 3L, "RECONCILE", 1L));
        }
    }

    @Test
    @DisplayName("AC2 / EC-14: an empty lane's slots go to the next non-empty lanes; no receive comes back empty")
    void workConserving() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            fill(h, Lane.PUBLISH, 20);
            fill(h, Lane.RECONCILE, 20);
            for (int i = 0; i < 10; i++) {
                assertThat(h.executor(0).pollOnce()).as("receive %d", i).isEqualTo(1);
            }
            // Backfill's three slots fall to the next lane in the cycle (publish), so publish gets 9.
            assertThat(lanesOfCalls(h)).containsExactlyInAnyOrderEntriesOf(Map.of("PUBLISH", 9L, "RECONCILE", 1L));
        }
    }

    @Test
    @DisplayName("AC2: only one lane has work → every receive drains that lane")
    void singleBusyLane() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            fill(h, Lane.RECONCILE, 10);
            for (int i = 0; i < 10; i++) {
                assertThat(h.executor(0).pollOnce()).isEqualTo(1);
            }
            assertThat(h.reconcile.depth()).isZero();
            assertThat(h.executor(0).pollOnce()).isZero();                         // all lanes empty
        }
    }

    @Test
    @DisplayName("ack, extendVisibility, requeueWithDelay and deadLetter act on the delivery's own lane")
    void routesByLane() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.send(Lane.BACKFILL, "job-1", "firm-1", "eng-b");
            h.send(Lane.RECONCILE, "job-1", "firm-1", "eng-r");
            h.send(Lane.RECONCILE, "job-1", "firm-1", "eng-r2");

            Delivery<LoadRequest> b = single(h.lanes.receive(1, Duration.ZERO));
            assertThat(b.body().lane()).isEqualTo(Lane.BACKFILL);                  // slot 0 is publish (empty) → backfill
            h.lanes.extendVisibility(b, Duration.ofMinutes(10));
            h.mutableClock().advance(CONFIG.visibilityTimeout());
            assertThat(h.backfill.receive(10, Duration.ZERO)).isEmpty();           // still invisible: extended on backfill
            h.lanes.requeueWithDelay(b, Duration.ofMinutes(1));
            assertThat(h.backfill.depth()).isEqualTo(1);
            assertThat(h.queue.depth()).isZero();

            Delivery<LoadRequest> r = single(h.lanes.receive(1, Duration.ZERO));
            h.lanes.deadLetter(r, "test");
            assertThat(h.reconcile.deadLetters()).hasSize(1);
            Delivery<LoadRequest> r2 = single(h.lanes.receive(1, Duration.ZERO));
            h.lanes.ack(r2);
            assertThat(h.reconcile.depth()).isZero();
            assertThat(h.queue.deadLetters()).isEmpty();
        }
    }

    private static Delivery<LoadRequest> single(List<Delivery<LoadRequest>> got) {
        assertThat(got).hasSize(1);
        return got.get(0);
    }
}
