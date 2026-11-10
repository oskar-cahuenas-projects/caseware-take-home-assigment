package com.caseware.fanout.domain;

import java.util.Objects;

/** A user decision on a template version, as reported by the loader (G-08). */
public record DecisionRef(String versionId, Decision decision) {

    public enum Decision { APPLIED, DECLINED }

    public DecisionRef {
        Checks.nonBlank(versionId, "versionId");
        Objects.requireNonNull(decision, "decision");
    }
}
