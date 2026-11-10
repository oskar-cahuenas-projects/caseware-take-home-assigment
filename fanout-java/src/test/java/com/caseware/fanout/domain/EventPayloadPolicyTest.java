package com.caseware.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * AC5 / NFR-06: messages carry the envelope plus IDs, versions, sequences, enums and timestamps only.
 * A new free-form or content-bearing component fails this test until it is reviewed.
 */
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class EventPayloadPolicyTest {

    private static final Set<Class<?>> ALLOWED_TYPES = Set.of(String.class, int.class, long.class,
            boolean.class, Instant.class, OptionalLong.class, List.class, EngagementTemplateState.class);

    /** String components that are not identifiers, versions or envelope fields must be listed here. */
    private static final Set<String> ALLOWED_NON_ID_STRINGS = Set.of("region", "correlationId");

    @ParameterizedTest
    @ValueSource(classes = {EngagementStateObserved.class, EngagementLoadFailed.class,
            LoadRequest.class, ApplyHeadBatch.class})
    void carriesEnvelope(Class<? extends Record> message) {
        assertThat(names(message)).contains("schemaVersion", "correlationId", "region");
    }

    @ParameterizedTest
    @ValueSource(classes = {EngagementStateObserved.class, EngagementLoadFailed.class, LoadRequest.class,
            ApplyHeadBatch.class, EngagementTemplateState.class, DecisionRef.class})
    void carriesIdsAndVersionsOnly(Class<? extends Record> message) {
        for (RecordComponent c : message.getRecordComponents()) {
            Class<?> type = c.getType();
            assertThat(type.isEnum() || ALLOWED_TYPES.contains(type))
                    .as("%s.%s has type %s", message.getSimpleName(), c.getName(), type.getSimpleName())
                    .isTrue();
            if (type == String.class) {
                assertThat(c.getName().endsWith("Id") || ALLOWED_NON_ID_STRINGS.contains(c.getName()))
                        .as("%s.%s must be an identifier or envelope field", message.getSimpleName(), c.getName())
                        .isTrue();
            }
        }
    }

    private static Set<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
    }
}
