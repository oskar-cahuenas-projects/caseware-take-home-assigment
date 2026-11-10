package com.caseware.fanout.domain;

import static com.caseware.fanout.domain.Checks.nonBlank;
import static com.caseware.fanout.domain.Checks.positive;

import java.util.Objects;

/** Load failure event (I6). Envelope fields per G-26. */
public record EngagementLoadFailed(String eventId, String firmId, String engagementId,
        FailureReason reason, String jobId, String correlationId, int schemaVersion, String region) {

    public EngagementLoadFailed {
        nonBlank(eventId, "eventId");
        nonBlank(firmId, "firmId");
        nonBlank(engagementId, "engagementId");
        Objects.requireNonNull(reason, "reason");
        nonBlank(jobId, "jobId");
        nonBlank(correlationId, "correlationId");
        positive(schemaVersion, "schemaVersion");
        nonBlank(region, "region");
    }
}
