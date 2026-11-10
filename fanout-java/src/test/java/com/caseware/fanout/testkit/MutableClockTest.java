package com.caseware.fanout.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class MutableClockTest {

    private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

    @Test
    @DisplayName("AC1: advance moves instant() by exactly that amount")
    void advanceIsExact() {
        MutableClock clock = new MutableClock(T0);
        clock.advance(Duration.ofMillis(1500));
        assertThat(clock.instant()).isEqualTo(T0.plusMillis(1500));
        assertThat(clock.millis()).isEqualTo(T0.toEpochMilli() + 1500);
    }

    @Test
    @DisplayName("AC1: concurrent advances are never lost")
    void concurrentAdvancesAreAtomic() throws InterruptedException {
        MutableClock clock = new MutableClock(T0);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            threads.add(Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 1_000; i++) {
                    clock.advance(Duration.ofMillis(1));
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(clock.instant()).isEqualTo(T0.plusMillis(16_000));
    }

    @Test
    @DisplayName("time never moves backwards")
    void rejectsNegativeAdvance() {
        MutableClock clock = new MutableClock(T0);
        assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("withZone shares the same time source")
    void withZoneSharesState() {
        MutableClock clock = new MutableClock(T0);
        var other = clock.withZone(ZoneId.of("America/Toronto"));
        clock.advance(Duration.ofMinutes(1));
        assertThat(other.instant()).isEqualTo(T0.plusSeconds(60));
        assertThat(other.getZone()).isEqualTo(ZoneId.of("America/Toronto"));
    }
}
