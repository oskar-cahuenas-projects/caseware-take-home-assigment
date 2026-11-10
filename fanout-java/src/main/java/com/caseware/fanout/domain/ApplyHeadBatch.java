package com.caseware.fanout.domain;

import static com.caseware.fanout.domain.Checks.nonBlank;
import static com.caseware.fanout.domain.Checks.nonNegative;
import static com.caseware.fanout.domain.Checks.positive;

import java.util.List;

/** Flag command for known rows (I3), at most {@value #MAX_IDS} engagement IDs. Envelope fields per G-26. */
public record ApplyHeadBatch(String jobId, String templateId, String branchId, long graphRev,
        List<String> engagementIds, String correlationId, int schemaVersion, String region) {

    public static final int MAX_IDS = 100;

    public ApplyHeadBatch {
        nonBlank(jobId, "jobId");
        nonBlank(templateId, "templateId");
        nonBlank(branchId, "branchId");
        nonNegative(graphRev, "graphRev");
        engagementIds = Checks.ids(engagementIds, "engagementIds");
        if (engagementIds.isEmpty() || engagementIds.size() > MAX_IDS) {
            throw new IllegalArgumentException("engagementIds size must be 1.." + MAX_IDS + ", was " + engagementIds.size());
        }
        nonBlank(correlationId, "correlationId");
        positive(schemaVersion, "schemaVersion");
        nonBlank(region, "region");
    }
}
