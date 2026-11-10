package com.caseware.fanout.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class BackoffTest {

    private static final ExecutorConfig CONFIG = ExecutorConfig.production();      // base 30 s, cap 15 min

    @Test
    @DisplayName("AC2: retry delay is full jitter over min(cap, base·2^attempt)")
    void fullJitterBounds() {
        Backoff backoff = new Backoff(CONFIG, new Random(7));
        for (int attempt = 0; attempt <= 6; attempt++) {
            Duration ceiling = Duration.ofSeconds(30L << attempt);
            if (ceiling.compareTo(CONFIG.maxRequeueDelay()) > 0) {
                ceiling = CONFIG.maxRequeueDelay();
            }
            for (int i = 0; i < 200; i++) {
                Duration d = backoff.retry(attempt, Duration.ZERO);
                assertThat(d).isGreaterThanOrEqualTo(Duration.ZERO).isLessThan(ceiling);
            }
        }
    }

    @Test
    @DisplayName("AC2: same seed → same delays (deterministic)")
    void seeded() {
        Backoff a = new Backoff(CONFIG, new Random(99));
        Backoff b = new Backoff(CONFIG, new Random(99));
        for (int i = 0; i < 20; i++) {
            assertThat(a.retry(i % 5, Duration.ZERO)).isEqualTo(b.retry(i % 5, Duration.ZERO));
        }
    }

    @Test
    @DisplayName("AC1: retryAfter is a floor; the result is capped at maxRequeueDelay (EC-10)")
    void retryAfterFloorAndCap() {
        Backoff backoff = new Backoff(CONFIG, new Random(1));
        assertThat(backoff.retry(0, Duration.ofMinutes(2))).isGreaterThanOrEqualTo(Duration.ofMinutes(2));
        assertThat(backoff.retry(0, Duration.ofMinutes(20))).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("large attempts do not overflow; ceiling stays at the cap")
    void largeAttempt() {
        Backoff backoff = new Backoff(CONFIG, new Random(1));
        assertThat(backoff.retry(1_000, Duration.ZERO)).isLessThan(CONFIG.maxRequeueDelay());
    }

    @Test
    @DisplayName("G-24: deferral is firmCapDeferral × (1 + U[0,1)); jitter is U[0, firmCapDeferral)")
    void deferralAndJitter() {
        Backoff backoff = new Backoff(CONFIG, new Random(3));
        for (int i = 0; i < 200; i++) {
            assertThat(backoff.deferral()).isGreaterThanOrEqualTo(CONFIG.firmCapDeferral())
                    .isLessThan(CONFIG.firmCapDeferral().multipliedBy(2));
            assertThat(backoff.jitter()).isGreaterThanOrEqualTo(Duration.ZERO).isLessThan(CONFIG.firmCapDeferral());
        }
    }
}
