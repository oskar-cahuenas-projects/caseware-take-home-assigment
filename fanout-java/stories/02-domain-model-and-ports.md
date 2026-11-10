# 02 — Domain model and ports

- **Epic:** E1 Domain & ports
- **Priority:** Must
- **Status:** Done
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
- [x] `domain`: all §B records/enums, `Outcome`, `FailureReason`, `DecisionRef`, `Page`, `Cursor`, `ProjectionStatus`, `FanoutJob`.
- [x] `port`: `WorkQueue`, `Delivery`, `LoadLedger`, `ConcurrencyLimiter`, `Permit`, `FanoutJobStore`, `EngagementLoader`, `ObservationPublisher`, `CandidateSource`.
- [x] Validation tests.

## Out of scope
- Any implementation of the ports.

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC → test: AC1 `RecordValidationTest` (26 cases); AC2/AC3 `SealedHierarchyTest` (exhaustive `switch` without `default` + `getPermittedSubclasses()`); AC4 `DomainBehaviourTest#eventIdFallbackWhenA1False`; AC5 `EventPayloadPolicyTest` (reflection: envelope present; only ID/version/enum/time components; any new non-`*Id` string fails until reviewed); AC6 `DomainBehaviourTest#jobIdIsDeterministic`.
- `Scheduler` port deferred to story 03, whose card owns it (listed here only as an approved signature change).
- `Claim` variants and `LoaderException` subtypes are nested types (`Claim.Claimed`, `LoaderException.Throttled`); simple names match §B. `LoaderException` is `abstract sealed` so pattern `switch` is exhaustive over the four subtypes alone. Both live in `port`, next to the port that returns/throws them (design §1).
- `Throttled.retryAfter` is a non-negative `Duration`; `Duration.ZERO` means no server hint. `Transient` takes an optional cause (timeout).
- `EngagementStateObserved` rejects `seqKnown` that disagrees with `state.engagementSeq()`, and empty `jobIds`.
- `Cursor.START` added so `CandidateSource.find` never takes `null` (no signature change). `FanoutJob.loadSideClosed()` encodes the G-07 close rule; it does not enforce `done + dlq ≤ expected`, because outcomes can arrive before the Planner saves the page that counted them.
- Limitation: `LoadRequest.templateId` stays a required `String` (§B). Null-template backfill rows (A2) would need `Optional<String>`; out of scope while only the Planner produces requests.
- Build: `-Xlint:all,-serial` (exceptions are never serialized).
