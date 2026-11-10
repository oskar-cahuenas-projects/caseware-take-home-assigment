package com.caseware.fanout.domain;

import static com.caseware.fanout.domain.Checks.nonBlank;
import static com.caseware.fanout.domain.Checks.positive;

import java.util.List;
import java.util.Objects;

/**
 * Observation event (I6). {@code seqKnown} mirrors {@code state.engagementSeq().isPresent()} (G-06).
 * IDs and versions only: no engagement content.
 */
public record EngagementStateObserved(String eventId, int schemaVersion, String region, String firmId,
        EngagementTemplateState state, List<String> jobIds, String correlationId, boolean seqKnown) {

    public EngagementStateObserved {
        nonBlank(eventId, "eventId");
        positive(schemaVersion, "schemaVersion");
        nonBlank(region, "region");
        nonBlank(firmId, "firmId");
        Objects.requireNonNull(state, "state");
        jobIds = Checks.ids(jobIds, "jobIds");
        if (jobIds.isEmpty()) {
            throw new IllegalArgumentException("jobIds must not be empty");
        }
        nonBlank(correlationId, "correlationId");
        if (seqKnown != state.engagementSeq().isPresent()) {
            throw new IllegalArgumentException("seqKnown must match state.engagementSeq presence");
        }
    }
}
