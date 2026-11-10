# 08 — Executor core and step order

- **Epic:** E3 Executor core
- **Priority:** Must
- **Status:** Done
- **Depends on:** 04, 05, 06, 07
- **Contract refs:** §B, §C step order and flow, §D rows 4, 10
- **Contract tests:** E1, E2
- **Checkpoint after:** None

## User story
As the platform, I want an executor that claims, loads and publishes in the contract's order on virtual threads, so that loader capacity is respected and duplicate work coalesces.

## Context
Covers FR-02 – FR-04, FR-06, FR-09, FR-13, FR-14, FR-16, NFR-01. Applies G-05, G-06, G-15, G-19, G-21, G-24. Single lane only here; weighting comes in story 13.

## Acceptance criteria
- AC1 — Given a `Claimed` request and an OK load, Then calls happen in the order claim → permit → load → `recordObservation` → publish → `markPublished` → `recordOutcome(DONE)` → ack (→ FR-09).
- AC2 — Given N executors, 10k requests and C permits, Then peak concurrent loader calls ≤ C and peak per firm per task ≤ cap; permits in use end at 0 (→ FR-13, FR-14, E1).
- AC3 — Given duplicate and cross-version requests for one engagement within W, Then exactly one loader call; a duplicate seen mid-load is requeued until the lease ends (G-05) and then coalesces; every job gets an outcome (→ FR-03, FR-04, E2).
- AC4 — Given no permit after a claim, Then the claim is released and the message is requeued with delay, not acked (→ FR-06).
- AC5 — Given an observation without `engagementSeq`, Then the event has `seqKnown=false` and the fallback `eventId` (→ FR-16).
- AC6 — Given an observation of a different template, Then it is still published and the outcome is DONE (→ EC-17).

## Tasks
- [x] Add `int freePermits()` to the `ConcurrencyLimiter` port (G-24, approved); size each receive as `min(maxReceiveBatch, freePermits())`.
- [x] `LoadExecutor` (receive loop, per-message handler on virtual threads), `ExecutorFactory` wiring.
- [x] Multi-instance test harness.
- [x] `ContractE01CapacityInvariantTest`, `ContractE02CoalescingTest`.

## Out of scope
- Crash recovery (09), errors and breaker (10, 11), heartbeat (12), lane weights (13).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC → test: AC1 `LoadExecutorTest#stepOrder` (recording decorators; exact sequence incl. `permit.close` right after the load); AC2 `ContractE01CapacityInvariantTest` (N = 2, 10k requests, C = 50, real virtual threads, ~4 s); AC3 `ContractE02CoalescingTest` (3 tests incl. mid-load duplicate across two executors); AC4 `LoadExecutorTest#noPermitDefers`, plus `#noFreePermitsNoReceive` (G-24); AC5 `#a1False`, `#a1True`; AC6 `#differentTemplateStillPublished`. E01, E02 and `LoadExecutorTest` 5/5 runs green.
- E1 sends firms in contiguous blocks (one firm of 1,000, then 180 firms of 50) so both limits bind: measured peak total = 50 = C, peak per firm = 10 = cap × N. With firms interleaved the firm peak was 1 and a no-firm-cap mutant survived; after clustering it is detected (firm peak 50). A per-task C-instead-of-C/N mutant is detected by the end-state permit check.
- `ConcurrencyLimiter.freePermits()` added to the port (G-24, approved after story 06).
- Receive sizing: `min(maxReceiveBatch, freePermits, permitsPerTask − activeHandlers)`. The `activeHandlers` term stops the loop from receiving again before spawned handlers have taken their permits. Zero room → no receive.
- Idle without sleeping: the loop waits on a wake-up semaphore released by a `Scheduler` timer (`receiveWait`) or by any finishing handler (EC-15).
- `ExecutorPorts` record bundles the shared ports; `ExecutorFactory.create(config, ports, seed)` gives task `i` `workerId = worker-i`, its own `SemaphoreConcurrencyLimiter.forTask(config)` and `new Random(seed + i)`. Test kit `ExecutorHarness` has deterministic and real-time modes.
- Jitter is uniform in `[0, firmCapDeferral)` from the seeded `Random`. `InFlightElsewhere` delay = time to lease end + jitter (G-05); no-permit delay = `firmCapDeferral` + jitter (G-24). The queue caps both at 15 min.
- Event `jobIds`: the request's own job on the `Claimed` path; the ledger's attached IDs on the `PendingPublish` path. `region`, `correlationId` come from the request envelope; `schemaVersion = 1`.
- **Interim, replaced by stories 10/11:** any `LoaderException` releases the claim and leaves the message un-acked (redelivered after visibility; counts towards `maxReceiveCount`). So until story 10, sustained throttling would reach the DLQ. Unexpected `RuntimeException` follows G-23 already.
- Not yet wired: `CrashHook` calls (story 09), heartbeat and hard timeout (story 12), breaker (story 10), lanes (story 13).
