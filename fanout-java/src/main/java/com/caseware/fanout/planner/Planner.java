package com.caseware.fanout.planner;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;

import com.caseware.fanout.domain.ApplyHeadBatch;
import com.caseware.fanout.domain.Candidate;
import com.caseware.fanout.domain.Cursor;
import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.Lane;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.ProjectionStatus;
import com.caseware.fanout.domain.TemplateVersionEvent;
import com.caseware.fanout.port.CandidateSource;
import com.caseware.fanout.port.FanoutJobStore;
import com.caseware.fanout.port.MessageSink;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Thin Planner (Stage A, §C). Turns one {@link TemplateVersionEvent} into {@link ApplyHeadBatch} commands for
 * known rows (I3) and PUBLISH-lane {@link LoadRequest}s for {@code UNKNOWN}/{@code UNVERIFIED} rows (I4).
 * <ul>
 *   <li>One job per event: deterministic {@code jobId} + {@code createIfAbsent} (§D row 1).</li>
 *   <li>One planner per job: a conditional lease, renewed before every page (§D row 3).</li>
 *   <li>Cursor and counters saved after a page's sends, so a crash re-sends at most one page (§D row 2);
 *       duplicates are harmless downstream (request ID and job outcomes are per engagement).</li>
 *   <li>Before every page, a newer {@code graphRev} for the branch supersedes the job; loads already queued
 *       stay valid (§D row 9).</li>
 * </ul>
 * Each call runs on the caller's thread; thread-safe (no mutable state).
 */
public final class Planner {

    static final int SCHEMA_VERSION = 1;
    private static final System.Logger LOG = System.getLogger(Planner.class.getName());

    /** What {@link #handle} did; every result except an exception means the event can be acked. */
    public enum Result {
        /** Planning finished in this call. */
        PLANNED,
        /** The job had already left {@code PLANNING}: duplicate event. */
        DUPLICATE,
        /** Another planner holds the lease; it finishes the job, or a redelivery resumes after expiry. */
        LEASE_HELD,
        /** A newer {@code graphRev} exists for the branch; the job is {@code SUPERSEDED}. */
        SUPERSEDED
    }

    private final String owner;
    private final PlannerConfig config;
    private final CandidateSource candidates;
    private final FanoutJobStore jobs;
    private final MessageSink<ApplyHeadBatch> commands;
    private final MessageSink<LoadRequest> loads;
    private final Clock clock;

    public Planner(String owner, PlannerConfig config, CandidateSource candidates, FanoutJobStore jobs,
                   MessageSink<ApplyHeadBatch> commands, MessageSink<LoadRequest> loads, Clock clock) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.config = Objects.requireNonNull(config, "config");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.loads = Objects.requireNonNull(loads, "loads");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Plans the event's job, or resumes it from the saved cursor. A send failure propagates with the cursor
     * left on the failed page; the caller leaves the event un-acked so it is redelivered.
     */
    public Result handle(TemplateVersionEvent e) {
        String jobId = e.jobId();
        jobs.advanceGraphRev(e.productId(), e.branchId(), e.graphRev());
        jobs.createIfAbsent(new FanoutJob(jobId, JobStatus.PLANNING, Optional.empty(), Optional.empty(),
                e.graphRev(), 0, 0, 0, 0));
        FanoutJob job = jobs.find(jobId).orElseThrow();
        if (job.status() != JobStatus.PLANNING) {
            log(e, "duplicate event");
            return Result.DUPLICATE;
        }
        Cursor cursor = job.cursor().orElse(Cursor.START);
        while (true) {
            if (!jobs.tryAcquirePlannerLease(jobId, owner, clock.instant().plus(config.lease()))) {
                log(e, "lease held by another planner");
                return Result.LEASE_HELD;
            }
            if (jobs.latestGraphRev(e.productId(), e.branchId()).orElse(e.graphRev()) > e.graphRev()) {
                jobs.markSuperseded(jobId);
                LOG.log(INFO, "superseded: jobId={0} correlationId={1}", jobId, e.eventId());
                return Result.SUPERSEDED;
            }
            if (cursor.equals(Cursor.END)) {
                break;
            }
            Page<Candidate> page = candidates.find(e.templateId(), e.branchId(), cursor, config.pageSize());
            int batches = sendHeadBatches(e, page.items());
            int loadsSent = sendLoads(e, page.items());
            cursor = page.next().orElse(Cursor.END);
            jobs.savePage(jobId, owner, cursor, batches, loadsSent);
        }
        jobs.markPlanned(jobId, owner);
        log(e, "planned");
        return Result.PLANNED;
    }

    private int sendHeadBatches(TemplateVersionEvent e, List<Candidate> page) {
        List<String> known = page.stream()
                .filter(c -> c.status() == ProjectionStatus.KNOWN)
                .map(Candidate::engagementId)
                .toList();
        int batches = 0;
        for (int from = 0; from < known.size(); from += ApplyHeadBatch.MAX_IDS) {
            List<String> ids = new ArrayList<>(known.subList(from, Math.min(known.size(), from + ApplyHeadBatch.MAX_IDS)));
            commands.send(new ApplyHeadBatch(e.jobId(), e.templateId(), e.branchId(), e.graphRev(), ids,
                    e.eventId(), SCHEMA_VERSION, config.region()));
            batches++;
        }
        return batches;
    }

    private int sendLoads(TemplateVersionEvent e, List<Candidate> page) {
        int sent = 0;
        for (Candidate c : page) {
            if (c.status() == ProjectionStatus.KNOWN) {
                continue;
            }
            loads.send(new LoadRequest(e.jobId() + ":" + c.engagementId(), e.jobId(), c.firmId(), c.engagementId(),
                    e.templateId(), Lane.PUBLISH, c.knownSeq(), clock.instant(), e.eventId(), 0, SCHEMA_VERSION,
                    config.region()));
            sent++;
        }
        return sent;
    }

    private void log(TemplateVersionEvent e, String what) {
        LOG.log(DEBUG, "{0}: owner={1} jobId={2} correlationId={3}", what, owner, e.jobId(), e.eventId());
    }
}
