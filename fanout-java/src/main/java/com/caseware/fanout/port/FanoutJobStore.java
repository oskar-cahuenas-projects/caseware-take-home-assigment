package com.caseware.fanout.port;

import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.Outcome;
import java.util.Optional;

/** Job state (I7) with per-item conditional transitions and counters. Implementations must be thread-safe. */
public interface FanoutJobStore {

    /** Inserts the job unless {@code jobId} exists. Returns {@code true} if inserted. */
    boolean createIfAbsent(FanoutJob job);

    /** Records the first outcome per {@code (jobId, engagementId)}; later ones are no-ops (G-07). */
    void recordOutcome(String jobId, String engagementId, Outcome o);

    /** Current job state, if any (G-25). */
    Optional<FanoutJob> find(String jobId);
}
