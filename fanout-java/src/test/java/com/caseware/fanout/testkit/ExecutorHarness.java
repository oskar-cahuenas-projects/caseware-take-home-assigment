package com.caseware.fanout.testkit;

import com.caseware.fanout.adapter.memory.InMemoryCandidateSource;
import com.caseware.fanout.adapter.memory.InMemoryFanoutJobStore;
import com.caseware.fanout.adapter.memory.InMemoryLoadLedger;
import com.caseware.fanout.adapter.memory.InMemoryObservationPublisher;
import com.caseware.fanout.adapter.memory.InMemoryWorkQueue;
import com.caseware.fanout.adapter.memory.VirtualThreadScheduler;
import com.caseware.fanout.domain.ApplyHeadBatch;
import com.caseware.fanout.domain.Candidate;
import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.Lane;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.domain.ProjectionStatus;
import com.caseware.fanout.domain.TemplateVersionEvent;
import com.caseware.fanout.executor.CrashHook;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.executor.ExecutorFactory;
import com.caseware.fanout.executor.ExecutorPorts;
import com.caseware.fanout.executor.LaneQueues;
import com.caseware.fanout.executor.LoadExecutor;
import com.caseware.fanout.planner.Planner;
import com.caseware.fanout.planner.PlannerConfig;
import com.caseware.fanout.port.MessageSink;
import com.caseware.fanout.port.Scheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * N executors over shared in-memory adapters (G-15), with three lanes behind {@link LaneQueues} (G-13). {@link #deterministic} uses {@link MutableClock} and
 * {@link ManualScheduler} with {@code pollOnce()}; {@link #realTime} uses the system clock and
 * {@link VirtualThreadScheduler} with {@code start()}.
 */
public final class ExecutorHarness implements AutoCloseable {

    public static final String REGION = "ca-central-1";
    public static final long SEED = 42L;
    public static final Duration PLANNER_LEASE = Duration.ofMinutes(2);

    public final ExecutorConfig config;
    public final Clock clock;
    public final Scheduler scheduler;
    /** The publish lane; most tests use only this one. */
    public final InMemoryWorkQueue<LoadRequest> queue;
    public final InMemoryWorkQueue<LoadRequest> backfill;
    public final InMemoryWorkQueue<LoadRequest> reconcile;
    /** The three lanes as the executors see them (G-13). */
    public final LaneQueues lanes;
    public final InMemoryLoadLedger ledger;
    public final InMemoryFanoutJobStore jobs;
    public final InMemoryObservationPublisher publisher = new InMemoryObservationPublisher();
    public final FakeEngagementLoader loader;
    public final ExecutorPorts ports;
    public final List<LoadExecutor> executors;
    /** Planner side (story 14): candidate rows and the I3 commands the planners sent. */
    public final InMemoryCandidateSource candidates = new InMemoryCandidateSource();
    public final List<ApplyHeadBatch> commands = new CopyOnWriteArrayList<>();
    private final AtomicLong nextRequest = new AtomicLong();

    private ExecutorHarness(ExecutorConfig config, Clock clock, Scheduler scheduler, CrashHook crashHook) {
        this.config = config;
        this.clock = clock;
        this.scheduler = scheduler;
        this.queue = lane("publish", config, clock);
        this.backfill = lane("backfill", config, clock);
        this.reconcile = lane("reconcile", config, clock);
        this.lanes = new LaneQueues(Map.of(Lane.PUBLISH, queue, Lane.BACKFILL, backfill, Lane.RECONCILE, reconcile),
                config.laneWeights());
        this.ledger = new InMemoryLoadLedger(clock);
        this.jobs = new InMemoryFanoutJobStore(clock);
        this.loader = new FakeEngagementLoader(clock);
        this.ports = new ExecutorPorts(lanes, ledger, jobs, publisher, loader, scheduler, clock);
        this.executors = ExecutorFactory.create(config, ports, SEED, crashHook);
    }

    private static InMemoryWorkQueue<LoadRequest> lane(String name, ExecutorConfig config, Clock clock) {
        return new InMemoryWorkQueue<>(name, clock, config.visibilityTimeout(), config.maxReceiveCount(),
                config.maxRequeueDelay());
    }

    public static ExecutorHarness deterministic(ExecutorConfig config) {
        return deterministic(config, CrashHook.NONE);
    }

    /** All executors share {@code crashHook}; a {@link CrashInjector} fires once, in whichever executor hits it first. */
    public static ExecutorHarness deterministic(ExecutorConfig config, CrashHook crashHook) {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
        return new ExecutorHarness(config, clock, new ManualScheduler(clock), crashHook);
    }

    public static ExecutorHarness realTime(ExecutorConfig config) {
        return new ExecutorHarness(config, Clock.systemUTC(), new VirtualThreadScheduler(), CrashHook.NONE);
    }

    public MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    public LoadExecutor executor(int i) {
        return executors.get(i);
    }

    /** A job ready for outcomes: {@code PLANNED} with {@code expected} loads. */
    public void createJob(String jobId, int expected) {
        jobs.createIfAbsent(new FanoutJob(jobId, JobStatus.PLANNED, Optional.empty(), Optional.empty(),
                1, 0, expected, 0, 0));
    }

    public LoadRequest request(String jobId, String firmId, String engagementId) {
        return request(jobId, firmId, engagementId, "tpl-1");
    }

    public LoadRequest request(String jobId, String firmId, String engagementId, String templateId) {
        return request(jobId, firmId, engagementId, templateId, Lane.PUBLISH);
    }

    public LoadRequest request(String jobId, String firmId, String engagementId, String templateId, Lane lane) {
        return new LoadRequest("req-" + nextRequest.incrementAndGet(), jobId, firmId, engagementId, templateId,
                lane, 0, clock.instant(), "corr-" + jobId, 0, 1, REGION);
    }

    public LoadRequest send(String jobId, String firmId, String engagementId) {
        LoadRequest r = request(jobId, firmId, engagementId);
        queue.send(r);
        return r;
    }

    /** Sends to {@code lane}'s queue. */
    public LoadRequest send(Lane lane, String jobId, String firmId, String engagementId) {
        LoadRequest r = request(jobId, firmId, engagementId, "tpl-1", lane);
        switch (lane) {
            case PUBLISH -> queue.send(r);
            case BACKFILL -> backfill.send(r);
            case RECONCILE -> reconcile.send(r);
        }
        return r;
    }

    /** A template event for {@code prod-1 / tpl-1 / br-1}; {@code eventId} = {@code evt-<versionId>-<graphRev>}. */
    public TemplateVersionEvent templateEvent(TemplateVersionEvent.Kind kind, String versionId, long graphRev) {
        return new TemplateVersionEvent("evt-" + versionId + "-" + graphRev, 1, kind, "prod-1", "tpl-1", "br-1",
                versionId, graphRev, graphRev, clock.instant());
    }

    /** Adds {@code n} candidates for {@code tpl-1 / br-1}: {@code eng-<i>} in {@code firm-<i % 10>}. */
    public void addCandidates(int n, ProjectionStatus status) {
        int base = candidates.count("tpl-1", "br-1");
        for (int i = base; i < base + n; i++) {
            candidates.add("tpl-1", "br-1", new Candidate("firm-" + i % 10, "eng-" + i, status, 0));
        }
    }

    /** A planner that sends loads to the publish lane and commands to {@link #commands}. */
    public Planner planner(String owner, int pageSize) {
        return planner(owner, pageSize, queue);
    }

    /** As {@link #planner(String, int)}, sending loads through {@code loads} (e.g. a crashing decorator). */
    public Planner planner(String owner, int pageSize, MessageSink<LoadRequest> loads) {
        return new Planner(owner, new PlannerConfig(pageSize, PLANNER_LEASE, REGION), candidates, jobs,
                commands::add, loads, clock);
    }

    public void startAll() {
        executors.forEach(LoadExecutor::start);
    }

    @Override
    public void close() {
        executors.forEach(LoadExecutor::close);
        if (scheduler instanceof VirtualThreadScheduler v) {
            v.close();
        }
    }
}
