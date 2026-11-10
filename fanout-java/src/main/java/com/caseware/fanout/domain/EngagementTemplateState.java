package com.caseware.fanout.domain;

import static com.caseware.fanout.domain.Checks.nonBlank;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * What the loader saw (I5). {@code engagementSeq} is empty when assumption A1 does not hold (G-06);
 * the consumer may then fill {@code UNKNOWN} fields only.
 */
public record EngagementTemplateState(String engagementId, String templateId, String branchId,
        String curVersionId, List<DecisionRef> decisions, OptionalLong engagementSeq, Instant observedAt) {

    public EngagementTemplateState {
        nonBlank(engagementId, "engagementId");
        nonBlank(templateId, "templateId");
        nonBlank(branchId, "branchId");
        nonBlank(curVersionId, "curVersionId");
        decisions = List.copyOf(decisions);
        Objects.requireNonNull(engagementSeq, "engagementSeq");
        engagementSeq.ifPresent(seq -> Checks.nonNegative(seq, "engagementSeq"));
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
