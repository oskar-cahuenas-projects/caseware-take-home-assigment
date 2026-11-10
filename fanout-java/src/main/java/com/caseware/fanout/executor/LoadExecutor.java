package com.caseware.fanout.executor;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

import com.caseware.fanout.domain.EngagementLoadFailed;
import com.caseware.fanout.domain.EngagementStateObserved;
import com.caseware.fanout.domain.EngagementTemplateState;
import com.caseware.fanout.domain.FailureReason;
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
 * A per-task {@link CircuitBreaker} gates every receive (G-03, G-27). A {@link CrashHook} is called after
 * each durable step (G-11); handlers catch {@code RuntimeException} only, so a simulated crash
 * ({@code Error}) skips ack, requeue and release. Thread-safe.
 */
public final class LoadExecutor implements AutoCloseable {

    static final int SCHEMA_VERSION = 1;
    private static final System.Logger LOG = System.getLogger(LoadExecutor.class.getName());

    private final String workerId;
    private final ExecutorPorts ports;
    private final ConcurrencyLimiter limiter;
    private final ExecutorConfig config;
    private final Backoff backoff;
    private final CircuitBreaker breaker;
    private final CrashHook crashHook;

    private final AtomicInteger activeHandlers = new AtomicInteger();
    private final Semaphore wakeup = new Semaphore(0);
    private volatile boolean running;
    private Thread loop;
    private ExecutorService handlers;

