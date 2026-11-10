# 09 — Crash recovery and outbox

- **Epic:** E3 Executor core
- **Priority:** Must
- **Status:** Done
- **Depends on:** 08
- **Contract refs:** §C step order, §D rows 5–7, §F ledger as outbox
- **Contract tests:** E3
- **Checkpoint after:** None

## User story
As the platform, I want the executor to converge after a crash at any step, so that no item is lost and at most one extra load happens per crash.

## Context
Covers FR-05, FR-15, NFR-02, NFR-03. Applies G-11, G-22.

## Acceptance criteria
- AC1 — Given a crash after claim, When lease and visibility expire, Then another instance loads it; total extra loads ≤ 1 (→ FR-15, E3).
- AC2 — Given a crash after load, Then one reload, item DONE (→ FR-15).
- AC3 — Given a crash after `recordObservation`, Then `PendingPublish` republishes with 0 extra loads (→ FR-05, E3).
- AC4 — Given a crash after publish, Then the redelivered message publishes a duplicate with the same `eventId` (or none) and the job counters stay exact (→ FR-15, E3, E8).
- AC5 — Given a publish failure, Then no ack; the redelivery republishes without reloading (→ G-22).

## Tasks
- [x] Crash hooks at the four points.
- [x] `ContractE03CrashInjectionTest` (parameterised over the four points).

## Out of scope
- Ledger sweeper (A9).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC → test: AC1–AC4 `ContractE03CrashInjectionTest#convergesAfterCrash` (one case per point; exact loads 1/2/1/1 and publishes 1/1/1/2, same `eventId`), `#crashMidBatch` (×4 points, 10 items, loads ≤ 11), `#redeliveryBeforeLeaseExpiryDoesNotLoad`; AC5 `PublishFailureTest#republishWithoutReload`, plus `#persistentPublishFailureDeadLetters` (G-22: DLQ after 5 receives, ledger stays `PENDING_PUBLISH` for reconciliation, A9). 5/5 runs green (deterministic tests).
- Mutants detected: `PendingPublish` treated as `Claimed` (4 E3 failures); ack on handler exception (both `PublishFailureTest` cases fail).
- `PublishFailureTest` passed on first compile: the G-22 behaviour (no ack on publish failure, `PendingPublish` on redelivery) already followed from story 08's handler. This story adds the tests that pin it.
- Hook points (design §7): `AFTER_CLAIM` right after `claim` returns (any variant); `AFTER_LOAD` after the loader returns and the permit is closed; `AFTER_RECORD` after `recordObservation`; `AFTER_PUBLISH` after publish and before `markPublished`, on both the load and `PendingPublish` paths.
- `LoadExecutor` constructor gains `CrashHook` (last parameter). `ExecutorFactory.create(config, ports, seed)` passes `CrashHook.NONE`; an overload takes the hook. `ExecutorHarness.deterministic(config, crashHook)` shares one hook across executors; an armed `CrashInjector` fires once.
- Crash model per G-11: the crashed instance is not reused; the test advances `MutableClock` by `lease` (≥ visibility) and a second executor (new `workerId`) takes over.
