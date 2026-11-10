package com.caseware.fanout.testkit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.executor.CrashHook.Point;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class CrashInjectorTest {

    @Test
    @DisplayName("AC6: armed at a point → throws SimulatedCrash once at that point only")
    void throwsOnceAtArmedPoint() {
        CrashInjector crash = new CrashInjector();
        crash.arm(Point.AFTER_RECORD);

        assertThatCode(() -> crash.at(Point.AFTER_CLAIM)).doesNotThrowAnyException();
        assertThatCode(() -> crash.at(Point.AFTER_LOAD)).doesNotThrowAnyException();
        assertThatThrownBy(() -> crash.at(Point.AFTER_RECORD))
                .isInstanceOf(SimulatedCrash.class).isInstanceOf(Error.class)
                .hasMessageContaining("AFTER_RECORD");
        assertThatCode(() -> crash.at(Point.AFTER_RECORD)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SimulatedCrash is not an Exception, so a catch (Exception) handler skips it (G-11)")
    void notCaughtAsException() {
        CrashInjector crash = new CrashInjector();
        crash.arm(Point.AFTER_PUBLISH);
        assertThatThrownBy(() -> {
            try {
                crash.at(Point.AFTER_PUBLISH);
            } catch (Exception e) {
                throw new AssertionError("must not be caught as Exception", e);
            }
        }).isInstanceOf(SimulatedCrash.class);
    }
}
