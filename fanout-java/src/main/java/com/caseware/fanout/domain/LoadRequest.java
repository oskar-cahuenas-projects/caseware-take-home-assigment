package com.caseware.fanout.domain;

import static com.caseware.fanout.domain.Checks.nonBlank;
import static com.caseware.fanout.domain.Checks.nonNegative;
import static com.caseware.fanout.domain.Checks.positive;

import java.time.Instant;
import java.util.Objects;

/**
 * Work item (I4). {@code attempt} counts {@code Transient} retries and survives requeue (G-16);
 * {@code schemaVersion} and {@code region} complete the envelope (G-26).
 */
public record LoadRequest(String requestId, String jobId, String firmId, String engagementId,
        String templateId, Lane lane, long knownSeq, Instant enqueuedAt, String correlationId,
        int attempt, int schemaVersion, String region) {

    public LoadRequest {
        nonBlank(requestId, "requestId");
        nonBlank(jobId, "jobId");
        nonBlank(firmId, "firmId");
        nonBlank(engagementId, "engagementId");
        nonBlank(templateId, "templateId");
        Objects.requireNonNull(lane, "lane");
        nonNegative(knownSeq, "knownSeq");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt");
        nonBlank(correlationId, "correlationId");
        nonNegative(attempt, "attempt");
        positive(schemaVersion, "schemaVersion");
        nonBlank(region, "region");
    }

    /** Same request for the next retry after a {@code Transient} failure. */
    public LoadRequest withNextAttempt() {
        return new LoadRequest(requestId, jobId, firmId, engagementId, templateId, lane, knownSeq,
                enqueuedAt, correlationId, attempt + 1, schemaVersion, region);
    }
}
