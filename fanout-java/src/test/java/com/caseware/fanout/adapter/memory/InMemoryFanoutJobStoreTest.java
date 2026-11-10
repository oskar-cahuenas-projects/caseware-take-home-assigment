package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.Outcome;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class InMemoryFanoutJobStoreTest {

    private final InMemoryFanoutJobStore store = new InMemoryFanoutJobStore();

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
}
