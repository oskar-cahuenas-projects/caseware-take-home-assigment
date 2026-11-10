# 09 — Crash recovery and outbox

- **Epic:** E3 Executor core
- **Priority:** Must
- **Status:** To do
- **Depends on:** 08
- **Contract refs:** §C step order, §D rows 5–7, §F ledger as outbox
- **Contract tests:** E3
- **Checkpoint after:** None

## User story
As the platform, I want the executor to converge after a crash at any step, so that no item is lost and at most one extra load happens per crash.

## Context
Covers FR-05, FR-15, NFR-02, NFR-03. Applies G-11, G-22.

## Acceptance criteria
- AC1 — Given a crash after claim, When lease and visibility expire, Then another instance loads it; total extra loads ≤ 1 (→ FR-15, E3).
- AC2 — Given a crash after load, Then one reload, item DONE (→ FR-15).
- AC3 — Given a crash after `recordObservation`, Then `PendingPublish` republishes with 0 extra loads (→ FR-05, E3).
- AC4 — Given a crash after publish, Then the redelivered message publishes a duplicate with the same `eventId` (or none) and the job counters stay exact (→ FR-15, E3, E8).
- AC5 — Given a publish failure, Then no ack; the redelivery republishes without reloading (→ G-22).

## Tasks
- [ ] Crash hooks at the four points.
- [ ] `ContractE03CrashInjectionTest` (parameterised over the four points).

## Out of scope
- Ledger sweeper (A9).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
