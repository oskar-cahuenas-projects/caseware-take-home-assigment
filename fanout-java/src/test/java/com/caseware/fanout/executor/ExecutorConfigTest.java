package com.caseware.fanout.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.Lane;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class ExecutorConfigTest {

    @Test
    @DisplayName("AC3: production() equals the contract defaults")
    void productionDefaults() {
        ExecutorConfig c = ExecutorConfig.production();

        assertThat(c.visibilityTimeout()).isEqualTo(Duration.ofMinutes(3));
        assertThat(c.heartbeatInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(c.heartbeatExtension()).isEqualTo(Duration.ofMinutes(3));
        assertThat(c.loaderHardTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(c.maxReceiveCount()).isEqualTo(5);
        assertThat(c.freshnessWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(c.laneWeights().weight(Lane.PUBLISH)).isEqualTo(6);
        assertThat(c.laneWeights().weight(Lane.BACKFILL)).isEqualTo(3);
        assertThat(c.laneWeights().weight(Lane.RECONCILE)).isEqualTo(1);
        assertThat(c.maxRequeueDelay()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("AC3: production() carries the approved gap defaults")
    void gapDefaults() {
        ExecutorConfig c = ExecutorConfig.production();

        assertThat(c.lease()).isEqualTo(Duration.ofMinutes(6));                 // G-14
        assertThat(c.breakerFailureThreshold()).isEqualTo(5);                   // G-03
        assertThat(c.breakerCooldown()).isEqualTo(Duration.ofSeconds(30));      // G-03
        assertThat(c.backoffBase()).isEqualTo(Duration.ofSeconds(30));          // G-16
        assertThat(c.maxTransientAttempts()).isEqualTo(8);                      // G-16
        assertThat(c.firmCapDeferral()).isEqualTo(Duration.ofSeconds(30));      // G-24
        assertThat(c.maxReceiveBatch()).isEqualTo(10);                          // G-24
    }

    @Test
    @DisplayName("G-15: permitsPerTask = floor(C/N); firmCap = max(1, floor(0.2 * C/N))")
    void derivedCapacity() {
        ExecutorConfig c50 = ExecutorConfig.production().toBuilder().capacity(50).tasks(2).build();
        assertThat(c50.permitsPerTask()).isEqualTo(25);
        assertThat(c50.firmCap()).isEqualTo(5);

        ExecutorConfig c10 = ExecutorConfig.production().toBuilder().capacity(10).tasks(4).build();
        assertThat(c10.permitsPerTask()).isEqualTo(2);                          // remainder 2 unused
        assertThat(c10.firmCap()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC4: lease shorter than hard timeout + margin is rejected (G-14)")
    void rejectsShortLease() {
        assertThatThrownBy(() -> ExecutorConfig.production().toBuilder().lease(Duration.ofMinutes(5)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
    }

    @Test
    @DisplayName("AC4: heartbeat interval not shorter than visibility is rejected")
    void rejectsSlowHeartbeat() {
        assertThatThrownBy(() -> ExecutorConfig.production().toBuilder()
                .heartbeatInterval(Duration.ofMinutes(3)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("heartbeat");
    }

    @Test
    @DisplayName("AC4: C < N is rejected (G-15)")
    void rejectsCapacityBelowTasks() {
        assertThatThrownBy(() -> ExecutorConfig.production().toBuilder().capacity(1).tasks(2).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capacity");
    }

    @Test
    @DisplayName("AC4: non-positive durations and counts are rejected")
    void rejectsNonPositive() {
        assertThatThrownBy(() -> ExecutorConfig.production().toBuilder().maxReceiveCount(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutorConfig.production().toBuilder().backoffBase(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutorConfig.LaneWeights(6, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("G-18: the E7 profile (hard timeout 10 min, lease 11 min) is valid")
    void e7Profile() {
        ExecutorConfig e7 = ExecutorConfig.production().toBuilder()
                .loaderHardTimeout(Duration.ofMinutes(10)).lease(Duration.ofMinutes(11)).build();
        assertThat(e7.visibilityTimeout()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    @DisplayName("forTests() scales every duration 1000x down and keeps the numbers")
    void testProfile() {
        ExecutorConfig p = ExecutorConfig.production();
        ExecutorConfig t = ExecutorConfig.forTests();

        assertThat(t.visibilityTimeout()).isEqualTo(Duration.ofMillis(180));
        assertThat(t.heartbeatInterval()).isEqualTo(Duration.ofMillis(60));
        assertThat(t.lease()).isEqualTo(Duration.ofMillis(360));
        assertThat(t.maxRequeueDelay()).isEqualTo(Duration.ofMillis(900));
        assertThat(t.maxReceiveCount()).isEqualTo(p.maxReceiveCount());
        assertThat(t.permitsPerTask()).isEqualTo(p.permitsPerTask());
        assertThat(t.laneWeights()).isEqualTo(p.laneWeights());
    }
}
