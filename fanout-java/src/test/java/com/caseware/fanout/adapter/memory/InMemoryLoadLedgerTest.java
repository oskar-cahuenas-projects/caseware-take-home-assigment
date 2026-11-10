package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryLoadLedger.Status;
import com.caseware.fanout.domain.EngagementTemplateState;
import com.caseware.fanout.port.Claim;
import com.caseware.fanout.testkit.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class InMemoryLoadLedgerTest {

    private static final String ENG = "eng-1";
    private static final Duration LEASE = Duration.ofMinutes(6);
    private static final Duration W = Duration.ofHours(24);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
    private final InMemoryLoadLedger ledger = new InMemoryLoadLedger(clock);

    private Claim claim(String job, String worker, long knownSeq) {
        return ledger.claim(ENG, job, worker, LEASE, W, knownSeq);
    }

    private EngagementTemplateState observed(OptionalLong seq) {
        return new EngagementTemplateState(ENG, "tpl-1", "br-1", "v7", List.of(), seq, clock.instant());
    }

    /** Claim, record and publish an observation, leaving the entry DONE. */
    private void completeLoad(OptionalLong seq, String eventId) {
        assertThat(claim("job-0", "w-0", 0)).isInstanceOf(Claim.Claimed.class);
        ledger.recordObservation(ENG, observed(seq), eventId);
        ledger.markPublished(ENG, eventId);
        assertThat(ledger.status(ENG)).contains(Status.DONE);
    }

    @Test
    @DisplayName("AC1: no entry → Claimed(now + lease), job attached")
    void claimNewEntry() {
        Claim c = claim("job-1", "w-1", 0);

        assertThat(c).isEqualTo(new Claim.Claimed(clock.instant().plus(LEASE)));
        assertThat(ledger.status(ENG)).contains(Status.LEASED);
        ledger.recordObservation(ENG, observed(OptionalLong.of(1)), "eng-1:1");
        assertThat(claim("job-2", "w-2", 0)).isInstanceOfSatisfying(Claim.PendingPublish.class,
                p -> assertThat(p.jobIds()).containsExactly("job-1", "job-2"));
    }

    @Test
    @DisplayName("AC2: live lease of another worker → InFlightElsewhere; after expiry → Claimed")
    void inFlightElsewhereThenClaimedAfterExpiry() {
        Instant leaseUntil = clock.instant().plus(LEASE);
        claim("job-1", "w-1", 0);

        assertThat(claim("job-2", "w-2", 0)).isEqualTo(new Claim.InFlightElsewhere("w-1", leaseUntil));

        clock.advance(LEASE);
        assertThat(claim("job-2", "w-2", 0)).isEqualTo(new Claim.Claimed(clock.instant().plus(LEASE)));
    }

    @Test
    @DisplayName("G-19 row 4: the lease owner claiming again gets Claimed with a renewed lease")
    void sameWorkerReclaims() {
        claim("job-1", "w-1", 0);
        clock.advance(Duration.ofMinutes(1));
        assertThat(claim("job-1", "w-1", 0)).isEqualTo(new Claim.Claimed(clock.instant().plus(LEASE)));
    }

    @Test
    @DisplayName("AC3: DONE within W and observedSeq ≥ knownSeq → AlreadyFresh")
    void alreadyFresh() {
        Instant observedAt = clock.instant();
        completeLoad(OptionalLong.of(7), "eng-1:7");
        clock.advance(W.minusSeconds(1));

        assertThat(claim("job-2", "w-2", 7)).isEqualTo(new Claim.AlreadyFresh(OptionalLong.of(7), observedAt));
    }

    @Test
    @DisplayName("AC3: DONE but outside W → Claimed")
    void staleByTime() {
        completeLoad(OptionalLong.of(7), "eng-1:7");
        clock.advance(W);

        assertThat(claim("job-2", "w-2", 7)).isInstanceOf(Claim.Claimed.class);
    }

    @Test
    @DisplayName("AC3: DONE within W but observedSeq < knownSeq → Claimed")
    void staleBySequence() {
        completeLoad(OptionalLong.of(7), "eng-1:7");

        assertThat(claim("job-2", "w-2", 8)).isInstanceOf(Claim.Claimed.class);
    }

    @Test
    @DisplayName("AC4: after recordObservation every claim returns PendingPublish, even with a live lease")
    void pendingPublishWinsOverLease() {
        claim("job-1", "w-1", 0);
        EngagementTemplateState s = observed(OptionalLong.of(3));
        ledger.recordObservation(ENG, s, "eng-1:3");

        assertThat(ledger.status(ENG)).contains(Status.PENDING_PUBLISH);
        assertThat(claim("job-2", "w-2", 99)).isEqualTo(new Claim.PendingPublish(s, "eng-1:3", List.of("job-1", "job-2")));
        clock.advance(W.multipliedBy(2));
        assertThat(claim("job-2", "w-3", 0)).isInstanceOfSatisfying(Claim.PendingPublish.class,
                p -> assertThat(p.jobIds()).containsExactly("job-1", "job-2"));
    }

    @Test
    @DisplayName("AC5: markPublished with the stored eventId → DONE; repeated and stale calls are no-ops")
    void markPublished() {
        claim("job-1", "w-1", 0);
        ledger.recordObservation(ENG, observed(OptionalLong.of(3)), "eng-1:3");

        ledger.markPublished(ENG, "eng-1:2");                  // stale eventId
        assertThat(ledger.status(ENG)).contains(Status.PENDING_PUBLISH);

        ledger.markPublished(ENG, "eng-1:3");
        ledger.markPublished(ENG, "eng-1:3");                  // repeat
        assertThat(ledger.status(ENG)).contains(Status.DONE);

        ledger.markPublished("eng-unknown", "x");              // no entry
        assertThat(ledger.status("eng-unknown")).isEmpty();
    }

    @Test
    @DisplayName("AC6: A1 false → freshness by observedAt only, whatever knownSeq says")
    void freshnessWithoutSequence() {
        Instant observedAt = clock.instant();
        completeLoad(OptionalLong.empty(), "eng-1:obs:req-1");

        assertThat(claim("job-2", "w-2", 1_000)).isEqualTo(new Claim.AlreadyFresh(OptionalLong.empty(), observedAt));
        clock.advance(W);
        assertThat(claim("job-2", "w-2", 1_000)).isInstanceOf(Claim.Claimed.class);
    }

    @Test
    @DisplayName("AC7: release by the owner frees the entry; by a non-owner it is a no-op")
    void release() {
        claim("job-1", "w-1", 0);

        ledger.release(ENG, "w-2");
        assertThat(claim("job-2", "w-2", 0)).isInstanceOf(Claim.InFlightElsewhere.class);

        ledger.release(ENG, "w-1");
        assertThat(ledger.status(ENG)).isEmpty();
        assertThat(claim("job-2", "w-2", 0)).isInstanceOf(Claim.Claimed.class);
    }

    @Test
    @DisplayName("AC7: release never drops a recorded observation")
    void releaseKeepsOutbox() {
        claim("job-1", "w-1", 0);
        ledger.recordObservation(ENG, observed(OptionalLong.of(3)), "eng-1:3");

        ledger.release(ENG, "w-1");
        assertThat(ledger.status(ENG)).contains(Status.PENDING_PUBLISH);
    }

    @Test
    @DisplayName("recordObservation rejects a state for a different engagement")
    void recordRejectsMismatchedEngagement() {
        claim("job-1", "w-1", 0);
        assertThatThrownBy(() -> ledger.recordObservation("eng-2", observed(OptionalLong.of(1)), "eng-1:1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("invalid lease or freshness is rejected")
    void invalidArguments() {
        assertThatThrownBy(() -> ledger.claim(ENG, "j", "w", Duration.ZERO, W, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.claim(ENG, "j", "w", LEASE, Duration.ofSeconds(-1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC8: 50 concurrent claims on one engagement → exactly one Claimed")
    void concurrentClaimsExactlyOneWinner() throws InterruptedException {
        int workers = 50;
        CountDownLatch start = new CountDownLatch(1);
        List<Claim> results = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            String worker = "w-" + i;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                results.add(ledger.claim(ENG, "job-" + worker, worker, LEASE, W, 0));
            }));
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }

        assertThat(results).hasSize(workers);
        assertThat(results).filteredOn(Claim.Claimed.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(Claim.InFlightElsewhere.class::isInstance).hasSize(workers - 1);
    }
}
