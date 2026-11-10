# 11 — Permanent errors and DLQ

- **Epic:** E4 Resilience
- **Priority:** Must
- **Status:** To do
- **Depends on:** 08
- **Contract refs:** §C flow (NotFound/Permanent branch), §D rows 13, 14, §B FanoutJob close rule
- **Contract tests:** E6
- **Checkpoint after:** None

## User story
As an operator, I want unloadable engagements to fail fast and visibly, so that jobs close and the Tracking Service can mark the row.

## Context
Covers FR-11, FR-18, FR-19. Applies G-07, G-23.

## Acceptance criteria
- AC1 — Given `NotFound` or `Permanent`, Then one loader call, then in order: claim released, `EngagementLoadFailed` published, `recordOutcome(DLQ)`, message dead-lettered (→ FR-11, E6).
- AC2 — Given a job whose items are a mix of DONE, COALESCED and DLQ, Then it closes exactly when `done + dlq = expected` (→ FR-19, E6).
- AC3 — Given a request whose processing throws an unexpected `RuntimeException`, Then it is not acked and reaches the DLQ after 5 receives; permits end at 0 (→ FR-18).

## Tasks
- [ ] Failure branch in `LoadExecutor`.
- [ ] `ContractE06PermanentErrorTest`; poison-message test.

## Out of scope
- DLQ redrive tooling.

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
