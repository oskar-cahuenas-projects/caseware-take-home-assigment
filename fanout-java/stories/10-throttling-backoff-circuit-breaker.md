# 10 — Throttling, backoff and circuit breaker

- **Epic:** E4 Resilience
- **Priority:** Must
- **Status:** Done
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
- [x] `Backoff` (strategy), `CircuitBreaker` (state).
- [x] Error classification with exhaustive `switch`.
- [x] `ContractE04ThrottlingNoDlqTest`.

## Out of scope
- Permanent errors (11).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC → test: AC1 `ThrottlingTest#throttledRequeues`, `#retryAfterCapped`, `BackoffTest#retryAfterFloorAndCap`; AC2 `BackoffTest#fullJitterBounds`, `#seeded`, `ThrottlingTest#transientRetriesWithNextAttempt`; AC3 `ThrottlingTest#breakerStopsReceiving` (queue wrapper counts `receive` calls), `CircuitBreakerTest#opensAfterK`; AC4 `ThrottlingTest#halfOpenProbe`, `CircuitBreakerTest#probeSuccessCloses`, `#probeFailureReopens`, `#probeAbandoned`; AC5 `ContractE04ThrottlingNoDlqTest`; AC6 `ThrottlingTest#transientExhausted`, `#throttledNeverDeadLetters`. Affected classes (incl. E1–E3) 5/5 runs green.
- **Signature change (asked, approved):** `WorkQueue.requeueWithDelay(Delivery<T> d, T body, Duration delay)`, logged under G-16. The two-argument form now delegates to it with `d.body()`.
- `attempt` increments on `Transient` only (`withNextAttempt()`); `Throttled` requeues the same body, so throttling never spends the `Transient` budget. Throttled backoff still grows with the breaker and honours `retryAfter`. Logged in G-16.
- "DLQ after 8 `Transient`" = the 8th `Transient` failure dead-letters (the request with `attempt = 7`), matching EC-26.
- Dead-letter branch (shared with story 11): release claim → publish `EngagementLoadFailed` (`eventId = engagementId:failed:<requestId>`, via new `ObservationIds.failure`) → `recordOutcome(DLQ)` → `deadLetter(d, reason)`.
- `CircuitBreaker` (State, per task, G-27): `admit(wanted)` gates every receive and returns 1 flagged probe in HALF_OPEN. A probe that ends without a loader verdict (coalesced, deferred, NotFound, empty receive) calls `probeAbandoned()` so the next receive can probe. Failures arriving while OPEN (loads started before it opened) are ignored; they don't extend the cool-down.
- `Backoff` (Strategy) also owns the G-24 deferral and G-05 jitter, which moved out of `LoadExecutor`. `breakerState()` exposed for NFR-10.
- E4 design: 20 items, 429 until every item has been throttled ≥ `maxReceiveCount + 1` = 6 times, then recovery. The first version waited for 1,500 total calls and timed out, because the open breaker correctly limits calls to one probe per cool-down. Mutant (throttled message left for visibility-based redelivery) is detected: items reach the DLQ and the wait times out.
- Messages already received when the breaker opens are still processed (at most one batch per task). Not deferring them is a simplification; A7 covers in-flight loads.
- `NotFound`/`Permanent` keep the interim handling (release, no ack) until story 11 routes them to the dead-letter branch.
