package com.caseware.fanout.testkit;

import com.caseware.fanout.domain.EngagementTemplateState;
import com.caseware.fanout.port.EngagementLoader;
import com.caseware.fanout.port.LoaderException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Scriptable loader for tests. Each call takes the engagement's next scripted {@link Step}, or the default.
 * Calls can be held behind a gate (deterministic tests) or delayed by real-time latency (concurrency tests).
 * Ignores the {@code timeout} argument: the executor enforces the hard timeout by interrupting (G-28);
 * an interrupted call throws {@code Transient} and keeps the interrupt flag. Tracks calls per engagement,
 * in-flight and peak concurrency (total and per firm) and start order. Thread-safe.
 */
public final class FakeEngagementLoader implements EngagementLoader {

    /** What one call does. */
    public sealed interface Step {
        static Step ok() {
            return new Ok(OptionalLong.of(1));
        }

        static Step ok(OptionalLong seq) {
            return new Ok(seq);
        }

        static Step throttled(Duration retryAfter) {
            return new Fail(eng -> new LoaderException.Throttled(eng, retryAfter));
        }

        static Step transientFailure() {
            return new Fail(eng -> new LoaderException.Transient(eng, null));
        }

        static Step notFound() {
            return new Fail(LoaderException.NotFound::new);
        }

        static Step permanent() {
            return new Fail(LoaderException.Permanent::new);
        }

        /** An unexpected bug or poison payload (G-23). */
        static Step runtime(RuntimeException e) {
            return new Throw(e);
        }

        /** Blocks until the calling thread is interrupted (hard-timeout tests). */
        static Step hang() {
            return new Hang();
        }

        /**
         * Ignores interruption: waits for {@link #releaseCalls()} (if calls are held), then succeeds with seq 1
         * and keeps the interrupt flag (G-28 late-result tests).
         */
        static Step stubborn() {
            return new Stubborn();
        }
    }

    private record Ok(OptionalLong seq) implements Step { }

    private record Fail(Function<String, LoaderException> error) implements Step { }

    private record Throw(RuntimeException error) implements Step { }

    private record Hang() implements Step { }

    private record Stubborn() implements Step { }

    /** One loader call, in start order. */
    public record Call(String firmId, String engagementId) { }

    private final Clock clock;
    private final Map<String, Queue<Step>> scripts = new ConcurrentHashMap<>();
    private volatile Step defaultStep = Step.ok();
    private volatile Duration latency = Duration.ZERO;
    private volatile CountDownLatch gate;

    private final Map<String, AtomicInteger> callsPerEngagement = new ConcurrentHashMap<>();
    private final AtomicInteger totalCalls = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final Map<String, AtomicInteger> firmInFlight = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> firmPeak = new ConcurrentHashMap<>();
    private final List<Call> startOrder = Collections.synchronizedList(new ArrayList<>());

    public FakeEngagementLoader(Clock clock) {
        this.clock = clock;
    }

    /** Appends steps for {@code engagementId}; once used up, the default applies. */
    public void script(String engagementId, Step... steps) {
        scripts.computeIfAbsent(engagementId, k -> new ConcurrentLinkedQueue<>()).addAll(List.of(steps));
    }

    public void setDefault(Step step) {
        defaultStep = step;
    }

    /** Real-time delay before each call's step runs (concurrency tests). */
    public void setLatency(Duration latency) {
        this.latency = latency;
    }

    /** Subsequent calls block until {@link #releaseCalls()}. */
    public void holdCalls() {
        gate = new CountDownLatch(1);
    }

    /** Lets every held call proceed and stops holding new ones. */
    public void releaseCalls() {
        CountDownLatch g = gate;
        gate = null;
        if (g != null) {
            g.countDown();
        }
    }

    @Override
    public EngagementTemplateState load(String firmId, String engagementId, Duration timeout) throws LoaderException {
        Step step = nextStep(engagementId);
        enter(firmId, engagementId);
        try {
            CountDownLatch g = gate;
            if (g != null) {
                if (step instanceof Stubborn) {
                    awaitUninterruptibly(g);
                } else {
                    g.await();
                }
            }
            if (latency.isPositive()) {
                Thread.sleep(latency);
            }
            return switch (step) {
                case Ok ok -> state(engagementId, ok.seq());
                case Stubborn st -> state(engagementId, OptionalLong.of(1));
                case Fail f -> throw f.error().apply(engagementId);
                case Throw t -> throw t.error();
                case Hang h -> {
                    new CountDownLatch(1).await();
                    throw new IllegalStateException("unreachable");
                }
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LoaderException.Transient(engagementId, e);
        } finally {
            exit(firmId);
        }
    }

    private EngagementTemplateState state(String engagementId, OptionalLong seq) {
        return new EngagementTemplateState(engagementId, "tpl-1", "br-1", "v1", List.of(), seq, clock.instant());
    }

    private static void awaitUninterruptibly(CountDownLatch g) {
        boolean interrupted = false;
        while (true) {
            try {
                g.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public int calls(String engagementId) {
        AtomicInteger n = callsPerEngagement.get(engagementId);
        return n == null ? 0 : n.get();
    }

    public int totalCalls() {
        return totalCalls.get();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int peakInFlight() {
        return peak.get();
    }

    public int peakInFlight(String firmId) {
        AtomicInteger n = firmPeak.get(firmId);
        return n == null ? 0 : n.get();
    }

    /** Highest per-firm peak over all firms. */
    public int maxFirmPeak() {
        return firmPeak.values().stream().mapToInt(AtomicInteger::get).max().orElse(0);
    }

    public List<Call> startOrder() {
        synchronized (startOrder) {
            return List.copyOf(startOrder);
        }
    }

    /** Waits (real time) until at least {@code n} calls are in flight. */
    public void awaitInFlight(int n, Duration timeout) {
        Await.until(() -> inFlight.get() >= n, timeout, n + " loader calls in flight");
    }

    private Step nextStep(String engagementId) {
        Queue<Step> q = scripts.get(engagementId);
        Step s = q == null ? null : q.poll();
        return s != null ? s : defaultStep;
    }

    private void enter(String firmId, String engagementId) {
        callsPerEngagement.computeIfAbsent(engagementId, k -> new AtomicInteger()).incrementAndGet();
        totalCalls.incrementAndGet();
        startOrder.add(new Call(firmId, engagementId));
        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        int f = firmInFlight.computeIfAbsent(firmId, k -> new AtomicInteger()).incrementAndGet();
        firmPeak.computeIfAbsent(firmId, k -> new AtomicInteger()).accumulateAndGet(f, Math::max);
    }

    private void exit(String firmId) {
        firmInFlight.get(firmId).decrementAndGet();
        inFlight.decrementAndGet();
    }
}
