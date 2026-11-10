# 03 — Clock, config and test kit

- **Epic:** E2 Adapters & test kit
- **Priority:** Must
- **Status:** To do
- **Depends on:** 02
- **Contract refs:** §C heartbeat, §E preamble (deterministic Clock)
- **Contract tests:** None
- **Checkpoint after:** None

## User story
As a test author, I want a controllable clock, a scheduler and one config record, so that every time-based rule is deterministic in tests.

## Context
Covers NFR-04, NFR-08. Applies G-10, G-14, G-15, G-18. Per G-10, adapters evaluate expiry lazily against the clock; the `Scheduler` is used only for heartbeat and hard timeout. Two test modes: deterministic (`MutableClock` + `ManualScheduler`) and concurrency (system clock + `VirtualThreadScheduler`, ms-scaled config).

## Acceptance criteria
- AC1 — Given `MutableClock`, When advanced, Then `instant()` moves by exactly that amount and is thread-safe.
- AC2 — Given `ManualScheduler`, When the clock is advanced past a task's due time and `runDue()` is called, Then due tasks run in time order, once; cancelled tasks never run (→ G-10).
- AC3 — Given `ExecutorConfig.production()`, Then values equal contract defaults (visibility 3 min, heartbeat 60 s / +3 min, hard timeout 5 min, `maxReceiveCount` 5, W 24 h, weights 6:3:1, max delay 15 min).
- AC4 — Given an invalid config (lease < hard timeout + margin, heartbeat ≥ visibility, `C < N`), Then construction fails (→ G-14, G-15).
- AC5 — Given `Await.until(condition, timeout)`, When the condition never holds, Then it fails with a clear message without `Thread.sleep` in main code (→ NFR-08).

## Tasks
- [ ] `Scheduler` port + `VirtualThreadScheduler` (adapter.memory) + `ManualScheduler` (test kit).
- [ ] `ExecutorConfig` with `production()` and `forTests()`.
- [ ] Test kit: `MutableClock`, `Await`.

## Out of scope
- Fake loader and crash injector (story 07).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
