# 08 — Executor core and step order

- **Epic:** E3 Executor core
- **Priority:** Must
- **Status:** To do
- **Depends on:** 04, 05, 06, 07
- **Contract refs:** §B, §C step order and flow, §D rows 4, 10
- **Contract tests:** E1, E2
- **Checkpoint after:** None

## User story
As the platform, I want an executor that claims, loads and publishes in the contract's order on virtual threads, so that loader capacity is respected and duplicate work coalesces.

## Context
Covers FR-02 – FR-04, FR-06, FR-09, FR-13, FR-14, FR-16, NFR-01. Applies G-05, G-06, G-15, G-19, G-21, G-24. Single lane only here; weighting comes in story 13.

## Acceptance criteria
- AC1 — Given a `Claimed` request and an OK load, Then calls happen in the order claim → permit → load → `recordObservation` → publish → `markPublished` → `recordOutcome(DONE)` → ack (→ FR-09).
- AC2 — Given N executors, 10k requests and C permits, Then peak concurrent loader calls ≤ C and peak per firm per task ≤ cap; permits in use end at 0 (→ FR-13, FR-14, E1).
- AC3 — Given duplicate and cross-version requests for one engagement within W, Then exactly one loader call; a duplicate seen mid-load is requeued until the lease ends (G-05) and then coalesces; every job gets an outcome (→ FR-03, FR-04, E2).
- AC4 — Given no permit after a claim, Then the claim is released and the message is requeued with delay, not acked (→ FR-06).
- AC5 — Given an observation without `engagementSeq`, Then the event has `seqKnown=false` and the fallback `eventId` (→ FR-16).
- AC6 — Given an observation of a different template, Then it is still published and the outcome is DONE (→ EC-17).

## Tasks
- [ ] `LoadExecutor` (receive loop, per-message handler on virtual threads), `ExecutorFactory` wiring.
- [ ] Multi-instance test harness.
- [ ] `ContractE01CapacityInvariantTest`, `ContractE02CoalescingTest`.

## Out of scope
- Crash recovery (09), errors and breaker (10, 11), heartbeat (12), lane weights (13).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
