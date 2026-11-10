# 15 — Tracking Service consumer contract test

- **Epic:** E6 Planner (stretch)
- **Priority:** Should
- **Status:** Done
- **Depends on:** 08
- **Contract refs:** §A I6, §D rows 7, 8, 10
- **Contract tests:** E10
- **Checkpoint after:** None

## User story
As the Tracking Service team, I want a consumer-driven contract test on the events the executor publishes, so that we can rely on `eventId` dedupe and the sequence guard.

## Context
Covers FR-26. Applies G-06 (`seqKnown=false` fills `UNKNOWN` only).

## Acceptance criteria
- AC1 — Given an observation with a lower `engagementSeq` than stored, Then the fake consumer ignores it (→ FR-26, E10).
- AC2 — Given a duplicate `eventId`, Then it is a no-op (→ FR-26, E10).
- AC3 — Given `seqKnown=false`, Then only `UNKNOWN` fields are filled (→ FR-16).
- AC4 — Given events produced by a real executor run (not hand-built), Then they satisfy AC1–AC3.

## Tasks
- [x] `FakeTrackingServiceConsumer` (test kit).
- [x] `ContractE10TrackingServiceConsumerTest`.

## Out of scope
- Head computation and projection writes.

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC → test (`ContractE10TrackingServiceConsumerTest`): AC1 `Rules#lowerSeqIgnored`; AC2 `Rules#duplicateEventIdNoOp`; AC3 `Rules#seqUnknownFillsUnknownOnly`; AC4 `FromExecutor#crashDuplicateIsNoOp` (crash after publish → republish with the same `eventId`), `#lowerSeqFromLaterLoadIgnored`, `#a1FalseFillsUnknownOnly`, `#loadFailedDeduped`. Also §D row 10, `Rules#differentTemplateApplied`.
- Test-only story: no main code changed. The tests were red only at compile time (the fake is new); the executor already produced conforming events.
- `FakeTrackingServiceConsumer` models one row per engagement, with fields `Optional` (empty = `UNKNOWN`) plus a sequence, and a seen-`eventId` set shared by both event types. The sequence guard is strict (`seq ≤ stored` → stale). `seqKnown=false` never changes the stored sequence.
- Deterministic tests only; no concurrency, so the 5× rule doesn't apply.
