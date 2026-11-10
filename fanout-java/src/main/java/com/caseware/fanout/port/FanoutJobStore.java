package com.caseware.fanout.port;

import com.caseware.fanout.domain.Cursor;
import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.Outcome;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** Job state (I7) with per-item conditional transitions and counters. Implementations must be thread-safe. */
public interface FanoutJobStore {

    /** Inserts the job unless {@code jobId} exists. Returns {@code true} if inserted. */
    boolean createIfAbsent(FanoutJob job);

    /** Records the first outcome per {@code (jobId, engagementId)}; later ones are no-ops (G-07). */
    void recordOutcome(String jobId, String engagementId, Outcome o);

    /** Current job state, if any (G-25). */
    Optional<FanoutJob> find(String jobId);

    // ---- Planner (G-25, G-29) ----

    /**
     * Takes or renews the planner lease until {@code until}. Succeeds if the job is {@code PLANNING} and the
     * lease is free, expired, or already held by {@code owner}.
     */
    boolean tryAcquirePlannerLease(String jobId, String owner, Instant until);

    /**
     * After a page's sends: moves the cursor to {@code next} and adds {@code flagBatchesSent} and
     * {@code loadsAdded} to the counters, atomically. Throws {@link IllegalStateException} unless
     * {@code owner} holds an unexpired lease.
     */
    void savePage(String jobId, String owner, Cursor next, int flagBatchesSent, int loadsAdded);

    /** Ends planning: {@code PLANNED}, or {@code COMPLETE} if every expected load has an outcome; clears the lease. Lease holder only. */
    void markPlanned(String jobId, String owner);

    /** A newer {@code graphRev} exists: {@code SUPERSEDED} (terminal); clears the lease. Outcomes still count. */
    void markSuperseded(String jobId);

    /** Raises the branch's latest {@code graphRev} to at least {@code graphRev} (monotonic, G-29). */
    void advanceGraphRev(String productId, String branchId, long graphRev);

    OptionalLong latestGraphRev(String productId, String branchId);
}
