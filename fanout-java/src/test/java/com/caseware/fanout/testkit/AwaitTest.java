package com.caseware.fanout.testkit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class AwaitTest {

    @Test
    @DisplayName("returns as soon as the condition holds")
    void returnsWhenConditionHolds() {
        AtomicBoolean flag = new AtomicBoolean();
        Thread.ofVirtual().start(() -> flag.set(true));
        assertThatCode(() -> Await.until(flag::get, Duration.ofSeconds(2), "flag set")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC5: fails with a clear message when the condition never holds")
    void failsWithClearMessage() {
        assertThatThrownBy(() -> Await.until(() -> false, Duration.ofMillis(50), "all items DONE"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("all items DONE")
                .hasMessageContaining("PT0.05S");
    }
}
