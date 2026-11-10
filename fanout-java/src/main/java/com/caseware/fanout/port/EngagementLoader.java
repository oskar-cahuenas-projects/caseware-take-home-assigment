package com.caseware.fanout.port;

import com.caseware.fanout.domain.EngagementTemplateState;
import java.time.Duration;

/**
 * The other team's engagement loader (I5): read-only, ~60 s per call, capacity-bound,
 * no idempotency key. Calls must be made under a {@link Permit}.
 */
public interface EngagementLoader {
    EngagementTemplateState load(String firmId, String engagementId, Duration timeout) throws LoaderException;
}