    public LoadExecutor(String workerId, ExecutorPorts ports, ConcurrencyLimiter limiter,
                        ExecutorConfig config, Random random, CrashHook crashHook) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.config = Objects.requireNonNull(config, "config");
        this.backoff = new Backoff(config, random);
        this.breaker = new CircuitBreaker(config.breakerFailureThreshold(), config.breakerCooldown(), ports.clock());
        this.crashHook = Objects.requireNonNull(crashHook, "crashHook");
    }

    public String workerId() {
        return workerId;
    }

    public ConcurrencyLimiter limiter() {
        return limiter;
    }

    /** Breaker state, for tests and observability (NFR-10). */
    public CircuitBreaker.State breakerState() {
        return breaker.state();
    }

    /** Receives one batch and handles it on the calling thread. Returns the number of messages handled. */
    public int pollOnce() {
        Batch batch = receiveBatch();
        for (Delivery<LoadRequest> d : batch.deliveries()) {
            activeHandlers.incrementAndGet();
            handleAndSignal(d, batch.probe());
        }
        return batch.deliveries().size();
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
            Batch batch = receiveBatch();
            if (batch.deliveries().isEmpty()) {
                idle();
                continue;
            }
            for (Delivery<LoadRequest> d : batch.deliveries()) {
                activeHandlers.incrementAndGet();
                handlers.submit(() -> handleAndSignal(d, batch.probe()));
            }
        }
    }

    /** Received messages; {@code probe} marks the single HALF_OPEN probe. */
    private record Batch(List<Delivery<LoadRequest>> deliveries, boolean probe) {
        static final Batch EMPTY = new Batch(List.of(), false);
    }

    /**
     * Receives at most {@code min(maxReceiveBatch, freePermits, permitsPerTask − activeHandlers)} messages
     * (G-24), further limited by the breaker: none while OPEN, one probe while HALF_OPEN (§C: an open breaker
     * stops receiving). Zero room → no receive.
     */
    private Batch receiveBatch() {
        int room = Math.min(config.maxReceiveBatch(),
                Math.min(limiter.freePermits(), config.permitsPerTask() - activeHandlers.get()));
        if (room <= 0) {
            return Batch.EMPTY;
        }
        CircuitBreaker.Admission admission = breaker.admit(room);
        if (admission.count() == 0) {
            return Batch.EMPTY;
        }
        List<Delivery<LoadRequest>> got = ports.queue().receive(admission.count(), config.receiveWait());
        if (got.isEmpty() && admission.probe()) {
            breaker.probeAbandoned();
        }
        return new Batch(got, admission.probe());
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

    private void handleAndSignal(Delivery<LoadRequest> d, boolean probe) {
        boolean verdict = false;
        try {
            verdict = handle(d);
        } finally {
            if (probe && !verdict) {
                breaker.probeAbandoned();
            }
            activeHandlers.decrementAndGet();
            wakeup.release();
        }
    }

    /** Returns {@code true} if the breaker received a verdict (a loader success or a counted failure). */
    private boolean handle(Delivery<LoadRequest> d) {
        LoadRequest r = d.body();
        try {
            Claim claim = ports.ledger().claim(r.engagementId(), r.jobId(), workerId,
                    config.lease(), config.freshnessWindow(), r.knownSeq());                     // 1
            crashHook.at(CrashHook.Point.AFTER_CLAIM);
            return switch (claim) {
                case Claim.AlreadyFresh fresh -> {
                    ports.jobs().recordOutcome(r.jobId(), r.engagementId(), Outcome.COALESCED);
                    ports.queue().ack(d);
                    log(r, "coalesced");
                    yield false;
                }
                case Claim.InFlightElsewhere busy -> {
                    Duration untilLeaseEnds = Duration.between(ports.clock().instant(), busy.leaseUntil());
                    ports.queue().requeueWithDelay(d, nonNegative(untilLeaseEnds).plus(backoff.jitter()));  // G-05
                    log(r, "in flight elsewhere, requeued");
                    yield false;
                }
                case Claim.PendingPublish pending -> {
                    publishAndFinish(d, r, pending.state(), pending.eventId(), pending.jobIds());
                    yield false;
                }
                case Claim.Claimed claimed -> loadAndPublish(d, r);
            };
        } catch (RuntimeException e) {
            // G-23: release what we hold, leave the message un-acked; the queue dead-letters after maxReceiveCount.
            releaseQuietly(r);
            LOG.log(WARNING, "handler failed: correlationId={0} jobId={1} engagementId={2} error={3}",
                    r.correlationId(), r.jobId(), r.engagementId(), e.getClass().getName());
            return false;
        }
    }

    @SuppressWarnings("try") // the permit is held only for the loader call's duration
    private boolean loadAndPublish(Delivery<LoadRequest> d, LoadRequest r) {
        Optional<Permit> permit = limiter.tryAcquire(r.firmId());                                   // 2
        if (permit.isEmpty()) {
            ports.ledger().release(r.engagementId(), workerId);                                     // G-21
            ports.queue().requeueWithDelay(d, backoff.deferral());                                  // G-24
            log(r, "no permit, deferred");
            return false;
        }
        EngagementTemplateState state;
        try (Permit held = permit.get()) {
            state = ports.loader().load(r.firmId(), r.engagementId(), config.loaderHardTimeout());   // 3
        } catch (LoaderException e) {
            return onLoadFailure(d, r, e);                                                           // permit already released
        }
        breaker.onSuccess();
        crashHook.at(CrashHook.Point.AFTER_LOAD);
        String eventId = ObservationIds.of(state, r.requestId());
        ports.ledger().recordObservation(r.engagementId(), state, eventId);                         // 4
        crashHook.at(CrashHook.Point.AFTER_RECORD);
        publishAndFinish(d, r, state, eventId, List.of(r.jobId()));
        return true;
    }

    /**
     * Failure classification (§C, §D rows 11–13, G-16). Every branch first releases the claim. Throttled and
     * Transient count towards the breaker and requeue with backoff (a new message, so the receive count stays
     * reserved for real failures); only Transient increments {@code attempt}, and its 8th occurrence
     * dead-letters. NotFound and Permanent dead-letter at once and do not count towards the breaker.
     */
    private boolean onLoadFailure(Delivery<LoadRequest> d, LoadRequest r, LoaderException e) {
        ports.ledger().release(r.engagementId(), workerId);                                         // G-21
        switch (e) {
            case LoaderException.Throttled t -> {
                breaker.onFailure();
                ports.queue().requeueWithDelay(d, backoff.retry(r.attempt(), t.retryAfter()));
                log(r, "throttled, requeued");
                return true;
            }
            case LoaderException.Transient t -> {
                breaker.onFailure();
                if (r.attempt() + 1 >= config.maxTransientAttempts()) {
                    deadLetter(d, r, FailureReason.TRANSIENT_EXHAUSTED);
                } else {
                    ports.queue().requeueWithDelay(d, r.withNextAttempt(), backoff.retry(r.attempt(), Duration.ZERO));
                    log(r, "transient, requeued");
                }
                return true;
            }
            case LoaderException.NotFound n -> {
                deadLetter(d, r, FailureReason.NOT_FOUND);                                          // §D row 13
                return false;                                                                        // not a capacity signal (G-03)
            }
            case LoaderException.Permanent p -> {
                deadLetter(d, r, FailureReason.PERMANENT);
                return false;
            }
        }
    }

    /** Dead-letter branch (design §3): publish LoadFailed → outcome DLQ → dead-letter (acts as the ack). */
    private void deadLetter(Delivery<LoadRequest> d, LoadRequest r, FailureReason reason) {
        ports.publisher().publish(new EngagementLoadFailed(ObservationIds.failure(r.engagementId(), r.requestId()),
                r.firmId(), r.engagementId(), reason, r.jobId(), r.correlationId(), SCHEMA_VERSION, r.region()));
        ports.jobs().recordOutcome(r.jobId(), r.engagementId(), Outcome.DLQ);
        ports.queue().deadLetter(d, reason.name());
        LOG.log(WARNING, "dead-lettered {0}: correlationId={1} jobId={2} engagementId={3}",
                reason, r.correlationId(), r.jobId(), r.engagementId());
    }

    private void publishAndFinish(Delivery<LoadRequest> d, LoadRequest r, EngagementTemplateState state,
                                  String eventId, List<String> jobIds) {
        ports.publisher().publish(new EngagementStateObserved(eventId, SCHEMA_VERSION, r.region(), r.firmId(),
                state, jobIds, r.correlationId(), state.engagementSeq().isPresent()));               // 5
        crashHook.at(CrashHook.Point.AFTER_PUBLISH);
        ports.ledger().markPublished(r.engagementId(), eventId);                                    // 6
        ports.jobs().recordOutcome(r.jobId(), r.engagementId(), Outcome.DONE);                      // 7
        ports.queue().ack(d);                                                                        // 8
        log(r, "done");
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
