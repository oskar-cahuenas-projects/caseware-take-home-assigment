# 02 — Domain model and ports

- **Epic:** E1 Domain & ports
- **Priority:** Must
- **Status:** To do
- **Depends on:** 01
- **Contract refs:** §A envelope, §B (all signatures)
- **Contract tests:** None
- **Checkpoint after:** None

## User story
As a developer, I want the contract §B records, sealed types and ports in code, so that the compiler enforces the contract and the failure classification.

## Context
Covers FR-16, FR-17, NFR-06. Applies approved gaps G-01, G-02, G-04, G-06, G-07, G-08, G-26 G-16, G-21, G-25 (`find` only).

Approved signature changes vs. contract §B: `EngagementTemplateState.engagementSeq` → `OptionalLong`; `EngagementStateObserved.seqKnown`; `LoadLedger.recordObservation(eng, state, eventId)`, `markPublished(eng, eventId)`, `release(eng, workerId)`; `LoadRequest.attempt`, `schemaVersion`, `region`; `schemaVersion`/`region` on `ApplyHeadBatch` and `EngagementLoadFailed`; `FailureReason.TRANSIENT_EXHAUSTED`; `ObservationPublisher`, `Permit`, `Scheduler` ports.

## Acceptance criteria
- AC1 — Given each record, When constructed with null or blank IDs or negative counts, Then the compact constructor throws (→ FR-17).
- AC2 — Given `LoaderException`, When switched over, Then the compiler requires the four permitted subtypes; `Throttled` carries `retryAfter` (→ FR-10, FR-11).
- AC3 — Given `Claim`, Then it is sealed over `Claimed`, `AlreadyFresh`, `InFlightElsewhere`, `PendingPublish` with the G-04 payloads.
- AC4 — Given `EngagementTemplateState` without a sequence, Then `engagementSeq` is an empty `OptionalLong` and the observation's `eventId` uses the G-06 fallback (→ FR-16).
- AC5 — Given the event records, Then they carry only IDs, versions and envelope fields (`schemaVersion`, `correlationId`, `region`) (→ FR-17, NFR-06).
- AC6 — Given `TemplateVersionEvent`, Then `jobId()` = `productId:branchId:versionId:graphRev` (→ FR-22).

## Tasks
- [ ] `domain`: all §B records/enums, `Outcome`, `FailureReason`, `DecisionRef`, `Page`, `Cursor`, `ProjectionStatus`, `FanoutJob`.
- [ ] `port`: `WorkQueue`, `Delivery`, `LoadLedger`, `ConcurrencyLimiter`, `Permit`, `FanoutJobStore`, `EngagementLoader`, `ObservationPublisher`, `CandidateSource`.
- [ ] Validation tests.

## Out of scope
- Any implementation of the ports.

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
