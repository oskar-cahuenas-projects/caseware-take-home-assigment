# 06 — Concurrency limiter

- **Epic:** E2 Adapters & test kit
- **Priority:** Must
- **Status:** To do
- **Depends on:** 02
- **Contract refs:** §B ConcurrencyLimiter, §C lanes and fairness, §F static permit split
- **Contract tests:** groundwork E1
- **Checkpoint after:** None

## User story
As the executor, I want a non-blocking limiter with a task budget and a per-firm cap, so that loader concurrency never exceeds C and no firm takes all permits.

## Context
Covers FR-13, FR-14, FR-06. Applies G-02, G-15.

## Acceptance criteria
- AC1 — Given `⌊C/N⌋` permits held, When `tryAcquire` is called, Then `Optional.empty()` (→ FR-13).
- AC2 — Given a firm at its cap `max(1, ⌊0.2 × C/N⌋)`, Then `tryAcquire(firm)` is empty while other firms still acquire (→ FR-13, EC-12).
- AC3 — Given a `Permit` closed twice, Then it is released once (→ FR-14, G-02).
- AC4 — Given 200 virtual threads hammering `tryAcquire`/`close`, Then in-use never exceeds the budget or a firm's cap and ends at 0 (→ FR-13, FR-14).
- AC5 — Given `C=50, N=4`, Then 12 permits per task and firm cap 2; given `C=10, N=4`, firm cap 1 (→ EC-11, EC-12).

## Tasks
- [ ] `SemaphoreConcurrencyLimiter` with per-firm counters; in-use counter (NFR-10).
- [ ] Unit + concurrency tests.

## Out of scope
- Global (cross-task) limiter (§F upgrade path).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
