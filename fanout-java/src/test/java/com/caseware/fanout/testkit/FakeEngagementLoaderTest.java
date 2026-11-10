package com.caseware.fanout.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.EngagementTemplateState;
import com.caseware.fanout.port.LoaderException;
import com.caseware.fanout.testkit.FakeEngagementLoader.Step;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class FakeEngagementLoaderTest {

    private static final Duration T = Duration.ofMinutes(5);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
    private final FakeEngagementLoader loader = new FakeEngagementLoader(clock);

    @Test
    @DisplayName("AC5: default step returns a state for the engagement, seq 1, observed now")
    void defaultOk() throws LoaderException {
        EngagementTemplateState s = loader.load("firm-1", "eng-1", T);

        assertThat(s.engagementId()).isEqualTo("eng-1");
        assertThat(s.engagementSeq()).hasValue(1);
        assertThat(s.observedAt()).isEqualTo(clock.instant());
        assertThat(loader.calls("eng-1")).isEqualTo(1);
        assertThat(loader.totalCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC5: scripted steps are consumed in order per engagement, then the default applies")
    void scriptedSteps() throws LoaderException {
        loader.script("eng-1", Step.throttled(Duration.ofSeconds(30)), Step.transientFailure(),
                Step.ok(OptionalLong.empty()));

        assertThatThrownBy(() -> loader.load("f", "eng-1", T)).isInstanceOfSatisfying(LoaderException.Throttled.class,
                t -> assertThat(t.retryAfter()).isEqualTo(Duration.ofSeconds(30)));
        assertThatThrownBy(() -> loader.load("f", "eng-1", T)).isInstanceOf(LoaderException.Transient.class);
        assertThat(loader.load("f", "eng-1", T).engagementSeq()).isEmpty();
        assertThat(loader.load("f", "eng-1", T).engagementSeq()).hasValue(1);
        assertThat(loader.load("f", "eng-2", T).engagementSeq()).hasValue(1);
        assertThat(loader.calls("eng-1")).isEqualTo(4);
    }

    @Test
    @DisplayName("AC5: NotFound, Permanent and an unexpected RuntimeException are thrown as scripted")
    void failureSteps() {
        loader.script("a", Step.notFound());
        loader.script("b", Step.permanent());
        loader.script("c", Step.runtime(new IllegalStateException("poison")));

        assertThatThrownBy(() -> loader.load("f", "a", T)).isInstanceOf(LoaderException.NotFound.class);
        assertThatThrownBy(() -> loader.load("f", "b", T)).isInstanceOf(LoaderException.Permanent.class);
        assertThatThrownBy(() -> loader.load("f", "c", T)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("AC5: the default step can be switched (e.g. a sustained 429 period)")
    void switchDefault() throws LoaderException {
        loader.setDefault(Step.throttled(Duration.ZERO));
        assertThatThrownBy(() -> loader.load("f", "x", T)).isInstanceOf(LoaderException.Throttled.class);
        loader.setDefault(Step.ok());
        assertThat(loader.load("f", "x", T)).isNotNull();
    }

    @Test
    @DisplayName("AC5: held calls block until released; in-flight and peaks are tracked, total and per firm")
    void gateAndPeaks() throws InterruptedException {
        loader.holdCalls();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String firm = i < 4 ? "big" : "small";
            String eng = "eng-" + i;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    loader.load(firm, eng, T);
                } catch (LoaderException e) {
                    throw new AssertionError(e);
                }
            }));
        }
        loader.awaitInFlight(6, Duration.ofSeconds(5));
        assertThat(loader.inFlight()).isEqualTo(6);

        loader.releaseCalls();
        for (Thread t : threads) {
            t.join();
        }

        assertThat(loader.inFlight()).isZero();
        assertThat(loader.peakInFlight()).isEqualTo(6);
        assertThat(loader.peakInFlight("big")).isEqualTo(4);
        assertThat(loader.peakInFlight("small")).isEqualTo(2);
        assertThat(loader.startOrder()).hasSize(6);
    }

    @Test
    @DisplayName("AC5: hang blocks until interrupted, then throws Transient and restores the interrupt flag")
    void hangUntilInterrupted() throws InterruptedException {
        loader.script("slow", Step.hang());
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                loader.load("f", "slow", T);
            } catch (Throwable e) {
                thrown.set(e);
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            }
        });
        loader.awaitInFlight(1, Duration.ofSeconds(5));
        t.interrupt();
        t.join();

        assertThat(thrown.get()).isInstanceOf(LoaderException.Transient.class);
        assertThat(interruptedAfter.get()).isTrue();
        assertThat(loader.inFlight()).isZero();
    }

    @Test
    @DisplayName("AC5: real-time latency delays each call")
    void latency() throws LoaderException {
        loader.setLatency(Duration.ofMillis(20));
        long start = System.nanoTime();
        loader.load("f", "e", T);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(20));
    }
}
