package com.caseware.fanout.testkit;

import com.caseware.fanout.adapter.memory.InMemoryFanoutJobStore;
import com.caseware.fanout.adapter.memory.InMemoryLoadLedger;
import com.caseware.fanout.adapter.memory.InMemoryObservationPublisher;
import com.caseware.fanout.adapter.memory.InMemoryWorkQueue;
import com.caseware.fanout.adapter.memory.VirtualThreadScheduler;
import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.domain.Lane;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.executor.CrashHook;
import com.caseware.fanout.executor.ExecutorConfig;
import com.caseware.fanout.executor.ExecutorFactory;
import com.caseware.fanout.executor.ExecutorPorts;
import com.caseware.fanout.executor.LoadExecutor;
import com.caseware.fanout.port.Scheduler;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * N executors over shared in-memory adapters (G-15). {@link #deterministic} uses {@link MutableClock} and
 * {@link ManualScheduler} with {@code pollOnce()}; {@link #realTime} uses the system clock and
 * {@link VirtualThreadScheduler} with {@code start()}.
 */
public final class ExecutorHarness implements AutoCloseable {

    public static final String REGION = "ca-central-1";
    public static final long SEED = 42L;

    public final ExecutorConfig config;
    public final Clock clock;
    public final Scheduler scheduler;
    public final InMemoryWorkQueue<LoadRequest> queue;
    public final InMemoryLoadLedger ledger;
    public final InMemoryFanoutJobStore jobs = new InMemoryFanoutJobStore();
    public final InMemoryObservationPublisher publisher = new InMemoryObservationPublisher();
    public final FakeEngagementLoader loader;
    public final ExecutorPorts ports;
    public final List<LoadExecutor> executors;
    private final AtomicLong nextRequest = new AtomicLong();

    private ExecutorHarness(ExecutorConfig config, Clock clock, Scheduler scheduler, CrashHook crashHook) {
        this.config = config;
        this.clock = clock;
        this.scheduler = scheduler;
        this.queue = new InMemoryWorkQueue<>("publish", clock, config.visibilityTimeout(),
                config.maxReceiveCount(), config.maxRequeueDelay());
        this.ledger = new InMemoryLoadLedger(clock);
        this.loader = new FakeEngagementLoader(clock);
        this.ports = new ExecutorPorts(queue, ledger, jobs, publisher, loader, scheduler, clock);
        this.executors = ExecutorFactory.create(config, ports, SEED, crashHook);
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
        return new LoadRequest("req-" + nextRequest.incrementAndGet(), jobId, firmId, engagementId, templateId,
                Lane.PUBLISH, 0, clock.instant(), "corr-" + jobId, 0, 1, REGION);
    }

    public LoadRequest send(String jobId, String firmId, String engagementId) {
        LoadRequest r = request(jobId, firmId, engagementId);
        queue.send(r);
        return r;
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
