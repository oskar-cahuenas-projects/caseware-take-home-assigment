package com.caseware.fanout.executor;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

import com.caseware.fanout.domain.EngagementStateObserved;
import com.caseware.fanout.domain.EngagementTemplateState;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.domain.ObservationIds;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.port.Claim;
import com.caseware.fanout.port.ConcurrencyLimiter;
import com.caseware.fanout.port.Delivery;
import com.caseware.fanout.port.LoaderException;
import com.caseware.fanout.port.Permit;
import com.caseware.fanout.port.Scheduler;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One executor task (Stage B). Handles each {@link LoadRequest} in the contract's load-bearing order (§C):
 * claim ledger → acquire permit → call loader → recordObservation → publish → markPublished →
 * record job outcome → ack. {@link #pollOnce()} runs one receive synchronously (deterministic tests);
 * {@link #start()} runs a receive loop on a virtual thread with one virtual thread per message.
 * Thread-safe.
 */
public final class LoadExecutor implements AutoCloseable {

    static final int SCHEMA_VERSION = 1;
    private static final System.Logger LOG = System.getLogger(LoadExecutor.class.getName());

    private final String workerId;
    private final ExecutorPorts ports;
    private final ConcurrencyLimiter limiter;
    private final ExecutorConfig config;
    private final Random random;

    private final AtomicInteger activeHandlers = new AtomicInteger();
    private final Semaphore wakeup = new Semaphore(0);
    private volatile boolean running;
    private Thread loop;
    private ExecutorService handlers;

    public LoadExecutor(String workerId, ExecutorPorts ports, ConcurrencyLimiter limiter,
                        ExecutorConfig config, Random random) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.config = Objects.requireNonNull(config, "config");
        this.random = Objects.requireNonNull(random, "random");
    }

    public String workerId() {
        return workerId;
    }

    public ConcurrencyLimiter limiter() {
        return limiter;
    }

    /** Receives one batch and handles it on the calling thread. Returns the number of messages handled. */
    public int pollOnce() {
        List<Delivery<LoadRequest>> batch = receiveBatch();
        for (Delivery<LoadRequest> d : batch) {
            activeHandlers.incrementAndGet();
            handleAndSignal(d);
        }
        return batch.size();
    }

    /** Starts the receive loop. Not restartable after {@link #close()}. */
    public synchronized void start() {
        if (loop != null) {
            throw new IllegalStateException("already started");
        }
        running = true;
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        loop = Thread.ofVirtual().name("executor-" + workerId).start(this::runLoop);
    }

    /** Stops receiving and waits for in-flight handlers to finish. Idempotent. */
    @Override
    public synchronized void close() {
        running = false;
        wakeup.release();
        if (loop != null) {
            try {
                loop.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handlers.close();
        }
    }

    private void runLoop() {
        while (running) {
            List<Delivery<LoadRequest>> batch = receiveBatch();
            if (batch.isEmpty()) {
                idle();
                continue;
            }
            for (Delivery<LoadRequest> d : batch) {
                activeHandlers.incrementAndGet();
                handlers.submit(() -> handleAndSignal(d));
            }
        }
    }

    /**
     * Receives at most {@code min(maxReceiveBatch, freePermits, permitsPerTask − activeHandlers)} messages
     * (G-24), so a task never holds more messages than it could load at once. Zero room → no receive.
     */
    private List<Delivery<LoadRequest>> receiveBatch() {
        int room = Math.min(limiter.freePermits(), config.permitsPerTask() - activeHandlers.get());
        if (room <= 0) {
            return List.of();
        }
        return ports.queue().receive(Math.min(config.maxReceiveBatch(), room), config.receiveWait());
    }

    /** Waits for a finishing handler or {@code receiveWait} on the scheduler; never sleeps or spins (EC-15). */
    private void idle() {
        Scheduler.Cancellable timer = ports.scheduler().schedule(wakeup::release, config.receiveWait());
        try {
            wakeup.acquire();
            wakeup.drainPermits();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        } finally {
            timer.cancel();
        }
    }

    private void handleAndSignal(Delivery<LoadRequest> d) {
        try {
            handle(d);
        } finally {
            activeHandlers.decrementAndGet();
            wakeup.release();
        }
    }

    private void handle(Delivery<LoadRequest> d) {
        LoadRequest r = d.body();
        try {
            Claim claim = ports.ledger().claim(r.engagementId(), r.jobId(), workerId,
                    config.lease(), config.freshnessWindow(), r.knownSeq());                     // 1
            switch (claim) {
                case Claim.AlreadyFresh fresh -> {
                    ports.jobs().recordOutcome(r.jobId(), r.engagementId(), Outcome.COALESCED);
                    ports.queue().ack(d);
                    log(r, "coalesced");
                }
                case Claim.InFlightElsewhere busy -> {
                    Duration untilLeaseEnds = Duration.between(ports.clock().instant(), busy.leaseUntil());
                    ports.queue().requeueWithDelay(d, nonNegative(untilLeaseEnds).plus(jitter()));       // G-05
                    log(r, "in flight elsewhere, requeued");
                }
                case Claim.PendingPublish pending ->
                        publishAndFinish(d, r, pending.state(), pending.eventId(), pending.jobIds());
                case Claim.Claimed claimed -> loadAndPublish(d, r);
            }
        } catch (RuntimeException e) {
            // G-23: release what we hold, leave the message un-acked; the queue dead-letters after maxReceiveCount.
            releaseQuietly(r);
            LOG.log(WARNING, "handler failed: correlationId={0} jobId={1} engagementId={2} error={3}",
                    r.correlationId(), r.jobId(), r.engagementId(), e.getClass().getName());
        }
    }

    @SuppressWarnings("try") // the permit is held only for the loader call's duration
    private void loadAndPublish(Delivery<LoadRequest> d, LoadRequest r) {
        Optional<Permit> permit = limiter.tryAcquire(r.firmId());                                   // 2
        if (permit.isEmpty()) {
            ports.ledger().release(r.engagementId(), workerId);                                     // G-21
            ports.queue().requeueWithDelay(d, config.firmCapDeferral().plus(jitter()));             // G-24
            log(r, "no permit, deferred");
            return;
        }
        EngagementTemplateState state;
        try (Permit held = permit.get()) {
            state = ports.loader().load(r.firmId(), r.engagementId(), config.loaderHardTimeout());   // 3
        } catch (LoaderException e) {
            // Interim until stories 10/11 classify failures: release the claim and leave the message for redelivery.
            ports.ledger().release(r.engagementId(), workerId);
            LOG.log(WARNING, "load failed: correlationId={0} jobId={1} engagementId={2} error={3}",
                    r.correlationId(), r.jobId(), r.engagementId(), e.getClass().getSimpleName());
            return;
        }
        String eventId = ObservationIds.of(state, r.requestId());
        ports.ledger().recordObservation(r.engagementId(), state, eventId);                         // 4
        publishAndFinish(d, r, state, eventId, List.of(r.jobId()));
    }

    private void publishAndFinish(Delivery<LoadRequest> d, LoadRequest r, EngagementTemplateState state,
                                  String eventId, List<String> jobIds) {
        ports.publisher().publish(new EngagementStateObserved(eventId, SCHEMA_VERSION, r.region(), r.firmId(),
                state, jobIds, r.correlationId(), state.engagementSeq().isPresent()));               // 5
        ports.ledger().markPublished(r.engagementId(), eventId);                                    // 6
        ports.jobs().recordOutcome(r.jobId(), r.engagementId(), Outcome.DONE);                      // 7
        ports.queue().ack(d);                                                                        // 8
        log(r, "done");
    }

    /** Uniform jitter in {@code [0, firmCapDeferral)} from the injected, seeded {@link Random}. */
    private Duration jitter() {
        return Duration.ofNanos((long) (config.firmCapDeferral().toNanos() * random.nextDouble()));
    }

    private void releaseQuietly(LoadRequest r) {
        try {
            ports.ledger().release(r.engagementId(), workerId);
        } catch (RuntimeException ignored) {
            // The lease expires on its own (G-14).
        }
    }

    private static Duration nonNegative(Duration d) {
        return d.isNegative() ? Duration.ZERO : d;
    }

    private void log(LoadRequest r, String what) {
        LOG.log(DEBUG, "{0}: workerId={1} correlationId={2} jobId={3} engagementId={4}",
                what, workerId, r.correlationId(), r.jobId(), r.engagementId());
    }
}
