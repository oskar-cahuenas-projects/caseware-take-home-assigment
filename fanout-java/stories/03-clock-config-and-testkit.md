# 03 — Clock, config and test kit

- **Epic:** E2 Adapters & test kit
- **Priority:** Must
- **Status:** Done
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
- [x] `Scheduler` port + `VirtualThreadScheduler` (adapter.memory) + `ManualScheduler` (test kit).
- [x] `ExecutorConfig` with `production()` and `forTests()`.
- [x] Test kit: `MutableClock`, `Await`.

## Out of scope
- Fake loader and crash injector (story 07).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC → test: AC1 `MutableClockTest`; AC2 `ManualSchedulerTest`; AC3 `ExecutorConfigTest#productionDefaults`, `#gapDefaults`; AC4 `ExecutorConfigTest#rejects*`; AC5 `AwaitTest#failsWithClearMessage`. Concurrency classes (`VirtualThreadSchedulerTest`, `MutableClockTest`, `ManualSchedulerTest`, `AwaitTest`) passed 5/5 runs.
- `ExecutorConfig` (package `executor`) is one record with 19 components and a copy-and-override `Builder` (used for G-18's E7 profile and per-test C/N). Derived: `permitsPerTask() = ⌊C/N⌋`, `firmCap() = max(1, ⌊0.2·⌊C/N⌋⌋)`. Production C = 50, N = 2 (3.3 low scenario, A3/A4).
- Values not in the contract: `receiveWait` 20 s (SQS long-poll max) [A 90%]; `maxReceiveBatch` 10 (SQS max, G-24). Everything else cites §C or a gap in the source.
- `forTests()` = production with every duration divided by 1000 (1 s → 1 ms); counts and weights unchanged. W becomes 86.4 s, which is longer than any test run, so coalescing tests stay valid.
- `Scheduler.Cancellable` is nested in the port. `VirtualThreadScheduler` uses one timer thread and runs each firing on a new virtual thread, so a throwing heartbeat does not cancel the fixed-rate schedule. It uses real time and must be paired with the system clock.
- `ManualScheduler.runDue()` runs on the calling thread; a fixed-rate task fires once per elapsed period (catch-up), which is what a heartbeat test advancing several minutes expects.
- Added beyond the card: `MainCodeTimeSourcesTest` scans `src/main` for `Instant.now(`, `System.currentTimeMillis(`, `System.nanoTime(`, `Thread.sleep(` (NFR-08). Verified it fails on a planted `Instant.now()`. Story 16's grep check is now this test.
