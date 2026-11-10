package com.caseware.fanout.domain;

/** Deterministic observation event IDs (contract §B, G-06). Stateless; thread-safe. */
public final class ObservationIds {
    private ObservationIds() { }

    /**
     * {@code engagementId:<seq>} when the loader returned a sequence (A1 holds), otherwise
     * {@code engagementId:obs:<requestId>}, so a republish of the same observation reuses the ID.
     */
    public static String of(EngagementTemplateState state, String requestId) {
        Checks.nonBlank(requestId, "requestId");
        return state.engagementSeq().isPresent()
                ? state.engagementId() + ":" + state.engagementSeq().getAsLong()
                : state.engagementId() + ":obs:" + requestId;
    }

    /** {@code engagementId:failed:<requestId>}; stable across retries because {@code requestId} is. */
    public static String failure(String engagementId, String requestId) {
        Checks.nonBlank(engagementId, "engagementId");
        Checks.nonBlank(requestId, "requestId");
        return engagementId + ":failed:" + requestId;
    }
}
