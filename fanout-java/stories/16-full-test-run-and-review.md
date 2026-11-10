# 16 — Full test run and review

- **Epic:** E7 Validation & review
- **Priority:** Must
- **Status:** To do
- **Depends on:** 13 (and 14, 15 unless Dropped)
- **Contract refs:** §D (all rows), §E (all tests)
- **Contract tests:** all
- **Checkpoint after:** None

## User story
As a reviewer, I want evidence that the contract is met, so that I can trust the implementation without reading every line.

## Context
Covers NFR-04, NFR-05, NFR-06, NFR-08, NFR-09.

## Acceptance criteria
- AC1 — Given the full suite, When run 5 times, Then 5/5 green and each run < 60 s (→ NFR-04, NFR-05).
- AC2 — Given main sources, Then no `Instant.now(`, `currentTimeMillis`, `Thread.sleep` (→ NFR-08).
- AC3 — Given `requirements.md`, Then the traceability matrix has a test for every FR/NFR, or a reason; a §D coverage table and a review section exist.

## Tasks
- [ ] 5 runs, timings.
- [ ] Grep checks; review checklist.
- [ ] Fix list (fixes made here only if small; otherwise reported).

## Out of scope
- New features.

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
