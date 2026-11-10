# 12 — Visibility heartbeat and hard timeout

- **Epic:** E4 Resilience
- **Priority:** Must
- **Status:** Done
- **Depends on:** 08
- **Contract refs:** §C visibility heartbeat
- **Contract tests:** E7
- **Checkpoint after:** None

## User story
As the platform, I want visibility extended while a load runs and a hard timeout on the call, so that slow loads are never processed twice and stuck loads don't hold permits forever.

## Context
Covers FR-07, FR-08. Applies G-10, G-18, G-28.

## Acceptance criteria
- AC1 — Given a load running 3 × visibility (config per G-18), Then the message is never delivered to another worker and the loader is called once (→ FR-08, E7).
- AC2 — Given the heartbeat interval, Then each tick calls `extendVisibility(d, 3 min)`; the heartbeat is cancelled on every exit path (→ FR-08, EC-27).
- AC3 — Given a load exceeding the hard timeout (timeout task fires on the `Scheduler` and interrupts the load), Then it is treated as `Transient`; the permit is released only when the loader call returns; permit and claim released, requeued (→ FR-07, EC-09).

## Tasks
- [x] `Heartbeat` on the `Scheduler`.
- [x] Hard-timeout enforcement per G-28.
- [x] `ContractE07HeartbeatTest`.

## Out of scope
- SQS 12-h visibility ceiling (not reachable with a 5-min hard timeout).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC → test: AC1 `ContractE07HeartbeatTest` (G-18 profile: visibility 3 min, heartbeat 60 s, hard timeout 10 min, lease 11 min; a second executor polls every 60 s for 9 min and receives nothing; loader called once); AC2 `HeartbeatTimeoutTest#heartbeatExtendsEveryInterval` (`extend:PT3M` per tick, none after the load returns), `#cancelledOnEveryExitPath` (OK, Throttled, Transient, NotFound, Permanent, RuntimeException), `#cancelledOnCrash`; AC3 `#hardTimeoutIsTransient`, `#lateResultDiscardedPermitHeldUntilReturn`. 5/5 runs green.
- `Heartbeat` and `HardTimeout` are package-private resources in the same try-with-resources as the `Permit`; they close in reverse order once the loader call returns, so the permit is released only when the call really ends (G-28, A8).
- `HardTimeout` interrupts only while armed, and `close()` clears an interrupt it delivered, so it cannot leak into steps 4–8 or into the next `pollOnce` on the same thread.
- A success that returns after the timeout fired is discarded as `Transient`. A loader *exception* after the timeout keeps its own classification (for example, a late `NotFound` still dead-letters). This is a deliberate simplification.
- Heartbeat covers step 3 only (§C "while the load runs"); steps 4–8 are fast in-memory writes well inside the 3-min visibility.
- Test kit: `FakeEngagementLoader.Step.stubborn()` (ignores interrupts); `RecordingQueue` now records `extend:<duration>`.
