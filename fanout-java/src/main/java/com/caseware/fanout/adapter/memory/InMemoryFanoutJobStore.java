package com.caseware.fanout.adapter.memory;

import static java.lang.System.Logger.Level.WARNING;

import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.port.FanoutJobStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DynamoDB-like job store. The per-item outcome and the job counters change in one
 * {@link ConcurrentHashMap#compute}, modelling a transaction of a conditional item write plus a counter
 * update (§D row 16). Thread-safe.
 */
public final class InMemoryFanoutJobStore implements FanoutJobStore {

    private static final System.Logger LOG = System.getLogger(InMemoryFanoutJobStore.class.getName());

    private final ConcurrentHashMap<String, JobItem> jobs = new ConcurrentHashMap<>();

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

    /** Recorded outcome of one item, for tests. */
    public Optional<Outcome> outcome(String jobId, String engagementId) {
        return Optional.ofNullable(jobs.get(jobId)).map(item -> item.outcomes().get(engagementId));
    }

    /** Immutable job state plus its per-item outcomes. */
    private record JobItem(FanoutJob job, Map<String, Outcome> outcomes) {

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
