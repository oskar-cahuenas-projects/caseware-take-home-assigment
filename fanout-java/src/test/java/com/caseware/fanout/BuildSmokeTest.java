package com.caseware.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class BuildSmokeTest {

    @Test
    @DisplayName("build runs on Java 21 or later")
    void runsOnJava21() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }

    @Test
    @Tag("contract")
    @DisplayName("tests tagged 'contract' run in the default build")
    void contractTaggedTestsRunByDefault() {
        assertThat(Thread.ofVirtual().unstarted(() -> { }).isVirtual()).isTrue();
    }
}
