package com.caseware.fanout.domain;

import static com.caseware.fanout.domain.Fixtures.T0;
import static com.caseware.fanout.domain.Fixtures.state;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** AC1: compact constructors reject null or blank IDs and negative numbers. */
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class RecordValidationTest {

    static Stream<Arguments> invalidConstructions() {
        return Stream.of(
                bad("TemplateVersionEvent blank eventId", () -> new TemplateVersionEvent(" ", 1,
                        TemplateVersionEvent.Kind.PUBLISHED, "p", "t", "b", "v", 1, 1, T0)),
                bad("TemplateVersionEvent null kind", () -> new TemplateVersionEvent("e", 1,
                        null, "p", "t", "b", "v", 1, 1, T0)),
                bad("TemplateVersionEvent negative graphRev", () -> new TemplateVersionEvent("e", 1,
                        TemplateVersionEvent.Kind.PUBLISHED, "p", "t", "b", "v", 1, -1, T0)),
                bad("TemplateVersionEvent schemaVersion 0", () -> new TemplateVersionEvent("e", 0,
                        TemplateVersionEvent.Kind.PUBLISHED, "p", "t", "b", "v", 1, 1, T0)),
                bad("Candidate null engagementId", () -> new Candidate("f", null, ProjectionStatus.UNKNOWN, 0)),
                bad("Candidate negative knownSeq", () -> new Candidate("f", "e", ProjectionStatus.KNOWN, -1)),
                bad("ApplyHeadBatch empty ids", () -> new ApplyHeadBatch("j", "t", "b", 1, List.of(), "c", 1, "r")),
                bad("ApplyHeadBatch > 100 ids", () -> new ApplyHeadBatch("j", "t", "b", 1,
                        Collections.nCopies(101, "e"), "c", 1, "r")),
                bad("ApplyHeadBatch blank id in list", () -> new ApplyHeadBatch("j", "t", "b", 1,
                        List.of("e", ""), "c", 1, "r")),
                bad("ApplyHeadBatch blank region", () -> new ApplyHeadBatch("j", "t", "b", 1,
                        List.of("e"), "c", 1, "")),
                bad("LoadRequest blank firmId", () -> new LoadRequest("r", "j", "", "e", "t", Lane.PUBLISH,
                        0, T0, "c", 0, 1, "reg")),
                bad("LoadRequest null lane", () -> new LoadRequest("r", "j", "f", "e", "t", null,
                        0, T0, "c", 0, 1, "reg")),
                bad("LoadRequest negative attempt", () -> new LoadRequest("r", "j", "f", "e", "t", Lane.PUBLISH,
                        0, T0, "c", -1, 1, "reg")),
                bad("LoadRequest negative knownSeq", () -> new LoadRequest("r", "j", "f", "e", "t", Lane.PUBLISH,
                        -1, T0, "c", 0, 1, "reg")),
                bad("EngagementTemplateState null seq", () -> new EngagementTemplateState("e", "t", "b", "v",
                        List.of(), null, T0)),
                bad("EngagementTemplateState negative seq", () -> new EngagementTemplateState("e", "t", "b", "v",
                        List.of(), OptionalLong.of(-1), T0)),
                bad("EngagementTemplateState null observedAt", () -> new EngagementTemplateState("e", "t", "b", "v",
                        List.of(), OptionalLong.empty(), null)),
                bad("EngagementStateObserved empty jobIds", () -> new EngagementStateObserved("eng-1:1", 1, "r",
                        "f", state(OptionalLong.of(1)), List.of(), "c", true)),
                bad("EngagementStateObserved seqKnown mismatch", () -> new EngagementStateObserved("eng-1:1", 1, "r",
                        "f", state(OptionalLong.of(1)), List.of("j"), "c", false)),
                bad("EngagementLoadFailed null reason", () -> new EngagementLoadFailed("x", "f", "e", null,
                        "j", "c", 1, "r")),
                bad("DecisionRef blank versionId", () -> new DecisionRef("", DecisionRef.Decision.APPLIED)),
                bad("Cursor blank token", () -> new Cursor(" ")),
                bad("Page null next", () -> new Page<String>(List.of(), null)),
                bad("PlannerLease blank owner", () -> new PlannerLease("", T0)),
                bad("FanoutJob negative loadsDone", () -> new FanoutJob("j", JobStatus.PLANNED, Optional.empty(),
                        Optional.empty(), 1, 0, 0, -1, 0)),
                bad("FanoutJob null status", () -> new FanoutJob("j", null, Optional.empty(),
                        Optional.empty(), 1, 0, 0, 0, 0)));
    }

    private static Arguments bad(String name, Executable construction) {
        return Arguments.of(name, construction);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConstructions")
    void rejectsInvalidInput(String name, Executable construction) {
        assertThatThrownBy(construction::execute)
                .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
    }
}
