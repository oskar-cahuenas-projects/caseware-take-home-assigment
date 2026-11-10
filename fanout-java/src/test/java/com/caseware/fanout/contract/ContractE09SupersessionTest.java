package com.caseware.fanout.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.domain.ProjectionStatus;
import com.caseware.fanout.domain.TemplateVersionEvent;
import com.caseware.fanout.domain.TemplateVersionEvent.Kind;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.planner.Planner;
import com.caseware.fanout.port.MessageSink;
import com.caseware.fanout.testkit.ExecutorHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Contract §E 9 (deterministic mode): a withdraw at a higher {@code graphRev} supersedes the running job. */
@Tag("contract")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ContractE09SupersessionTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();
    private static final int PAGE = 5;

    @Test
    @DisplayName("E9 supersession: a withdraw at a higher graphRev stops the old planner at its next page and keeps its loads")
    void withdrawSupersedesAndKeepsLoads() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.addCandidates(15, ProjectionStatus.UNKNOWN);                          // 3 pages
            TemplateVersionEvent publish = h.templateEvent(Kind.PUBLISHED, "v7", 1);
            TemplateVersionEvent withdraw = h.templateEvent(Kind.WITHDRAWN, "v7", 2);
            AtomicReference<Planner.Result> withdrawResult = new AtomicReference<>();
            List<LoadRequest> oldJobSends = new ArrayList<>();
            MessageSink<LoadRequest> oldPlannerLoads = r -> {
                oldJobSends.add(r);
                h.queue.send(r);
                if (oldJobSends.size() == PAGE) {                                  // end of page 1: the withdraw lands
                    withdrawResult.set(h.planner("planner-b", PAGE).handle(withdraw));
                }
            };

            assertThat(h.planner("planner-a", PAGE, oldPlannerLoads).handle(publish))
                    .isEqualTo(Planner.Result.SUPERSEDED);

            assertThat(withdrawResult.get()).isEqualTo(Planner.Result.PLANNED);
            assertThat(oldJobSends).hasSize(PAGE);                                 // stopped before page 2
            FanoutJob old = h.jobs.find(publish.jobId()).orElseThrow();
            assertThat(old.status()).isEqualTo(JobStatus.SUPERSEDED);
            assertThat(old.loadsExpected()).isEqualTo(PAGE);
            assertThat(old.plannerLease()).isEmpty();

            while (h.executor(0).pollOnce() > 0) {
                // drain: old job's 5 loads and the new job's 15
            }
            assertThat(oldJobSends).allSatisfy(r ->
                    assertThat(h.jobs.outcome(publish.jobId(), r.engagementId())).isPresent());
            assertThat(h.jobs.find(publish.jobId()).orElseThrow().loadsDone()).isEqualTo(PAGE);
            assertThat(h.jobs.find(publish.jobId()).orElseThrow().status()).isEqualTo(JobStatus.SUPERSEDED);
            FanoutJob current = h.jobs.find(withdraw.jobId()).orElseThrow();
            assertThat(current.status()).isEqualTo(JobStatus.COMPLETE);
            assertThat(current.loadsExpected()).isEqualTo(15);
            assertThat(h.loader.totalCalls()).isEqualTo(15);                        // shared engagements coalesced
            assertThat(h.queue.deadLetters()).isEmpty();
        }
    }

    @Test
    @DisplayName("E9 supersession: a late event at a lower graphRev is superseded before it sends anything")
    void lateLowerGraphRev() {
        try (var h = ExecutorHarness.deterministic(CONFIG)) {
            h.addCandidates(3, ProjectionStatus.UNKNOWN);
            TemplateVersionEvent newer = h.templateEvent(Kind.WITHDRAWN, "v7", 2);
            TemplateVersionEvent older = h.templateEvent(Kind.PUBLISHED, "v7", 1);

            assertThat(h.planner("planner-a", PAGE).handle(newer)).isEqualTo(Planner.Result.PLANNED);
            int sent = h.queue.depth();
            assertThat(h.planner("planner-a", PAGE).handle(older)).isEqualTo(Planner.Result.SUPERSEDED);

            assertThat(h.queue.depth()).isEqualTo(sent);
            assertThat(h.jobs.find(older.jobId()).orElseThrow().status()).isEqualTo(JobStatus.SUPERSEDED);
            assertThat(h.jobs.outcome(older.jobId(), "eng-0")).isEmpty();
        }
    }
}
