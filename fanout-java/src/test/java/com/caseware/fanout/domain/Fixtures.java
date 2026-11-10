package com.caseware.fanout.domain;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

/** Valid sample values for domain tests. */
public final class Fixtures {
    public static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

    private Fixtures() { }

    public static EngagementTemplateState state(OptionalLong seq) {
        return new EngagementTemplateState("eng-1", "tpl-1", "br-1", "v7",
                List.of(new DecisionRef("v6", DecisionRef.Decision.DECLINED)), seq, T0);
    }

    public static LoadRequest loadRequest() {
        return new LoadRequest("req-1", "job-1", "firm-1", "eng-1", "tpl-1", Lane.PUBLISH,
                3L, T0, "corr-1", 0, 1, "ca-central-1");
    }

    public static TemplateVersionEvent publishEvent() {
        return new TemplateVersionEvent("evt-1", 1, TemplateVersionEvent.Kind.PUBLISHED,
                "prod-1", "tpl-1", "br-1", "v7", 12L, 4L, T0);
    }
}
