package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.EngagementLoadFailed;
import com.caseware.fanout.domain.EngagementStateObserved;
import com.caseware.fanout.domain.FailureReason;
import com.caseware.fanout.domain.Fixtures;
import com.caseware.fanout.port.PublishFailedException;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class InMemoryObservationPublisherTest {

    private final InMemoryObservationPublisher publisher = new InMemoryObservationPublisher();

    private static EngagementStateObserved observed(long seq) {
        return new EngagementStateObserved("eng-1:" + seq, 1, "ca-central-1", "firm-1",
                Fixtures.state(OptionalLong.of(seq)), List.of("job-1"), "corr-1", true);
    }

    private static EngagementLoadFailed failed() {
        return new EngagementLoadFailed("eng-1:failed:req-1", "firm-1", "eng-1", FailureReason.NOT_FOUND,
                "job-1", "corr-1", 1, "ca-central-1");
    }

    @Test
    @DisplayName("records both event types in publish order")
    void recordsInOrder() {
        publisher.publish(observed(1));
        publisher.publish(failed());
        publisher.publish(observed(2));

        assertThat(publisher.events()).hasSize(3);
        assertThat(publisher.observedEvents()).extracting(EngagementStateObserved::eventId)
                .containsExactly("eng-1:1", "eng-1:2");
        assertThat(publisher.failedEvents()).hasSize(1);
    }

    @Test
    @DisplayName("AC4: failNext(n) → the next n publishes throw and are not recorded, then publishing resumes")
    void failNext() {
        publisher.failNext(2);

        assertThatThrownBy(() -> publisher.publish(observed(1))).isInstanceOf(PublishFailedException.class)
                .hasMessageContaining("eng-1:1");
        assertThatThrownBy(() -> publisher.publish(failed())).isInstanceOf(PublishFailedException.class);
        publisher.publish(observed(2));

        assertThat(publisher.events()).singleElement().isEqualTo(observed(2));
        assertThat(publisher.failedAttempts()).isEqualTo(2);
    }
}
