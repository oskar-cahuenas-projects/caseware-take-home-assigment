package com.caseware.fanout.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.executor.CircuitBreaker.Admission;
import com.caseware.fanout.executor.CircuitBreaker.State;
import com.caseware.fanout.testkit.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class CircuitBreakerTest {

    private static final Duration COOLDOWN = Duration.ofSeconds(30);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
    private final CircuitBreaker breaker = new CircuitBreaker(5, COOLDOWN, clock);

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            breaker.onFailure();
        }
    }

    @Test
    @DisplayName("CLOSED admits what is asked; a success resets the consecutive-failure count")
    void closedAndReset() {
        assertThat(breaker.admit(10)).isEqualTo(new Admission(10, false));
        fail(4);
        breaker.onSuccess();
        fail(4);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    @DisplayName("AC3: K consecutive failures open it; OPEN admits nothing until the cool-down elapses")
    void opensAfterK() {
        fail(5);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.admit(10).count()).isZero();
        clock.advance(COOLDOWN.minusMillis(1));
        assertThat(breaker.admit(10).count()).isZero();
    }

    @Test
    @DisplayName("AC4: after cool-down exactly one probe; success closes")
    void probeSuccessCloses() {
        fail(5);
        clock.advance(COOLDOWN);

        assertThat(breaker.admit(10)).isEqualTo(new Admission(1, true));
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.admit(10).count()).isZero();                         // probe in flight

        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.admit(10).count()).isEqualTo(10);
    }

    @Test
    @DisplayName("AC4: probe failure re-opens with a new cool-down")
    void probeFailureReopens() {
        fail(5);
        clock.advance(COOLDOWN);
        breaker.admit(10);

        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        clock.advance(COOLDOWN.minusMillis(1));
        assertThat(breaker.admit(10).count()).isZero();
        clock.advance(Duration.ofMillis(1));
        assertThat(breaker.admit(10)).isEqualTo(new Admission(1, true));
    }

    @Test
    @DisplayName("a probe that never reached the loader frees the probe slot")
    void probeAbandoned() {
        fail(5);
        clock.advance(COOLDOWN);
        breaker.admit(10);

        breaker.probeAbandoned();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.admit(10)).isEqualTo(new Admission(1, true));
    }
}
