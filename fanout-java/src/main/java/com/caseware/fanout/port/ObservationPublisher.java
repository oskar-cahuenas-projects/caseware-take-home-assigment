package com.caseware.fanout.port;

import com.caseware.fanout.domain.EngagementLoadFailed;
import com.caseware.fanout.domain.EngagementStateObserved;

/**
 * Outbound observations (I6, G-01). At-least-once; consumers dedupe on {@code eventId}.
 * Both methods throw {@link PublishFailedException} on failure. Implementations must be thread-safe.
 */
public interface ObservationPublisher {
    void publish(EngagementStateObserved e);

    void publish(EngagementLoadFailed e);
}
