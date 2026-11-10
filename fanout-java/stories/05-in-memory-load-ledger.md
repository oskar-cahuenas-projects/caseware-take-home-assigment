# 05 — In-memory load ledger

- **Epic:** E2 Adapters & test kit
- **Priority:** Must
- **Status:** Done
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
- [x] `InMemoryLoadLedger` with `ConcurrentHashMap.compute`.
- [x] Unit + concurrency tests.

## Out of scope
- Ledger sweeper for abandoned `PENDING_PUBLISH` (reconciliation, A9).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC → test (`InMemoryLoadLedgerTest`): AC1 `claimNewEntry`; AC2 `inFlightElsewhereThenClaimedAfterExpiry`; AC3 `alreadyFresh`, `staleByTime`, `staleBySequence`; AC4 `pendingPublishWinsOverLease`; AC5 `markPublished`; AC6 `freshnessWithoutSequence`; AC7 `release`, `releaseKeepsOutbox`; AC8 `concurrentClaimsExactlyOneWinner` (50 virtual threads behind a start latch). 5/5 runs green; AC8 verified to fail 3/3 with a non-atomic read-then-write `claim`.
- Each operation is one `ConcurrentHashMap.compute` over an immutable `Entry` record, modelling a DynamoDB conditional write.
- Freshness (G-19 row 2) uses the loader's `state.observedAt()`, not the ledger clock at record time, and `observedAt + W > now` (strict). With no sequence (A1 false) only the time check applies.
- Job IDs: a `PendingPublish` claim attaches the caller's `jobId` (G-19 row 1). A `Claimed` over a `LEASED` entry (expired, or the same worker) keeps the attached IDs and adds the new one; over an absent or `DONE` entry it starts a new list. `recordObservation` keeps the list, so the published event names every attached job.
- `recordObservation` has no owner check (the §B/G-06 signature has no `workerId`); it always writes `PENDING_PUBLISH`. G-14's lease ≥ hard timeout + margin keeps a late writer from racing a new owner in practice. It rejects a state whose `engagementId` differs from the key.
- `release` only drops a `LEASED` entry owned by the caller; a recorded observation is never dropped (`releaseKeepsOutbox`).
- Added `status(engagementId)` (public) for tests and observability; not a port method.
