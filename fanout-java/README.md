# Fan-out Worker

Java 21 implementation of the **template-publish fan-out worker** from the [design](../docs/solution-design.md), specified in [`fanout-worker-contract.md`](fanout-worker-contract.md).

- **Load Executor (Stage B, Must).** Calls the ~1-minute, capacity-limited `EngagementLoader` and publishes observations.
- **Thin Planner (Stage A, Should).** Turns a publish or withdraw event into flag commands and load requests.

Everything runs in memory behind ports: there is no framework, no AWS SDK and no Docker. Design detail is in [`docs/design.md`](docs/design.md), gap decisions in [`docs/contract-gaps.md`](docs/contract-gaps.md), and requirements, traceability and the review in [`docs/requirements.md`](docs/requirements.md).

## Build and test

JDK 21 is required. The Maven wrapper pins Maven 3.9.10.

```bash
./mvnw -q verify                        # build gate: compile and all 218 tests (~10 s)
./mvnw -q test -Dgroups=contract        # contract tests E1–E10 only
./mvnw -q test -Dtest=ContractE05FairnessTest
```

## Run the demo

A small end-to-end run on fake data: the Planner, the three lanes, two executor tasks on virtual threads and a fake Tracking Service consumer. Durations are scaled 1000× down (C = 50, N = 2, firm cap 5), so a 1-minute load takes 5 ms and the run finishes in about a second.

```bash
./mvnw -q test-compile
java -cp target/classes:target/test-classes com.caseware.fanout.demo.Demo
```

The scenario:
- **Data:** 300 known rows; one firm with 1,500 unknown engagements; 20 small firms with 10 each.
- **v7 published, twice:** the duplicate is ignored. Four engagements are throttled once; three are `NotFound`.
- **v8 published on the same branch after v7 finishes:** its loads coalesce onto v7's observations, which are still fresh.

Typical output (abridged):

```
Stage A - Planner
  v7 published: 3 ApplyHeadBatch commands (<= 100 known rows each), 1700 load requests
  duplicate v7 event -> DUPLICATE (one job, one planning pass)
Stage B - Load Executor
  loader calls            1707   (1700 unique engagements + 4 throttled retries + v8)
  peak concurrent calls     50   (limit C = 50)
  peak calls, big firm      10   (limit cap x N = 10)
  v8 loads coalesced      1697   (still fresh from v7: no reload)
  dead-lettered              6   (NotFound: eng-small-3-0, eng-small-7-0, eng-small-11-0)
Jobs
  prod-1:br-1:v7:1         COMPLETE  expected 1700  done 1697  dlq 3
  prod-1:br-1:v8:2         COMPLETE  expected 1700  done 1697  dlq 3
```

The demo lives in test sources (`com.caseware.fanout.demo.Demo`) because it is built from the test fakes; main code ships no test doubles. `DemoTest` runs it in the build and checks the same numbers.

## Layout

```
com.caseware.fanout
  domain          contract §B records, enums, sealed types (immutable)
  port            WorkQueue, MessageSink, LoadLedger, ConcurrencyLimiter, FanoutJobStore,
                  EngagementLoader, ObservationPublisher, CandidateSource, Scheduler
  executor        LoadExecutor, LanePoller, LaneQueues, CircuitBreaker, Backoff, Heartbeat,
                  HardTimeout, ExecutorConfig, ExecutorFactory
  planner         Planner, PlannerConfig
  adapter.memory  in-memory SQS / DynamoDB / bus stand-ins, semaphore limiter, virtual-thread scheduler
test: testkit (MutableClock, ManualScheduler, FakeEngagementLoader, CrashInjector, ExecutorHarness,
      FakeTrackingServiceConsumer), contract (ContractE01…E10), demo (Demo, DemoTest), unit tests mirroring main
```

## How the code maps to the contract

- **Ports I1–I7** are interfaces in `port`. The contract's names and signatures are kept; every addition is logged as a gap (G-01 … G-29), for example `OptionalLong engagementSeq` (G-06) and `MessageSink` (G-29).
- **Step order (§C)** is in `LoadExecutor`: claim ledger → acquire permit → call loader (heartbeat and hard timeout armed) → `recordObservation` → publish → `markPublished` → record job outcome → ack. `LoadExecutorTest#stepOrder` asserts the exact call sequence.
- **Contract tests (§E)** are all green:

| Test | Shows | Test | Shows |
|---|---|---|---|
| E1 | peak loader calls ≤ C with 2 tasks and 10k requests | E6 | `NotFound` → DLQ at once, `LoadFailed`, job closes |
| E2 | duplicates and v6/v7 overlap → one load | E7 | a 9-min load is never delivered twice |
| E3 | crash at 4 points → ≤ 1 extra load, nothing lost | E8 | duplicate events and outcomes → exact counters |
| E4 | sustained 429 → 0 DLQ | E9 | a withdraw at higher `graphRev` supersedes; queued loads still run |
| E5 | 40k firm + 100 small firms → every small firm starts within 1,350 call starts | E10 | the consumer drops stale-seq and duplicate events produced by the executor |

