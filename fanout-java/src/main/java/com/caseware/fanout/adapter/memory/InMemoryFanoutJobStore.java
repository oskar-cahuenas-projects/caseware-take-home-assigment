package com.caseware.fanout.adapter.memory;

import static java.lang.System.Logger.Level.WARNING;

import com.caseware.fanout.domain.Cursor;
import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.domain.PlannerLease;
import com.caseware.fanout.port.FanoutJobStore;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * DynamoDB-like job store. Every change to a job (per-item outcome plus counters, lease, page save) runs in
 * one {@link ConcurrentHashMap#compute}, modelling a conditional write or a transaction (§D row 16, G-25).
 * Lease expiry is evaluated against the injected clock. Thread-safe.
 */
public final class InMemoryFanoutJobStore implements FanoutJobStore {

    private static final System.Logger LOG = System.getLogger(InMemoryFanoutJobStore.class.getName());

    private final Clock clock;
    private final ConcurrentHashMap<String, JobItem> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> graphRevs = new ConcurrentHashMap<>();

    public InMemoryFanoutJobStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean createIfAbsent(FanoutJob job) {
        Objects.requireNonNull(job, "job");
        return jobs.putIfAbsent(job.jobId(), new JobItem(job, Map.of())) == null;
    }

    /**
     * First outcome per {@code (jobId, engagementId)} wins (G-07). {@code DONE} and {@code COALESCED} count
     * as done. A {@code PLANNED} job whose load side closes becomes {@code COMPLETE}. Unknown job: logged, ignored (EC-25).
     */
    @Override
    public void recordOutcome(String jobId, String engagementId, Outcome o) {
        Objects.requireNonNull(o, "outcome");
        JobItem after = jobs.computeIfPresent(jobId, (id, item) -> item.with(engagementId, o));
        if (after == null) {
            LOG.log(WARNING, "outcome for unknown job ignored: jobId={0} engagementId={1}", jobId, engagementId);
        }
    }

    @Override
    public Optional<FanoutJob> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId)).map(JobItem::job);
    }

    @Override
    public boolean tryAcquirePlannerLease(String jobId, String owner, Instant until) {
        boolean[] acquired = new boolean[1];
        jobs.computeIfPresent(jobId, (id, item) -> {
            FanoutJob j = item.job();
            boolean free = j.plannerLease().map(l -> l.owner().equals(owner) || expired(l)).orElse(true);
            if (j.status() != JobStatus.PLANNING || !free) {
                return item;
            }
            acquired[0] = true;
            return item.replace(copy(j, j.status(), Optional.of(new PlannerLease(owner, until)), j.cursor(),
                    j.flagBatchesSent(), j.loadsExpected()));
        });
        return acquired[0];
    }

    @Override
    public void savePage(String jobId, String owner, Cursor next, int flagBatchesSent, int loadsAdded) {
        Objects.requireNonNull(next, "next");
        update(jobId, j -> {
            requireLease(j, owner);
            return copy(j, j.status(), j.plannerLease(), Optional.of(next),
                    j.flagBatchesSent() + flagBatchesSent, j.loadsExpected() + loadsAdded);
        });
    }

    @Override
    public void markPlanned(String jobId, String owner) {
        update(jobId, j -> {
            requireLease(j, owner);
            FanoutJob planned = copy(j, JobStatus.PLANNED, Optional.empty(), j.cursor(), j.flagBatchesSent(),
                    j.loadsExpected());
            return planned.loadSideClosed() ? copy(planned, JobStatus.COMPLETE, Optional.empty(), j.cursor(),
                    j.flagBatchesSent(), j.loadsExpected()) : planned;
        });
    }

    @Override
    public void markSuperseded(String jobId) {
        update(jobId, j -> copy(j, JobStatus.SUPERSEDED, Optional.empty(), j.cursor(), j.flagBatchesSent(),
                j.loadsExpected()));
    }

    @Override
    public void advanceGraphRev(String productId, String branchId, long graphRev) {
        graphRevs.merge(productId + "/" + branchId, graphRev, Math::max);
    }

    @Override
    public OptionalLong latestGraphRev(String productId, String branchId) {
        Long rev = graphRevs.get(productId + "/" + branchId);
        return rev == null ? OptionalLong.empty() : OptionalLong.of(rev);
    }

    /** Recorded outcome of one item, for tests. */
    public Optional<Outcome> outcome(String jobId, String engagementId) {
        return Optional.ofNullable(jobs.get(jobId)).map(item -> item.outcomes().get(engagementId));
    }

    private void update(String jobId, UnaryOperator<FanoutJob> change) {
        if (jobs.computeIfPresent(jobId, (id, item) -> item.replace(change.apply(item.job()))) == null) {
            throw new IllegalStateException("unknown job: jobId=" + jobId);
        }
    }

    private void requireLease(FanoutJob j, String owner) {
        boolean held = j.status() == JobStatus.PLANNING
                && j.plannerLease().filter(l -> l.owner().equals(owner) && !expired(l)).isPresent();
        if (!held) {
            throw new IllegalStateException("planner lease not held: jobId=" + j.jobId() + " owner=" + owner);
        }
    }

    private boolean expired(PlannerLease lease) {
        return !lease.until().isAfter(clock.instant());
    }

    private static FanoutJob copy(FanoutJob j, JobStatus status, Optional<PlannerLease> lease, Optional<Cursor> cursor,
                                  int flagBatchesSent, int loadsExpected) {
        return new FanoutJob(j.jobId(), status, lease, cursor, j.graphRev(), flagBatchesSent, loadsExpected,
                j.loadsDone(), j.loadsDlq());
    }

    /** Immutable job state plus its per-item outcomes. */
    private record JobItem(FanoutJob job, Map<String, Outcome> outcomes) {

        JobItem replace(FanoutJob next) {
            return new JobItem(next, outcomes);
        }

        JobItem with(String engagementId, Outcome o) {
            if (outcomes.containsKey(engagementId)) {
                return this;
            }
            Map<String, Outcome> next = new HashMap<>(outcomes);
            next.put(engagementId, o);
            int done = job.loadsDone() + (o == Outcome.DLQ ? 0 : 1);
            int dlq = job.loadsDlq() + (o == Outcome.DLQ ? 1 : 0);
            FanoutJob counted = new FanoutJob(job.jobId(), job.status(), job.plannerLease(), job.cursor(),
                    job.graphRev(), job.flagBatchesSent(), job.loadsExpected(), done, dlq);
            FanoutJob updated = counted.status() == JobStatus.PLANNED && counted.loadSideClosed()
                    ? new FanoutJob(job.jobId(), JobStatus.COMPLETE, job.plannerLease(), job.cursor(),
                            job.graphRev(), job.flagBatchesSent(), job.loadsExpected(), done, dlq)
                    : counted;
            return new JobItem(updated, Map.copyOf(next));
        }
    }
}
