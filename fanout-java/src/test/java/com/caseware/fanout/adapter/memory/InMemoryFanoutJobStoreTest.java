package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.domain.Cursor;
import com.caseware.fanout.testkit.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class InMemoryFanoutJobStoreTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
    private final InMemoryFanoutJobStore store = new InMemoryFanoutJobStore(clock);

    static FanoutJob job(String id, JobStatus status, int expected) {
        return new FanoutJob(id, status, Optional.empty(), Optional.empty(), 1, 0, expected, 0, 0);
    }

    @Test
    @DisplayName("AC1: createIfAbsent twice → second is false and the stored job is unchanged")
    void createIfAbsentIsIdempotent() {
        assertThat(store.createIfAbsent(job("j", JobStatus.PLANNED, 3))).isTrue();
        assertThat(store.createIfAbsent(job("j", JobStatus.PLANNING, 99))).isFalse();
        assertThat(store.find("j")).hasValueSatisfying(j -> {
            assertThat(j.status()).isEqualTo(JobStatus.PLANNED);
            assertThat(j.loadsExpected()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("AC2: first outcome per (job, engagement) wins; COALESCED counts as done")
    void firstOutcomeWins() {
        store.createIfAbsent(job("j", JobStatus.PLANNED, 3));

        store.recordOutcome("j", "e1", Outcome.COALESCED);
        store.recordOutcome("j", "e1", Outcome.DLQ);
        store.recordOutcome("j", "e2", Outcome.DLQ);
        store.recordOutcome("j", "e2", Outcome.DONE);

        FanoutJob j = store.find("j").orElseThrow();
        assertThat(j.loadsDone()).isEqualTo(1);
        assertThat(j.loadsDlq()).isEqualTo(1);
        assertThat(store.outcome("j", "e1")).contains(Outcome.COALESCED);
        assertThat(store.outcome("j", "e2")).contains(Outcome.DLQ);
    }

    @Test
    @DisplayName("AC3: a PLANNED job becomes COMPLETE exactly when done + dlq = expected")
    void completesOnCloseRule() {
        store.createIfAbsent(job("j", JobStatus.PLANNED, 3));

        store.recordOutcome("j", "e1", Outcome.DONE);
        store.recordOutcome("j", "e2", Outcome.COALESCED);
        assertThat(store.find("j").orElseThrow().status()).isEqualTo(JobStatus.PLANNED);

        store.recordOutcome("j", "e3", Outcome.DLQ);
        FanoutJob j = store.find("j").orElseThrow();
        assertThat(j.status()).isEqualTo(JobStatus.COMPLETE);
        assertThat(j.loadSideClosed()).isTrue();
    }

    @Test
    @DisplayName("AC3: a PLANNING job does not complete even when counters match")
    void planningJobDoesNotComplete() {
        store.createIfAbsent(job("j", JobStatus.PLANNING, 1));
        store.recordOutcome("j", "e1", Outcome.DONE);
        assertThat(store.find("j").orElseThrow().status()).isEqualTo(JobStatus.PLANNING);
    }

    @Test
    @DisplayName("EC-25: an outcome for an unknown job is ignored")
    void unknownJobIgnored() {
        store.recordOutcome("nope", "e1", Outcome.DONE);
        assertThat(store.find("nope")).isEmpty();
        assertThat(store.outcome("nope", "e1")).isEmpty();
    }

    // ---- Planner additions (story 14, G-25, G-29) ----

    private Instant in(Duration d) {
        return clock.instant().plus(d);
    }

    @Test
    @DisplayName("G-25: the planner lease goes to one owner; the same owner may renew; another owner only after expiry")
    void plannerLease() {
        store.createIfAbsent(job("j", JobStatus.PLANNING, 0));
        assertThat(store.tryAcquirePlannerLease("j", "a", in(Duration.ofMinutes(2)))).isTrue();
        assertThat(store.tryAcquirePlannerLease("j", "b", in(Duration.ofMinutes(2)))).isFalse();
        assertThat(store.tryAcquirePlannerLease("j", "a", in(Duration.ofMinutes(5)))).isTrue();     // renew
        clock.advance(Duration.ofMinutes(5));
        assertThat(store.tryAcquirePlannerLease("j", "b", in(Duration.ofMinutes(2)))).isTrue();     // expired
        assertThat(store.find("j").orElseThrow().plannerLease()).hasValueSatisfying(l -> assertThat(l.owner()).isEqualTo("b"));
        assertThat(store.tryAcquirePlannerLease("missing", "a", in(Duration.ofMinutes(2)))).isFalse();
    }

    @Test
    @DisplayName("G-25: savePage adds counters and moves the cursor, only for the lease holder")
    void savePageConditionalOnLease() {
        store.createIfAbsent(job("j", JobStatus.PLANNING, 0));
        store.tryAcquirePlannerLease("j", "a", in(Duration.ofMinutes(2)));
        store.savePage("j", "a", new Cursor("2"), 1, 3);
        store.savePage("j", "a", Cursor.END, 2, 4);
        FanoutJob j = store.find("j").orElseThrow();
        assertThat(j.cursor()).contains(Cursor.END);
        assertThat(j.flagBatchesSent()).isEqualTo(3);
        assertThat(j.loadsExpected()).isEqualTo(7);

        assertThatThrownBy(() -> store.savePage("j", "b", Cursor.END, 1, 1)).isInstanceOf(IllegalStateException.class);
        clock.advance(Duration.ofMinutes(2));
        assertThatThrownBy(() -> store.savePage("j", "a", Cursor.END, 1, 1)).isInstanceOf(IllegalStateException.class);
        assertThat(store.find("j").orElseThrow().loadsExpected()).isEqualTo(7);
    }

    @Test
    @DisplayName("G-25: markPlanned closes straight to COMPLETE when every expected load already has an outcome")
    void markPlannedCompletesWhenClosed() {
        store.createIfAbsent(job("j", JobStatus.PLANNING, 0));
        store.tryAcquirePlannerLease("j", "a", in(Duration.ofMinutes(2)));
        store.savePage("j", "a", Cursor.END, 0, 2);
        store.recordOutcome("j", "e1", Outcome.DONE);
        store.recordOutcome("j", "e2", Outcome.DLQ);
        assertThat(store.find("j").orElseThrow().status()).isEqualTo(JobStatus.PLANNING);      // planning not finished

        store.markPlanned("j", "a");
        FanoutJob j = store.find("j").orElseThrow();
        assertThat(j.status()).isEqualTo(JobStatus.COMPLETE);
        assertThat(j.plannerLease()).isEmpty();
    }

    @Test
    @DisplayName("G-25: markPlanned leaves the job PLANNED while loads are outstanding; outcomes then complete it")
    void markPlannedThenOutcomes() {
        store.createIfAbsent(job("j", JobStatus.PLANNING, 0));
        store.tryAcquirePlannerLease("j", "a", in(Duration.ofMinutes(2)));
        store.savePage("j", "a", Cursor.END, 0, 1);
        store.markPlanned("j", "a");
        assertThat(store.find("j").orElseThrow().status()).isEqualTo(JobStatus.PLANNED);
        store.recordOutcome("j", "e1", Outcome.COALESCED);
        assertThat(store.find("j").orElseThrow().status()).isEqualTo(JobStatus.COMPLETE);
        assertThatThrownBy(() -> store.markPlanned("j", "b")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("G-25: markSuperseded is terminal; later outcomes still count but never complete the job")
    void markSuperseded() {
        store.createIfAbsent(job("j", JobStatus.PLANNING, 0));
        store.tryAcquirePlannerLease("j", "a", in(Duration.ofMinutes(2)));
        store.savePage("j", "a", new Cursor("1"), 0, 1);
        store.markSuperseded("j");
        store.recordOutcome("j", "e1", Outcome.DONE);
        FanoutJob j = store.find("j").orElseThrow();
        assertThat(j.status()).isEqualTo(JobStatus.SUPERSEDED);
        assertThat(j.loadsDone()).isEqualTo(1);
        assertThat(j.plannerLease()).isEmpty();
        assertThat(store.tryAcquirePlannerLease("j", "a", in(Duration.ofMinutes(2)))).isFalse();
    }

    @Test
    @DisplayName("G-29: latestGraphRev is the maximum ever advanced per (product, branch)")
    void graphRevIsMonotonic() {
        assertThat(store.latestGraphRev("p", "b")).isEmpty();
        store.advanceGraphRev("p", "b", 5);
        store.advanceGraphRev("p", "b", 3);
        store.advanceGraphRev("p", "other", 9);
        assertThat(store.latestGraphRev("p", "b")).hasValue(5);
    }
}
