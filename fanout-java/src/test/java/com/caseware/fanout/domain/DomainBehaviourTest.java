package com.caseware.fanout.domain;

import static com.caseware.fanout.domain.Fixtures.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class DomainBehaviourTest {

    @Test
    @DisplayName("AC6: jobId is productId:branchId:versionId:graphRev")
    void jobIdIsDeterministic() {
        assertThat(Fixtures.publishEvent().jobId()).isEqualTo("prod-1:br-1:v7:4");
    }

    @Test
    @DisplayName("AC4: with a sequence, eventId = engagementId:seq")
    void eventIdWithSequence() {
        assertThat(ObservationIds.of(state(OptionalLong.of(42)), "req-1")).isEqualTo("eng-1:42");
    }

    @Test
    @DisplayName("AC4: A1 false, so engagementSeq is empty and eventId uses the G-06 fallback")
    void eventIdFallbackWhenA1False() {
        EngagementTemplateState s = state(OptionalLong.empty());

        assertThat(s.engagementSeq()).isEmpty();
        assertThat(ObservationIds.of(s, "req-9")).isEqualTo("eng-1:obs:req-9");

        var observed = new EngagementStateObserved(ObservationIds.of(s, "req-9"), 1, "ca-central-1",
                "firm-1", s, List.of("job-1"), "corr-1", false);
        assertThat(observed.seqKnown()).isFalse();
    }

    @Test
    @DisplayName("withNextAttempt increments attempt and keeps every other field")
    void withNextAttempt() {
        LoadRequest r = Fixtures.loadRequest();
        LoadRequest next = r.withNextAttempt();

        assertThat(next.attempt()).isEqualTo(1);
        assertThat(next).usingRecursiveComparison().ignoringFields("attempt").isEqualTo(r);
    }

    @Test
    @DisplayName("list components are defensively copied and unmodifiable")
    void listsAreImmutableCopies() {
        List<String> ids = new ArrayList<>(List.of("eng-1"));
        var batch = new ApplyHeadBatch("job-1", "tpl-1", "br-1", 4, ids, "corr-1", 1, "ca-central-1");
        ids.add("eng-2");

        assertThat(batch.engagementIds()).containsExactly("eng-1");
        assertThatThrownBy(() -> batch.engagementIds().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("G-07: load side closes only when not PLANNING and done + dlq = expected")
    void loadSideCloseRule() {
        assertThat(job(JobStatus.PLANNED, 3, 2, 1).loadSideClosed()).isTrue();
        assertThat(job(JobStatus.PLANNED, 3, 2, 0).loadSideClosed()).isFalse();
        assertThat(job(JobStatus.PLANNING, 3, 2, 1).loadSideClosed()).isFalse();
        assertThat(job(JobStatus.PLANNED, 0, 0, 0).loadSideClosed()).isTrue();
    }

    private static FanoutJob job(JobStatus status, int expected, int done, int dlq) {
        return new FanoutJob("job-1", status, Optional.empty(), Optional.empty(), 4, 0, expected, done, dlq);
    }
}
