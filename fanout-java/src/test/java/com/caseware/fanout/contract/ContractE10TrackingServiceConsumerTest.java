package com.caseware.fanout.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.EngagementStateObserved;
import com.caseware.fanout.domain.EngagementTemplateState;
import com.caseware.fanout.domain.FailureReason;
import com.caseware.fanout.executor.CrashHook.Point;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.testkit.CrashInjector;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.FakeEngagementLoader.Step;
import com.caseware.fanout.testkit.FakeTrackingServiceConsumer;
import com.caseware.fanout.testkit.FakeTrackingServiceConsumer.Effect;
import com.caseware.fanout.testkit.FakeTrackingServiceConsumer.Row;
import com.caseware.fanout.testkit.SimulatedCrash;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Contract §E 10: the Tracking Service's guarantees the executor relies on, checked first on hand-built
 * events (the rules) and then on events produced by real executor runs (the contract). Deterministic mode.
 */
@Tag("contract")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ContractE10TrackingServiceConsumerTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();
    private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

    private static EngagementStateObserved observed(String eventId, String templateId, OptionalLong seq) {
        var state = new EngagementTemplateState("eng-1", templateId, "br-1", "v2", List.of(), seq, T0);
        return new EngagementStateObserved(eventId, 1, ExecutorHarness.REGION, "firm-1", state, List.of("job-1"),
                "corr-1", seq.isPresent());
    }

    @Nested
    @DisplayName("Consumer rules (hand-built events)")
    class Rules {

        private final FakeTrackingServiceConsumer consumer = new FakeTrackingServiceConsumer();

        @Test
        @DisplayName("AC1 / §D row 8: an observation with a lower engagementSeq than stored is ignored (a later decline wins)")
        void lowerSeqIgnored() {
            consumer.seed("eng-1", Row.known("tpl-1", "br-1", "v-declined", 7));
            assertThat(consumer.apply(observed("eng-1:5", "tpl-1", OptionalLong.of(5)))).isEqualTo(Effect.STALE);
            assertThat(consumer.row("eng-1")).isEqualTo(Row.known("tpl-1", "br-1", "v-declined", 7));
        }

        @Test
        @DisplayName("AC2 / §D row 7: a duplicate eventId is a no-op")
        void duplicateEventIdNoOp() {
            assertThat(consumer.apply(observed("eng-1:5", "tpl-1", OptionalLong.of(5)))).isEqualTo(Effect.APPLIED);
            Row after = consumer.row("eng-1");
            consumer.seed("eng-1", Row.unknown());                                // even if the row changed since
            assertThat(consumer.apply(observed("eng-1:5", "tpl-1", OptionalLong.of(5)))).isEqualTo(Effect.DUPLICATE);
            assertThat(consumer.row("eng-1")).isEqualTo(Row.unknown());
            assertThat(after.seq()).hasValue(5);
        }

        @Test
        @DisplayName("AC3 / G-06: seqKnown=false fills UNKNOWN fields only and leaves the sequence alone")
        void seqUnknownFillsUnknownOnly() {
            consumer.seed("eng-1", new Row(Optional.of("tpl-old"), Optional.empty(), Optional.empty(),
                    OptionalLong.of(3), Optional.empty()));
            assertThat(consumer.apply(observed("eng-1:obs:req-1", "tpl-new", OptionalLong.empty())))
                    .isEqualTo(Effect.FILLED_UNKNOWN);
            assertThat(consumer.row("eng-1")).isEqualTo(new Row(Optional.of("tpl-old"), Optional.of("br-1"),
                    Optional.of("v2"), OptionalLong.of(3), Optional.empty()));
        }

        @Test
        @DisplayName("§D row 10: a higher-seq observation of a different template is applied (an observation is a fact)")
        void differentTemplateApplied() {
            consumer.seed("eng-1", Row.known("tpl-1", "br-1", "v1", 4));
            assertThat(consumer.apply(observed("eng-1:6", "tpl-other", OptionalLong.of(6)))).isEqualTo(Effect.APPLIED);
            assertThat(consumer.row("eng-1").templateId()).hasValue("tpl-other");
        }
    }

    @Nested
    @DisplayName("AC4: events from real executor runs")
    class FromExecutor {

        private final FakeTrackingServiceConsumer consumer = new FakeTrackingServiceConsumer();

        @Test
        @DisplayName("E10: the duplicate published after a crash before ack (§D row 7) is a no-op for the consumer")
        void crashDuplicateIsNoOp() {
            CrashInjector crash = new CrashInjector();
            try (var h = ExecutorHarness.deterministic(CONFIG, crash)) {
                h.loader.script("eng-1", Step.ok(OptionalLong.of(5)));
                h.send("job-1", "firm-1", "eng-1");
                crash.arm(Point.AFTER_PUBLISH);
                assertThatThrownBy(() -> h.executor(0).pollOnce()).isInstanceOf(SimulatedCrash.class);
                h.mutableClock().advance(CONFIG.lease());
                h.executor(1).pollOnce();

                List<EngagementStateObserved> events = h.publisher.observedEvents();
                assertThat(events).hasSize(2);
                assertThat(events.stream().map(consumer::apply))
                        .containsExactly(Effect.APPLIED, Effect.DUPLICATE);
                assertThat(consumer.row("eng-1").seq()).hasValue(5);
            }
        }

        @Test
        @DisplayName("E10: a later load that returns a lower engagementSeq (stale replica) is ignored by the consumer")
        void lowerSeqFromLaterLoadIgnored() {
            try (var h = ExecutorHarness.deterministic(CONFIG)) {
                h.loader.script("eng-1", Step.ok(OptionalLong.of(9)), Step.ok(OptionalLong.of(4)));
                h.send("job-1", "firm-1", "eng-1");
                h.executor(0).pollOnce();
                h.mutableClock().advance(CONFIG.freshnessWindow());               // stale, so the next one loads
                h.send("job-2", "firm-1", "eng-1");
                h.executor(0).pollOnce();

                List<EngagementStateObserved> events = h.publisher.observedEvents();
                assertThat(events).extracting(EngagementStateObserved::eventId).containsExactly("eng-1:9", "eng-1:4");
                assertThat(events.stream().map(consumer::apply)).containsExactly(Effect.APPLIED, Effect.STALE);
                assertThat(consumer.row("eng-1").seq()).hasValue(9);
            }
        }

        @Test
        @DisplayName("E10 / A1 false: the executor's seqKnown=false event fills UNKNOWN fields only")
        void a1FalseFillsUnknownOnly() {
            try (var h = ExecutorHarness.deterministic(CONFIG)) {
                consumer.seed("eng-1", new Row(Optional.of("tpl-user"), Optional.empty(), Optional.empty(),
                        OptionalLong.of(3), Optional.empty()));
                h.loader.script("eng-1", Step.ok(OptionalLong.empty()));
                h.send("job-1", "firm-1", "eng-1");
                h.executor(0).pollOnce();

                EngagementStateObserved e = h.publisher.observedEvents().get(0);
                assertThat(e.seqKnown()).isFalse();
                assertThat(consumer.apply(e)).isEqualTo(Effect.FILLED_UNKNOWN);
                Row row = consumer.row("eng-1");
                assertThat(row.templateId()).hasValue("tpl-user");                  // known field untouched
                assertThat(row.branchId()).hasValue("br-1");
                assertThat(row.curVersionId()).hasValue("v1");
                assertThat(row.seq()).hasValue(3);
            }
        }

        @Test
        @DisplayName("E10: an EngagementLoadFailed is applied once; its redelivery is a no-op")
        void loadFailedDeduped() {
            try (var h = ExecutorHarness.deterministic(CONFIG)) {
                h.loader.script("eng-1", Step.notFound());
                h.send("job-1", "firm-1", "eng-1");
                h.executor(0).pollOnce();

                var failed = h.publisher.failedEvents().get(0);
                assertThat(consumer.apply(failed)).isEqualTo(Effect.APPLIED);
                assertThat(consumer.apply(failed)).isEqualTo(Effect.DUPLICATE);
                assertThat(consumer.row("eng-1").loadFailed()).contains(FailureReason.NOT_FOUND);
            }
        }
    }
}
