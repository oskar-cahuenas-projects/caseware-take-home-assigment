package com.caseware.fanout.domain;

import static com.caseware.fanout.domain.Checks.nonNegative;

import java.util.Objects;
import java.util.Optional;

/**
 * Job state (I7) with separate Stage A ({@code flagBatchesSent}) and Stage B (load) counters.
 * Done includes {@code COALESCED} outcomes (G-07).
 */
public record FanoutJob(String jobId, JobStatus status, Optional<PlannerLease> plannerLease,
        Optional<Cursor> cursor, long graphRev, int flagBatchesSent,
        int loadsExpected, int loadsDone, int loadsDlq) {

    public FanoutJob {
        Checks.nonBlank(jobId, "jobId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(plannerLease, "plannerLease");
        Objects.requireNonNull(cursor, "cursor");
        nonNegative(graphRev, "graphRev");
        nonNegative(flagBatchesSent, "flagBatchesSent");
        nonNegative(loadsExpected, "loadsExpected");
        nonNegative(loadsDone, "loadsDone");
        nonNegative(loadsDlq, "loadsDlq");
    }

    /** Invariant from §B / G-07: planning has finished and every expected load has an outcome. */
    public boolean loadSideClosed() {
        return status != JobStatus.PLANNING && loadsDone + loadsDlq == loadsExpected;
    }
}
