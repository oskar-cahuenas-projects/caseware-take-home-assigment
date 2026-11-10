package com.caseware.fanout.planner;

import com.caseware.fanout.domain.Checks;
import java.time.Duration;
import java.util.Objects;

/**
 * Planner settings. Production: {@code pageSize} 1,000 (§C). {@code lease} must outlast one page's sends;
 * it is renewed before every page. Immutable.
 *
 * @param region envelope field of every message the planner sends (G-26)
 */
public record PlannerConfig(int pageSize, Duration lease, String region) {

    public PlannerConfig {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
        Objects.requireNonNull(lease, "lease");
        if (!lease.isPositive()) {
            throw new IllegalArgumentException("lease must be > 0");
        }
        Checks.nonBlank(region, "region");
    }
}
