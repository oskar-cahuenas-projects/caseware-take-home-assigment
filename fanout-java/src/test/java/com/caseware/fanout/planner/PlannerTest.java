package com.caseware.fanout.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.Cursor;
import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.Lane;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.domain.ProjectionStatus;
import com.caseware.fanout.domain.TemplateVersionEvent;
import com.caseware.fanout.domain.TemplateVersionEvent.Kind;
import com.caseware.fanout.executor.CrashHook;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.port.Delivery;
import com.caseware.fanout.port.MessageSink;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.SimulatedCrash;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Story 14, deterministic mode. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class PlannerTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();

    private static List<LoadRequest> drain(ExecutorHarness h) {
        List<LoadRequest> out = new ArrayList<>();
        List<Delivery<LoadRequest>> got;
        while (!(got = h.queue.receive(1_000, Duration.ZERO)).isEmpty()) {
            got.forEach(d -> {
                out.add(d.body());
                h.queue.ack(d);
            });
        }
        return out;
    }

    @Test
    @DisplayName("AC3: known rows → ApplyHeadBatch of ≤ 100 IDs; UNKNOWN and UNVERIFIED → PUBLISH-lane LoadRequests; counters match")
    void splitsKnownAndUnknown() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.addCandidates(250, ProjectionStatus.KNOWN);
            h.addCandidates(30, ProjectionStatus.UNKNOWN);
            h.addCandidates(20, ProjectionStatus.UNVERIFIED);
            TemplateVersionEvent e = h.templateEvent(Kind.PUBLISHED, "v7", 4);

            assertThat(h.planner("planner-a", 1_000).handle(e)).isEqualTo(Planner.Result.PLANNED);

            assertThat(h.commands).extracting(b -> b.engagementIds().size()).containsExactly(100, 100, 50);
            assertThat(h.commands).allSatisfy(b -> {
                assertThat(b.jobId()).isEqualTo(e.jobId());
                assertThat(b.graphRev()).isEqualTo(4);
                assertThat(b.correlationId()).isEqualTo(e.eventId());
                assertThat(b.region()).isEqualTo(ExecutorHarness.REGION);
            });
            List<LoadRequest> loads = drain(h);
            assertThat(loads).hasSize(50).allSatisfy(r -> {
                assertThat(r.lane()).isEqualTo(Lane.PUBLISH);
                assertThat(r.jobId()).isEqualTo(e.jobId());
                assertThat(r.requestId()).isEqualTo(e.jobId() + ":" + r.engagementId());   // deterministic
                assertThat(r.correlationId()).isEqualTo(e.eventId());
                assertThat(r.templateId()).isEqualTo("tpl-1");
            });
            FanoutJob job = h.jobs.find(e.jobId()).orElseThrow();
            assertThat(job.loadsExpected()).isEqualTo(50);
            assertThat(job.flagBatchesSent()).isEqualTo(3);
            assertThat(job.status()).isEqualTo(JobStatus.PLANNED);
            assertThat(job.cursor()).contains(Cursor.END);
            assertThat(job.plannerLease()).isEmpty();
        }
    }

    @Test
    @DisplayName("AC3: the cursor is saved after each page's sends, so a failing send leaves it on the page")
    void cursorSavedAfterSends() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.addCandidates(10, ProjectionStatus.UNKNOWN);
            TemplateVersionEvent e = h.templateEvent(Kind.PUBLISHED, "v7", 1);
            AtomicInteger sent = new AtomicInteger();
            MessageSink<LoadRequest> failsOnSeventh = r -> {
                if (sent.incrementAndGet() == 7) {
                    throw new IllegalStateException("send failed");
                }
                h.queue.send(r);
            };

            assertThatThrownBy(() -> h.planner("planner-a", 5, failsOnSeventh).handle(e))
                    .isInstanceOf(IllegalStateException.class);

            FanoutJob job = h.jobs.find(e.jobId()).orElseThrow();
            assertThat(job.cursor()).hasValueSatisfying(c -> assertThat(c).isNotEqualTo(Cursor.END));
            assertThat(job.loadsExpected()).isEqualTo(5);                           // page 1 only
            assertThat(job.status()).isEqualTo(JobStatus.PLANNING);
        }
    }

    @Test
    @DisplayName("AC2 / §D row 3: two planners on one job → only the lease holder proceeds")
    void oneLeaseHolder() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.addCandidates(10, ProjectionStatus.UNKNOWN);
            TemplateVersionEvent e = h.templateEvent(Kind.PUBLISHED, "v7", 1);
            AtomicReference<Planner.Result> second = new AtomicReference<>();
            Planner b = h.planner("planner-b", 5);
            AtomicInteger sends = new AtomicInteger();
            MessageSink<LoadRequest> racing = r -> {
                if (sends.incrementAndGet() == 1) {
                    second.set(b.handle(e));                                         // B arrives mid-page
                }
                h.queue.send(r);
            };

            assertThat(h.planner("planner-a", 5, racing).handle(e)).isEqualTo(Planner.Result.PLANNED);

            assertThat(second.get()).isEqualTo(Planner.Result.LEASE_HELD);
            assertThat(drain(h)).hasSize(10);
            assertThat(h.jobs.find(e.jobId()).orElseThrow().loadsExpected()).isEqualTo(10);
        }
    }

    @Test
    @DisplayName("AC4 / §D row 2: a planner crash mid-page → a restart after lease expiry resumes from the cursor and re-sends at most one page")
    void crashResumesFromCursor() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.addCandidates(12, ProjectionStatus.UNKNOWN);
            TemplateVersionEvent e = h.templateEvent(Kind.PUBLISHED, "v7", 1);
            AtomicInteger sent = new AtomicInteger();
            MessageSink<LoadRequest> crashing = r -> {
                if (sent.incrementAndGet() == 8) {                                   // 3rd load of page 2
                    throw new SimulatedCrash(CrashHook.Point.AFTER_PUBLISH);
                }
                h.queue.send(r);
            };

            assertThatThrownBy(() -> h.planner("planner-a", 5, crashing).handle(e)).isInstanceOf(SimulatedCrash.class);
            assertThat(h.planner("planner-b", 5).handle(e)).isEqualTo(Planner.Result.LEASE_HELD);
            h.mutableClock().advance(ExecutorHarness.PLANNER_LEASE);
            assertThat(h.planner("planner-b", 5).handle(e)).isEqualTo(Planner.Result.PLANNED);

            List<LoadRequest> loads = drain(h);
            Set<String> distinct = new HashSet<>();
            loads.forEach(r -> distinct.add(r.engagementId()));
            assertThat(distinct).hasSize(12);
            assertThat(loads).hasSize(12 + 2);                                       // page 2's two pre-crash sends repeat
            assertThat(loads.size() - distinct.size()).isLessThanOrEqualTo(5);       // ≤ one page re-sent
            assertThat(h.jobs.find(e.jobId()).orElseThrow().loadsExpected()).isEqualTo(12);
        }
    }

    @Test
    @DisplayName("AC4: a crash after the last page's save and before markPlanned re-sends nothing on restart")
    void crashAfterLastSave() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.addCandidates(3, ProjectionStatus.UNKNOWN);
            TemplateVersionEvent e = h.templateEvent(Kind.PUBLISHED, "v7", 1);
            h.jobs.createIfAbsent(new FanoutJob(e.jobId(), JobStatus.PLANNING, Optional.empty(),
                    Optional.empty(), 1, 0, 0, 0, 0));
            h.jobs.tryAcquirePlannerLease(e.jobId(), "planner-a", h.clock.instant().plus(Duration.ofMinutes(1)));
            h.jobs.savePage(e.jobId(), "planner-a", Cursor.END, 0, 3);              // state left by the crash
            h.mutableClock().advance(Duration.ofMinutes(1));

            assertThat(h.planner("planner-b", 5).handle(e)).isEqualTo(Planner.Result.PLANNED);
            assertThat(h.queue.depth()).isZero();
            assertThat(h.jobs.find(e.jobId()).orElseThrow().loadsExpected()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("An empty candidate set plans an empty job that completes at once")
    void emptyJobCompletes() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            TemplateVersionEvent e = h.templateEvent(Kind.PUBLISHED, "v7", 1);
            assertThat(h.planner("planner-a", 5).handle(e)).isEqualTo(Planner.Result.PLANNED);
            assertThat(h.jobs.find(e.jobId()).orElseThrow().status()).isEqualTo(JobStatus.COMPLETE);
            assertThat(h.commands).isEmpty();
        }
    }

    @Test
    @DisplayName("PlannerConfig rejects a page size below 1")
    void configValidation() {
        assertThatThrownBy(() -> new PlannerConfig(0, Duration.ofMinutes(1), "r")).isInstanceOf(IllegalArgumentException.class);
    }
}
