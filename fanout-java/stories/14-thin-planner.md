# 14 — Thin Planner

- **Epic:** E6 Planner (stretch)
- **Priority:** Should
- **Status:** To do
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
- [ ] `Planner`, `InMemoryCandidateSource`, in-memory command sink.
- [ ] `FanoutJobStore` additions per G-25.
- [ ] `ContractE08JobIdempotencyTest` (event half), `ContractE09SupersessionTest`.

## Out of scope
- Real Tracking Service query API, GSI lag (§A I2).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
