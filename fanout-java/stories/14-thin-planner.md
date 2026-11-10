# 14 — Thin Planner

- **Epic:** E6 Planner (stretch)
- **Priority:** Should
- **Status:** Done
- **Depends on:** 07, 13
- **Contract refs:** §A I1–I4, §B TemplateVersionEvent/CandidateSource/ApplyHeadBatch, §C Planner, §D rows 1–3, 9
- **Contract tests:** E8 (duplicate events), E9
- **Checkpoint after:** None

## User story
As the platform, I want one publish event turned into flag commands and load requests exactly once per job, so that known rows flag fast and unknown rows get loaded.

## Context
Covers FR-22 – FR-25. Applies G-08, G-25.

## Acceptance criteria
- AC1 — Given a duplicate publish event, Then one job and one planning pass (→ FR-22, E8).
- AC2 — Given two planners on one job, Then one holds `plannerLease` and proceeds (→ FR-23).
- AC3 — Given candidates, Then known rows go out as `ApplyHeadBatch` of ≤ 100 IDs and `UNKNOWN`/`UNVERIFIED` rows as PUBLISH-lane `LoadRequest`s; `loadsExpected` matches; cursor saved after sends (→ FR-24).
- AC4 — Given a planner crash mid-page, Then a restart resumes from the cursor and re-sends at most one page (→ FR-24, §D row 2).
- AC5 — Given a withdraw at a higher `graphRev`, Then the old planner stops at the next page, its job is `SUPERSEDED`, and its queued loads still complete (→ FR-25, E9).

## Tasks
- [x] `Planner`, `InMemoryCandidateSource`, in-memory command sink.
- [x] `FanoutJobStore` additions per G-25.
- [x] `ContractE08JobIdempotencyTest` (event half), `ContractE09SupersessionTest`.

## Out of scope
- Real Tracking Service query API, GSI lag (§A I2).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC → test: AC1 `ContractE08JobIdempotencyTest#duplicateEvents` (same and different planner); AC2 `PlannerTest#oneLeaseHolder`; AC3 `PlannerTest#splitsKnownAndUnknown` (250 known → 100/100/50; 50 UNKNOWN + UNVERIFIED → PUBLISH loads; counters), `#cursorSavedAfterSends`; AC4 `PlannerTest#crashResumesFromCursor` (page 2 re-sent, `loadsExpected` exact), `#crashAfterLastSave`; AC5 `ContractE09SupersessionTest` (withdraw mid-run: old job stops after page 1, `SUPERSEDED`, its 5 loads complete; the new job's overlapping loads coalesce; late lower `graphRev`). Store units in `InMemoryFanoutJobStoreTest`. 5/5 runs green.
- **G-29 (new, flagged for owner review):** `MessageSink<T>` producer port (`InMemoryWorkQueue` implements it; tests use a list for I3); `FanoutJobStore.advanceGraphRev` as the write path for G-25's `latestGraphRev`; `Cursor.END` saved with the last page, so a crash before `markPlanned` doesn't re-count the last page; `InMemoryFanoutJobStore(Clock)` for lease expiry. G-25 signatures unchanged. `savePage`'s `flagBatchesSent` is an increment.
- `Planner.handle(event)` returns `PLANNED | DUPLICATE | LEASE_HELD | SUPERSEDED`. A send failure propagates (event not acked → redelivery resumes from the cursor). No receive loop over `template-events`: the thin Planner is driven by its caller.
- Deterministic `requestId = jobId:engagementId`, so a re-sent page produces the same request IDs (and the same A1-false fallback `eventId`).
- Withdraw and publish plan identically: the event creates a job at its `graphRev`; the Tracking Service recomputes the head from `ApplyHeadBatch.graphRev`.
- A `SUPERSEDED` job keeps counting outcomes but never becomes `COMPLETE` (terminal). Its loads still run and inform the newer job via coalescing.
- Out of scope, as on the card: A2 null-template rows → backfill lane; GSI lag.
