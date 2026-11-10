# 11 — Permanent errors and DLQ

- **Epic:** E4 Resilience
- **Priority:** Must
- **Status:** Done
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
- [x] Failure branch in `LoadExecutor`.
- [x] `ContractE06PermanentErrorTest`; poison-message test.

## Out of scope
- DLQ redrive tooling.

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC → test: AC1 `PermanentErrorTest#deadLetterOrder` (NotFound and Permanent; exact order claim, permit, load, permit.close, release, publishFailed, outcome:DLQ, deadLetter), `ContractE06PermanentErrorTest#deadLettersImmediately`; AC2 `ContractE06PermanentErrorTest#mixedJobClosesOnRule` (DONE + COALESCED + 2 DLQ; `PLANNED` at 3/4, `COMPLETE` at 4/4); AC3 `PoisonMessageTest`. Also `PermanentErrorTest#permanentFailuresDoNotTripBreaker` (G-03). 5/5 runs green.
- Executor change is the two `NotFound`/`Permanent` branches only; they reuse story 10's dead-letter path (release → `EngagementLoadFailed` → `recordOutcome(DLQ)` → `deadLetter(d, reason)`). No interim handling remains in `LoadExecutor`.
- The new tests were red before the change, except `PoisonMessageTest`: G-23 behaviour (no ack, claim and permit released, queue DLQ after 5 receives) already came from story 08's handler; this story pins it.
- G-23 limitation confirmed by test: a poison message produces no `LoadFailed` event and no job outcome, so its job stays open until reconciliation (A9).
- Refactor: the recording port decorators moved from `LoadExecutorTest` to a shared test class `executor/RecordingPorts`.
