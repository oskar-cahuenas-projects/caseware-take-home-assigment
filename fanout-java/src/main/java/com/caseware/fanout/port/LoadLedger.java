package com.caseware.fanout.port;

import com.caseware.fanout.domain.EngagementTemplateState;
import java.time.Duration;

/**
 * Per-engagement load ledger (I7), keyed by {@code engagementId} only. It doubles as the observation
 * outbox: {@code LEASED → PENDING_PUBLISH → DONE}. Every method is an atomic conditional write.
 */
public interface LoadLedger {

    /** Decides per the G-19 table, first match wins; see {@link Claim}. */
    Claim claim(String engagementId, String jobId, String workerId,
                Duration lease, Duration freshness, long knownSeq);

    /** Step 4: stores the observation and its {@code eventId}; entry becomes {@code PENDING_PUBLISH} (G-06). */
    void recordObservation(String engagementId, EngagementTemplateState s, String eventId);

    /** Step 6: entry becomes {@code DONE} if it still holds {@code eventId}. Idempotent. */
    void markPublished(String engagementId, String eventId);

    /** Drops a {@code LEASED} entry owned by {@code workerId} so a requeued copy can claim (G-21). Otherwise a no-op. */
    void release(String engagementId, String workerId);
}
