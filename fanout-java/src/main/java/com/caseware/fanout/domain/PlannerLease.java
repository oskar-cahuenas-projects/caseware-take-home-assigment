package com.caseware.fanout.domain;

import java.time.Instant;
import java.util.Objects;

/** Conditional lease that lets exactly one planner work on a job (§D row 3). */
public record PlannerLease(String owner, Instant until) {
    public PlannerLease {
        Checks.nonBlank(owner, "owner");
        Objects.requireNonNull(until, "until");
    }
}
