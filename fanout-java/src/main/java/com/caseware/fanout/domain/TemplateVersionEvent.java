package com.caseware.fanout.domain;

import static com.caseware.fanout.domain.Checks.nonBlank;
import static com.caseware.fanout.domain.Checks.nonNegative;
import static com.caseware.fanout.domain.Checks.positive;

import java.time.Instant;
import java.util.Objects;

/** Inbound publish or withdraw event (I1). Its {@code eventId} is the correlation ID of all derived work. */
public record TemplateVersionEvent(String eventId, int schemaVersion, Kind kind,
        String productId, String templateId, String branchId, String versionId,
        long branchSeq, long graphRev, Instant occurredAt) {

    public enum Kind { PUBLISHED, WITHDRAWN }

    public TemplateVersionEvent {
        nonBlank(eventId, "eventId");
        positive(schemaVersion, "schemaVersion");
        Objects.requireNonNull(kind, "kind");
        nonBlank(productId, "productId");
        nonBlank(templateId, "templateId");
        nonBlank(branchId, "branchId");
        nonBlank(versionId, "versionId");
        nonNegative(branchSeq, "branchSeq");
        nonNegative(graphRev, "graphRev");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** Deterministic job key: {@code productId:branchId:versionId:graphRev}, so a duplicate event maps to one job. */
    public String jobId() {
        return productId + ":" + branchId + ":" + versionId + ":" + graphRev;
    }
}
