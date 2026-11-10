package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.Checks;
import com.caseware.fanout.domain.EngagementTemplateState;
import com.caseware.fanout.port.Claim;
import com.caseware.fanout.port.LoadLedger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DynamoDB-like ledger keyed by {@code engagementId}, doubling as the observation outbox.
 * Each operation is one {@link ConcurrentHashMap#compute} over an immutable entry, which models a
 * conditional write. Leases and freshness are evaluated lazily against the injected clock (G-10).
 * Thread-safe.
 */
public final class InMemoryLoadLedger implements LoadLedger {

    /** Entry lifecycle: {@code LEASED → PENDING_PUBLISH → DONE}. */
    public enum Status { LEASED, PENDING_PUBLISH, DONE }

    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public InMemoryLoadLedger(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** G-19 decision table, evaluated atomically, first match wins. */
    @Override
    public Claim claim(String engagementId, String jobId, String workerId,
                       Duration lease, Duration freshness, long knownSeq) {
        Checks.nonBlank(engagementId, "engagementId");
        Checks.nonBlank(jobId, "jobId");
        Checks.nonBlank(workerId, "workerId");
        requirePositive(lease, "lease");
        requirePositive(freshness, "freshness");
        Claim[] result = new Claim[1];
        entries.compute(engagementId, (id, e) -> {
            Instant now = clock.instant();
            if (e != null && e.status == Status.PENDING_PUBLISH) {                         // row 1
                Entry attached = e.withJob(jobId);
                result[0] = new Claim.PendingPublish(attached.state, attached.eventId, attached.jobIds);
                return attached;
            }
            if (e != null && e.status == Status.DONE && isFresh(e.state, now, freshness, knownSeq)) {  // row 2
                result[0] = new Claim.AlreadyFresh(e.state.engagementSeq(), e.state.observedAt());
                return e;
            }
            if (e != null && e.status == Status.LEASED                                     // row 3
                    && !e.owner.equals(workerId) && e.leaseUntil.isAfter(now)) {
                result[0] = new Claim.InFlightElsewhere(e.owner, e.leaseUntil);
                return e;
            }
            Instant until = now.plus(lease);                                              // row 4
            List<String> jobs = e != null && e.status == Status.LEASED ? e.withJob(jobId).jobIds : List.of(jobId);
            result[0] = new Claim.Claimed(until);
            return Entry.leased(workerId, until, jobs);
        });
        return result[0];
    }

    /** Stores the observation as the outbox entry. Attached job IDs are kept. */
    @Override
    public void recordObservation(String engagementId, EngagementTemplateState s, String eventId) {
        Objects.requireNonNull(s, "state");
        Checks.nonBlank(eventId, "eventId");
        if (!s.engagementId().equals(engagementId)) {
            throw new IllegalArgumentException("state is for " + s.engagementId() + ", not " + engagementId);
        }
        entries.compute(engagementId, (id, e) ->
                Entry.pending(s, eventId, e == null ? List.of() : e.jobIds));
    }

    /** {@code PENDING_PUBLISH → DONE} only if the entry still holds {@code eventId}; otherwise a no-op. */
    @Override
    public void markPublished(String engagementId, String eventId) {
        entries.computeIfPresent(engagementId, (id, e) ->
                e.status == Status.PENDING_PUBLISH && e.eventId.equals(eventId) ? e.published() : e);
    }

    /** Drops a {@code LEASED} entry owned by {@code workerId} (G-21); any other state is left untouched. */
    @Override
    public void release(String engagementId, String workerId) {
        entries.computeIfPresent(engagementId, (id, e) ->
                e.status == Status.LEASED && e.owner.equals(workerId) ? null : e);
    }

    /** Current status of an entry, for tests and observability. */
    public Optional<Status> status(String engagementId) {
        return Optional.ofNullable(entries.get(engagementId)).map(e -> e.status);
    }

    /** Within W, and the observed sequence is not behind the caller's; an unknown sequence is judged by time only (G-06). */
    private static boolean isFresh(EngagementTemplateState s, Instant now, Duration freshness, long knownSeq) {
        boolean withinWindow = s.observedAt().plus(freshness).isAfter(now);
        boolean seqOk = s.engagementSeq().isEmpty() || s.engagementSeq().getAsLong() >= knownSeq;
        return withinWindow && seqOk;
    }

    private static void requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
    }

    /** Immutable ledger item. {@code owner}/{@code leaseUntil} are set only when LEASED; {@code state}/{@code eventId} otherwise. */
    private record Entry(Status status, String owner, Instant leaseUntil,
                         EngagementTemplateState state, String eventId, List<String> jobIds) {

        static Entry leased(String owner, Instant until, List<String> jobIds) {
            return new Entry(Status.LEASED, owner, until, null, null, List.copyOf(jobIds));
        }

        static Entry pending(EngagementTemplateState s, String eventId, List<String> jobIds) {
            return new Entry(Status.PENDING_PUBLISH, null, null, s, eventId, List.copyOf(jobIds));
        }

        Entry published() {
            return new Entry(Status.DONE, null, null, state, eventId, jobIds);
        }

        Entry withJob(String jobId) {
            if (jobIds.contains(jobId)) {
                return this;
            }
            List<String> jobs = new ArrayList<>(jobIds);
            jobs.add(jobId);
            return new Entry(status, owner, leaseUntil, state, eventId, List.copyOf(jobs));
        }
    }
}
