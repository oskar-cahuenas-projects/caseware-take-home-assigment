package com.caseware.fanout.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.port.Scheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class ManualSchedulerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final List<String> ran = new CopyOnWriteArrayList<>();

    @Test
    @DisplayName("AC2: nothing runs before its due time")
    void notDueNotRun() {
        scheduler.schedule(() -> ran.add("a"), Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(9));
        assertThat(scheduler.runDue()).isZero();
        assertThat(ran).isEmpty();
    }

    @Test
    @DisplayName("AC2: due tasks run in due-time order, then not again")
    void dueTasksRunInOrderOnce() {
        scheduler.schedule(() -> ran.add("c"), Duration.ofSeconds(30));
        scheduler.schedule(() -> ran.add("a"), Duration.ofSeconds(10));
        scheduler.schedule(() -> ran.add("b"), Duration.ofSeconds(20));
        scheduler.schedule(() -> ran.add("b2"), Duration.ofSeconds(20));

        clock.advance(Duration.ofSeconds(30));
        assertThat(scheduler.runDue()).isEqualTo(4);
        assertThat(scheduler.runDue()).isZero();
        assertThat(ran).containsExactly("a", "b", "b2", "c");
    }

    @Test
    @DisplayName("AC2: a cancelled task never runs; cancel is idempotent")
    void cancelledNeverRuns() {
        Scheduler.Cancellable handle = scheduler.schedule(() -> ran.add("x"), Duration.ofSeconds(1));
        handle.cancel();
        handle.cancel();
        clock.advance(Duration.ofSeconds(5));
        scheduler.runDue();
        assertThat(ran).isEmpty();
        assertThat(scheduler.pendingCount()).isZero();
    }

    @Test
    @DisplayName("AC2: a fixed-rate task fires once per elapsed period until cancelled")
    void fixedRateFiresPerPeriod() {
        Scheduler.Cancellable hb = scheduler.scheduleAtFixedRate(() -> ran.add("hb"),
                Duration.ofSeconds(60), Duration.ofSeconds(60));

        clock.advance(Duration.ofSeconds(59));
        scheduler.runDue();
        assertThat(ran).isEmpty();

        clock.advance(Duration.ofSeconds(121));      // t = 180 s: due at 60, 120, 180
        scheduler.runDue();
        assertThat(ran).hasSize(3);

        hb.cancel();
        clock.advance(Duration.ofMinutes(10));
        scheduler.runDue();
        assertThat(ran).hasSize(3);
    }

    @Test
    @DisplayName("a task may schedule another task; it runs in the same runDue if already due")
    void tasksScheduledWhileRunning() {
        scheduler.schedule(() -> {
            ran.add("outer");
            scheduler.schedule(() -> ran.add("inner"), Duration.ZERO);
        }, Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(1));
        scheduler.runDue();
        assertThat(ran).containsExactly("outer", "inner");
    }
}
