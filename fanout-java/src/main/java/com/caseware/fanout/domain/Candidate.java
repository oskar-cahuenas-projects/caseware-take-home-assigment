package com.caseware.fanout.domain;

import static com.caseware.fanout.domain.Checks.nonBlank;
import static com.caseware.fanout.domain.Checks.nonNegative;

import java.util.Objects;

/** One projection row returned by {@code CandidateSource} (I2). */
public record Candidate(String firmId, String engagementId, ProjectionStatus status, long knownSeq) {
    public Candidate {
        nonBlank(firmId, "firmId");
        nonBlank(engagementId, "engagementId");
        Objects.requireNonNull(status, "status");
        nonNegative(knownSeq, "knownSeq");
    }
}