## Key implementation tradeoffs

| Decision | Rejected alternative | Why | Cost |
|---|---|---|---|
| Ledger keyed by `engagementId` + `observedSeq`; it is also the outbox (`PENDING_PUBLISH`) | Key `(engagementId, targetVersion)` | Coalesces loads across versions (E2); a crash after load never reloads (E3) | One conditional write more per load |
| Deferrals use `requeueWithDelay` (a new message) | Visibility change | `maxReceiveCount` counts only real failures, so a long outage dead-letters nothing (E4) | A delete plus a send per deferral; delay capped at 15 min |
| Static `C/N` semaphore + per-firm cap `max(1, 0.2·C/N)` | Shared token bucket or a global semaphore | No cross-task coordination; the cap bounds fairness without per-firm queues (E5) | Up to `C mod N` permits unused; a task cannot borrow idle permits |
| Release the claim on every deferral (G-21) | Acquire the permit before claiming | Keeps the load-bearing order and spends no permits on duplicates | An extra ledger write per deferral |
| Duplicate while in flight → requeue until the lease ends (G-05) | Attach the job to the owner's entry | Nothing is lost if the owner crashes | One more message and up to one lease of latency |
| Hard timeout on the `Scheduler`, interrupting the call; permit held until the call returns (G-28) | `Future.get(timeout)` | Deterministic under the test clock; the capacity count stays exact even if the loader ignores interrupts | A stuck call holds its permit (A8) |
| Breaker per task; OPEN stops receiving (G-03, G-27) | Shared breaker; receive then defer | No shared state; no churn while the loader is down | Each task discovers an outage separately (≤ K failures) |
| SWRR lanes 6:3:1, one slot per receive, skip empty lanes (G-13) | Random weighted choice | Deterministic and work-conserving | Weights are per receive, not per message |
| Lazy expiry on an injected `Clock` + `ManualScheduler` | Background tick threads | Deterministic tests, no sleeps; the suite runs 5/5 green in ~10 s | Two test modes (deterministic vs. real-time concurrency) |
| Planner saves the cursor after a page's sends; `Cursor.END` before `markPlanned` | Save before sending | Never skips a page; a crash re-sends at most one page, and duplicates are harmless downstream | Duplicate messages after a crash |

## Simulated in memory vs. AWS

| Here | On AWS (design) |
|---|---|
| `InMemoryWorkQueue` ×3 behind `LaneQueues` | SQS standard fair queues `load-requests-{publish,backfill,reconcile}`, `MessageGroupId = firmId`, one DLQ each |
| `InMemoryLoadLedger`, `InMemoryFanoutJobStore` (`compute` = conditional write or transaction) | DynamoDB conditional writes and `TransactWriteItems` |
| `InMemoryObservationPublisher` | EventBridge regional bus `engagement-observations` |
| `InMemoryCandidateSource`, `MessageSink` list | Tracking Service query API (with GSI lag); `tracking-commands` SQS |
| N `LoadExecutor`s in one JVM, each with its own limiter | N ECS/Fargate tasks per cell |
| `FakeEngagementLoader` with scripted outcomes | The other team's loader |

## Assumptions

- **A1 (60%, riskiest): the loader returns `engagementSeq`.** If it doesn't, events carry `seqKnown=false` and the fallback `eventId = engagementId:obs:<requestId>`, ledger freshness uses `observedAt` only, and the consumer fills only `UNKNOWN` fields. This path is tested end to end (`LoadExecutorTest#a1False`, E10).
- A3/A4: the per-region cap `C` is split statically over `N` tasks.
- A6: `engagementId` is unique within a region.
- A7: in-flight loads finish when the breaker opens.
- A8: the loader honours interruption; if it doesn't, it only holds its own permit longer.
- A9: an out-of-scope reconciliation job re-enqueues stragglers.

## Left out, and known limitations

- **Left out:** real AWS adapters and LocalStack; the Tracking Service itself (head computation, projection writes); the reconciliation producer and the A2 backfill path (both lanes exist and are polled); GSI lag; autoscaling; a receive loop around the Planner (it is driven by its caller).
- **Limitations:**
  - A poison message reaches the DLQ without `EngagementLoadFailed`, so its job stays open until reconciliation (G-23).
  - No coalesced-count metric (NFR-10).
  - E5 runs in real time with about 6 waves of margin.
  - G-29 additions await owner review.
  - The full list is in [`docs/requirements.md` §8](docs/requirements.md).
