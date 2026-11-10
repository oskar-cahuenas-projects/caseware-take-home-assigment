package com.caseware.fanout.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryFanoutJobStore;
import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.domain.ProjectionStatus;
import com.caseware.fanout.domain.TemplateVersionEvent;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.planner.Planner;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.MutableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Contract §E 8. Outcome half (story 07) and duplicate-event half (story 14, Planner). */
@Tag("contract")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ContractE08JobIdempotencyTest {

    private static final Outcome[] OUTCOMES = {Outcome.DONE, Outcome.COALESCED, Outcome.DLQ};

    private final InMemoryFanoutJobStore store = new InMemoryFanoutJobStore(new MutableClock(Instant.parse("2026-11-09T10:00:00Z")));

    @Test
    @DisplayName("E8 job idempotency: duplicate outcomes leave the counters exact")
    void duplicateOutcomes() {
        store.createIfAbsent(new FanoutJob("job-1", JobStatus.PLANNED, Optional.empty(), Optional.empty(), 1, 0, 3, 0, 0));

        for (int round = 0; round < 3; round++) {
            store.recordOutcome("job-1", "e1", Outcome.DONE);
            store.recordOutcome("job-1", "e2", Outcome.COALESCED);
            store.recordOutcome("job-1", "e3", Outcome.DLQ);
        }

        FanoutJob j = store.find("job-1").orElseThrow();
        assertThat(j.loadsDone()).isEqualTo(2);
        assertThat(j.loadsDlq()).isEqualTo(1);
        assertThat(j.status()).isEqualTo(JobStatus.COMPLETE);
    }

    @Test
    @DisplayName("E8 job idempotency: 100 threads recording outcomes for the same items keep counters exact")
    void concurrentDuplicateOutcomes() throws InterruptedException {
        int items = 500;
        store.createIfAbsent(new FanoutJob("job-1", JobStatus.PLANNED, Optional.empty(), Optional.empty(), 1, 0, items, 0, 0));

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 100; t++) {
            int offset = t;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < items; i++) {
                    int item = (i + offset) % items;
                    store.recordOutcome("job-1", "e" + item, OUTCOMES[(item + offset) % OUTCOMES.length]);
                }
            }));
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }

        FanoutJob j = store.find("job-1").orElseThrow();
        assertThat(j.loadsDone() + j.loadsDlq()).isEqualTo(items);
        long dlq = java.util.stream.IntStream.range(0, items)
                .filter(i -> store.outcome("job-1", "e" + i).orElseThrow() == Outcome.DLQ).count();
        assertThat(j.loadsDlq()).isEqualTo((int) dlq);
        assertThat(j.status()).isEqualTo(JobStatus.COMPLETE);
    }

    @Test
    @DisplayName("E8 job idempotency: a duplicate publish event → one job and one planning pass, by the same or another planner")
    void duplicateEvents() {
        try (var h = ExecutorHarness.deterministic(ExecutorConfig.production())) {
            h.addCandidates(3, ProjectionStatus.KNOWN);
            h.addCandidates(2, ProjectionStatus.UNKNOWN);
            TemplateVersionEvent e = h.templateEvent(TemplateVersionEvent.Kind.PUBLISHED, "v7", 1);

            assertThat(h.planner("planner-a", 1_000).handle(e)).isEqualTo(Planner.Result.PLANNED);
            assertThat(h.planner("planner-a", 1_000).handle(e)).isEqualTo(Planner.Result.DUPLICATE);
            assertThat(h.planner("planner-b", 1_000).handle(e)).isEqualTo(Planner.Result.DUPLICATE);

            assertThat(h.commands).singleElement().satisfies(b -> assertThat(b.engagementIds()).hasSize(3));
            assertThat(h.queue.depth()).isEqualTo(2);
            FanoutJob job = h.jobs.find(e.jobId()).orElseThrow();
            assertThat(job.loadsExpected()).isEqualTo(2);
            assertThat(job.flagBatchesSent()).isEqualTo(1);
            assertThat(job.status()).isEqualTo(JobStatus.PLANNED);
        }
    }
}
