# 16 — Full test run and review

- **Epic:** E7 Validation & review
- **Priority:** Must
- **Status:** Done
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
- [x] 5 runs, timings.
- [x] Grep checks; review checklist.
- [x] Fix list (fixes made here only if small; otherwise reported).

## Out of scope
- New features.

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC1: 5 consecutive full `./mvnw -q verify` runs, all green, 217 test cases each; ≈ 10 s wall, Surefire total 7.6–8.0 s.
- AC2: no `Instant.now(`, `currentTimeMillis` or `Thread.sleep` in `src/main` (grep and `MainCodeTimeSourcesTest`).
- AC3: every FR/NFR has a test or a review check in §6.2, and every `Class#method` named there was checked to exist. §7 (§D coverage, 16 rows) and §8 (review) were added to `requirements.md`. The empty "Verified by" cells in §4/§5 point to §6.2.
- Small fixes made here: thread-safety statements added to the Javadoc of `LoadLedger`, `ObservationPublisher`, `EngagementLoader`, `CandidateSource`, `Permit`, `CrashHook`, `ExecutorPorts`, `PlannerConfig`, and the `domain`/`port` package docs (NFR-09). No behaviour change.
- Not fixed (reported in §8): §D row 12 reconciliation, NFR-10 coalesced counter, G-23 poison-message job closure, G-29 pending owner review.
