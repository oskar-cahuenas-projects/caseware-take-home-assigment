# 10 — Throttling, backoff and circuit breaker

- **Epic:** E4 Resilience
- **Priority:** Must
- **Status:** To do
- **Depends on:** 08
- **Contract refs:** §C flow (Throttled/Transient branch), §C circuit breaker, §D rows 11, 12
- **Contract tests:** E4
- **Checkpoint after:** None

## User story
As the owning team of the loader, I want the executor to back off and stop receiving when we throttle, so that our service recovers and no work is dead-lettered.

## Context
Covers FR-10, FR-12, NFR-02, NFR-10. Applies G-03, G-12, G-16, G-27.

## Acceptance criteria
- AC1 — Given `Throttled(retryAfter)`, Then permit released, claim released, `requeueWithDelay(max(backoff, retryAfter))` capped at 15 min (→ FR-10, EC-10).
- AC2 — Given backoff, Then delay = full jitter over `min(cap, base × 2^attempt)` using the seeded `Random` (→ FR-10, G-16).
- AC3 — Given K consecutive `Throttled`/`Transient`, Then the breaker opens and `receive` is not called until cool-down elapses on the clock (→ FR-12).
- AC4 — Given HALF_OPEN, Then exactly one probe; success closes, failure re-opens (→ FR-12, EC-16).
- AC5 — Given a sustained 429 period, Then 0 DLQ messages, and every item completes after throttling stops (→ FR-10, E4).
- AC6 — Given `Transient` at `attempt ≥ 8`, Then `EngagementLoadFailed(TRANSIENT_EXHAUSTED)`, outcome DLQ, dead-letter; `Throttled` never dead-letters; deferrals don't increment `attempt` (→ EC-26, G-16).

## Tasks
- [ ] `Backoff` (strategy), `CircuitBreaker` (state).
- [ ] Error classification with exhaustive `switch`.
- [ ] `ContractE04ThrottlingNoDlqTest`.

## Out of scope
- Permanent errors (11).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
