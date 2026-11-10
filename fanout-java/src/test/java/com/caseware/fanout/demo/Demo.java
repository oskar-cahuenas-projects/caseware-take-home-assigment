package com.caseware.fanout.demo;

import com.caseware.fanout.domain.Candidate;
import com.caseware.fanout.domain.EngagementStateObserved;
import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.domain.ProjectionStatus;
import com.caseware.fanout.domain.TemplateVersionEvent;
import com.caseware.fanout.domain.TemplateVersionEvent.Kind;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.executor.LoadExecutor;
import com.caseware.fanout.planner.Planner;
import com.caseware.fanout.testkit.Await;
import com.caseware.fanout.testkit.ExecutorHarness;
import com.caseware.fanout.testkit.FakeEngagementLoader.Step;
import com.caseware.fanout.testkit.FakeTrackingServiceConsumer;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * End-to-end demo on fake data: publish events → Planner → lanes → N executors on virtual threads →
 * observations → a fake Tracking Service consumer. v7 loads every unknown engagement; v8, published right
 * after on the same branch, coalesces onto v7's fresh observations. Durations are scaled 1000× down
 * ({@link ExecutorConfig#forTests()}: C = 50, N = 2, firm cap 5), so the ~1-minute load takes {@value #LOAD_MS} ms.
 * <p>
 * Run: {@code ./mvnw -q test-compile && java -cp target/classes:target/test-classes com.caseware.fanout.demo.Demo}
 */
public final class Demo {

    static final int LOAD_MS = 5;
    static final int BIG_FIRM_UNKNOWN = 1_500;
    static final int SMALL_FIRMS = 20;
    static final int SMALL_FIRM_UNKNOWN = 10;
    static final int KNOWN = 300;
    static final List<String> NOT_FOUND = List.of("eng-small-3-0", "eng-small-7-0", "eng-small-11-0");
    static final List<String> THROTTLED_ONCE = List.of("eng-small-0-1", "eng-small-1-1", "eng-small-2-1", "eng-small-4-1");

    /** What the run produced; {@link DemoTest} checks it. */
    record Summary(int capacity, int firmCapAllTasks, int requestsV7, int loaderCalls, int callsForV8, int peakInFlight,
                   int peakBigFirm, int coalesced, int deadLettered, int headBatches, int observations,
                   int consumerApplied, JobStatus v7Status, JobStatus v8Status, Planner.Result duplicate) { }

    private Demo() { }

    public static void main(String[] args) {
        Logger.getLogger("").setLevel(Level.SEVERE);          // executor WARNINGs (expected DLQs) would clutter the output
        run(System.out);
    }

    static Summary run(PrintStream out) {
        ExecutorConfig config = ExecutorConfig.forTests();
        try (var h = ExecutorHarness.realTime(config)) {
            seed(h);
            h.loader.setLatency(Duration.ofMillis(LOAD_MS));
            NOT_FOUND.forEach(e -> h.loader.script(e, Step.notFound(), Step.notFound()));   // v7 and v8
            THROTTLED_ONCE.forEach(e -> h.loader.script(e, Step.throttled(Duration.ofMillis(20)), Step.ok()));

            // Stage A: plan v7; the event arrives twice.
            TemplateVersionEvent v7 = h.templateEvent(Kind.PUBLISHED, "v7", 1);
            Planner planner = h.planner("planner-1", 1_000);
            planner.handle(v7);
            Planner.Result duplicate = planner.handle(v7);
            int requestsV7 = h.queue.depth();

            // Stage B: two executor tasks drain the lanes.
            h.startAll();
            Await.until(() -> closed(h, v7), Duration.ofSeconds(60), "v7 job closed");
            int callsAfterV7 = h.loader.totalCalls();

            // v8 is published on the same branch: every engagement observed for v7 is still fresh, so v8 loads coalesce.
            TemplateVersionEvent v8 = h.templateEvent(Kind.PUBLISHED, "v8", 2);
            planner.handle(v8);
            Await.until(() -> closed(h, v8), Duration.ofSeconds(60), "v8 job closed");
            h.executors.forEach(LoadExecutor::close);

            FakeTrackingServiceConsumer consumer = new FakeTrackingServiceConsumer();
            List<EngagementStateObserved> observed = h.publisher.observedEvents();
            Map<FakeTrackingServiceConsumer.Effect, Long> effects = observed.stream()
                    .map(consumer::apply).collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            h.publisher.failedEvents().forEach(consumer::apply);

            FanoutJob j7 = h.jobs.find(v7.jobId()).orElseThrow();
            FanoutJob j8 = h.jobs.find(v8.jobId()).orElseThrow();
            Summary s = new Summary(config.capacity(), config.firmCap() * config.tasks(), requestsV7,
                    h.loader.totalCalls(), h.loader.totalCalls() - callsAfterV7, h.loader.peakInFlight(), h.loader.peakInFlight("firm-big"),
                    coalesced(h, v8), h.queue.deadLetters().size(), h.commands.size(), observed.size(),
                    effects.getOrDefault(FakeTrackingServiceConsumer.Effect.APPLIED, 0L).intValue(),
                    j7.status(), j8.status(), duplicate);
            print(out, config, s, j7, j8, h);
            return s;
        }
    }

    /** {@code KNOWN} rows (flag only) and {@code UNKNOWN} rows (need a load): one 1,500-engagement firm, 20 small firms. */
    private static void seed(ExecutorHarness h) {
        for (int i = 0; i < KNOWN; i++) {
            h.candidates.add("tpl-1", "br-1", new Candidate("firm-" + i % 10, "eng-known-" + i, ProjectionStatus.KNOWN, 0));
        }
        for (int i = 0; i < BIG_FIRM_UNKNOWN; i++) {
            h.candidates.add("tpl-1", "br-1", new Candidate("firm-big", "eng-big-" + i, ProjectionStatus.UNKNOWN, 0));
        }
        for (int f = 0; f < SMALL_FIRMS; f++) {
            for (int i = 0; i < SMALL_FIRM_UNKNOWN; i++) {
                h.candidates.add("tpl-1", "br-1",
                        new Candidate("firm-" + f, "eng-small-" + f + "-" + i, ProjectionStatus.UNVERIFIED, 0));
            }
        }
    }

    private static boolean closed(ExecutorHarness h, TemplateVersionEvent e) {
        return h.jobs.find(e.jobId()).map(j -> j.status() == JobStatus.COMPLETE).orElse(false);
    }

    private static int coalesced(ExecutorHarness h, TemplateVersionEvent e) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < BIG_FIRM_UNKNOWN; i++) {
            ids.add("eng-big-" + i);
        }
        for (int f = 0; f < SMALL_FIRMS; f++) {
            for (int i = 0; i < SMALL_FIRM_UNKNOWN; i++) {
                ids.add("eng-small-" + f + "-" + i);
            }
        }
        return (int) ids.stream()
                .filter(id -> h.jobs.outcome(e.jobId(), id).filter(o -> o == Outcome.COALESCED).isPresent())
                .count();
    }

    private static void print(PrintStream out, ExecutorConfig c, Summary s, FanoutJob j7, FanoutJob j8, ExecutorHarness h) {
        out.println("Fan-out worker demo (in memory, durations scaled 1000x: a 1-min load takes " + LOAD_MS + " ms)");
        out.printf("  C = %d loader permits, N = %d executor tasks, per-firm cap = %d per task%n",
                c.capacity(), c.tasks(), c.firmCap());
        out.println();
        out.println("Stage A - Planner");
        out.printf("  v7 published: %d ApplyHeadBatch commands (<= 100 known rows each), %d load requests%n",
                j7.flagBatchesSent(), s.requestsV7());
        out.printf("  duplicate v7 event -> %s (one job, one planning pass)%n", s.duplicate());
        out.printf("  v8 published after v7 finished: %d load requests for the same engagements%n", j8.loadsExpected());
        out.println();
        out.println("Stage B - Load Executor");
        out.printf("  loader calls          %6d   (%d unique engagements + %d throttled retries + v8)%n",
                s.loaderCalls(), BIG_FIRM_UNKNOWN + SMALL_FIRMS * SMALL_FIRM_UNKNOWN, THROTTLED_ONCE.size());
        out.printf("  peak concurrent calls %6d   (limit C = %d)%n", s.peakInFlight(), s.capacity());
        out.printf("  peak calls, big firm  %6d   (limit cap x N = %d)%n", s.peakBigFirm(), s.firmCapAllTasks());
        out.printf("  v8 loads coalesced    %6d   (still fresh from v7: no reload)%n", s.coalesced());
        out.printf("  loader calls for v8   %6d   (only the NotFound engagements, which have no fresh observation)%n", s.callsForV8());
        out.printf("  dead-lettered         %6d   (NotFound: %s)%n", s.deadLettered(), String.join(", ", NOT_FOUND));
        out.printf("  breaker states        %s%n", h.executors.stream().map(e -> e.breakerState().name()).toList());
        out.println();
        out.println("Jobs");
        out.printf("  %-24s %-9s expected %4d  done %4d  dlq %d%n", j7.jobId(), j7.status(), j7.loadsExpected(), j7.loadsDone(), j7.loadsDlq());
        out.printf("  %-24s %-9s expected %4d  done %4d  dlq %d%n", j8.jobId(), j8.status(), j8.loadsExpected(), j8.loadsDone(), j8.loadsDlq());
        out.println();
        out.println("Tracking Service (fake consumer)");
        out.printf("  %d EngagementStateObserved published, %d applied to the projection; %d EngagementLoadFailed%n",
                s.observations(), s.consumerApplied(), h.publisher.failedEvents().size());
    }
}
