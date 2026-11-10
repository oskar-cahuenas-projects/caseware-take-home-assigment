# 05 — In-memory load ledger

- **Epic:** E2 Adapters & test kit
- **Priority:** Must
- **Status:** To do
- **Depends on:** 03
- **Contract refs:** §B LoadLedger/Claim, §D rows 4–6, §F (ledger as outbox)
- **Contract tests:** groundwork E2, E3
- **Checkpoint after:** None

## User story
As the executor, I want an atomic ledger keyed by engagement, so that duplicate requests coalesce and observations survive a crash before publish.

## Context
Covers FR-21, FR-03, FR-04, FR-16. Applies G-04, G-06, G-14, G-19, G-21.

## Acceptance criteria
- AC1 — Given no entry, When `claim` is called, Then `Claimed(leaseUntil)` and the job ID is attached (→ FR-21).
- AC2 — Given a live lease held by another worker, Then `InFlightElsewhere(owner, leaseUntil)`; after lease expiry, `Claimed` (→ FR-04).
- AC3 — Given `DONE` with `observedAt` within W and `observedSeq ≥ knownSeq`, Then `AlreadyFresh`; outside W or lower seq, `Claimed` (→ FR-03, G-19).
- AC4 — Given `recordObservation`, Then state is `PENDING_PUBLISH` and any later `claim` returns `PendingPublish(state, eventId, jobIds)` regardless of lease (→ FR-05, G-19 row 1).
- AC5 — Given `markPublished(eng, eventId)` with the stored `eventId`, Then `DONE`; a repeated or stale call is a no-op (→ FR-21, G-06).
- AC6 — Given A1 false (no seq), Then freshness uses `observedAt` only (→ FR-16).
- AC7 — Given `release` by the lease owner, Then the next `claim` returns `Claimed`; release by a non-owner is a no-op (→ G-21).
- AC8 — Given 50 threads claiming one engagement concurrently, Then exactly one `Claimed` (→ FR-21).

## Tasks
- [ ] `InMemoryLoadLedger` with `ConcurrentHashMap.compute`.
- [ ] Unit + concurrency tests.

## Out of scope
- Ledger sweeper for abandoned `PENDING_PUBLISH` (reconciliation, A9).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
