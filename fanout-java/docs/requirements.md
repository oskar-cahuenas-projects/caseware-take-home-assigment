# Fan-out Worker (Part 2): Requirements

> Prompt A, Phases 1–2. Tags: **[F]** brief, **[C]** contract or decided constraint, **[A n%]** assumption, **[R]** recommendation.
> Gap IDs (`G-nn`) refer to [`contract-gaps.md`](contract-gaps.md). All gaps were approved with their recommended option at Checkpoint 1.

## 1. Problem summary

A template publish can make tens of thousands of engagements candidates for a pending-update flag [F]. For engagements whose template state is `UNKNOWN`/unverified, the only way to read it is the engagement loader: ~1 min per call, owned by another team, capacity-limited [F]. The **Load Executor** (Stage B) consumes `LoadRequest`s from three lanes (I4), calls the loader (I5) under a hard concurrency budget `C` per region split statically across `N` tasks [C §F], and reports what it saw as `EngagementStateObserved` / `EngagementLoadFailed` events (I6) [C §A].
It must survive at-least-once, unordered delivery, crashes at any step, sustained throttling and a 40k-engagement firm flooding the queue, without exceeding `C`, losing work, or loading the same engagement twice within a freshness window W [C §D, §E].
A **thin Planner** (Stage A, stretch) turns one publish event into flag commands (I3) and load requests [C §C].

## 2. Scope

**In**
- Load Executor: lane polling, ledger claim, permits, loader call with heartbeat and hard timeout, outbox publish, job outcomes, backoff, circuit breaker, DLQ handling [C §C].
- Thin Planner (Should): `createIfAbsent`, `plannerLease`, candidate paging, `ApplyHeadBatch`, `LoadRequest`, supersession [C §C Planner].
- In-memory adapters for every port (I1–I7) and test fakes (`FakeEngagementLoader`, `MutableClock`, manual scheduler, crash injector) [C A5].
- Tests: contract tests E1–E10 in priority order [C §E], plus unit tests per adapter.
- `fanout-java/README.md` with the key implementation tradeoffs [F].

**Out**
- Real AWS (SQS, DynamoDB, EventBridge), AWS SDK, Docker, LocalStack [C A5].
- Tracking Service implementation and projection writes. Only a fake consumer for the E10 contract test [C §A I6].
- UI, LLM summaries, reconciliation job, DLQ redrive tooling, autoscaling, global (cross-region) limiter [C §F].

## 3. Assumptions, constraints and edge cases

### 3.1 Assumptions

| ID | Assumption | Confidence | If wrong | Source |
|---|---|---|---|---|
| A1 | The loader returns `engagementSeq`. | 60% | Observations only fill `UNKNOWN` fields; eventId falls back to the request ID (G-06). | [C §0] |
| A2 | Every catalogued engagement has a projection row; `templateId` may be null. | 75% | Null-template rows go to the backfill lane. | [C §0] |
| A3 | `C` is a per-region cap; saturation shows as 429 or timeout. | Assumption | Needs a cross-region quota service. | [C §0] |
| A4 | Fixed `N` tasks per cell, each holding `⌊C/N⌋` permits. | Design choice | Lease-based global limiter (§F upgrade path). | [C §0, §F] |
| A5 | In-memory adapters only. | Decision | — | [C §0] |
| A6 | `engagementId` is unique within a region, so it alone keys the ledger. | 90% | Key by `(firmId, engagementId)`. | new |
| A7 | When the breaker opens, loads already in flight finish normally; only new receives stop. | 80% [R] | Cancel in-flight loads (wastes capacity already spent). | new |
| A8 | The loader honours thread interruption, or the executor can abandon the call after the hard timeout without leaking a permit. | 70% | A stuck call holds a permit until it returns; capacity degrades, the invariant still holds. | new (G-28) |
| A9 | The reconciliation job (out of scope) re-enqueues anything left `PENDING_PUBLISH` or past SQS retention. | 85% | Add a ledger sweeper. | [C §D row 12] |

### 3.2 Constraints

- Java 21, Maven wrapper, no framework, no runtime dependencies; JUnit 5 + AssertJ for tests only [C].
- Executor step order: claim → permit → load → `recordObservation` → publish → `markPublished` → job outcome → ack [C §C].
- Ledger keyed by `engagementId` with `observedSeq`; it is the observation outbox [C §B, §F].
- Deferrals use `requeueWithDelay` (new message, receive count reset); `maxReceiveCount = 5` counts real failures only; delay capped at 15 min [C §C].
- Breaker open → stop receiving [C §C].
- Permits: static `⌊C/N⌋` per task; per-firm cap `max(1, ⌊0.2 × C/N⌋)`; lanes 6:3:1 [C §C].
- Payloads and logs carry IDs and versions only [C §A].
- Contract names and signatures kept unless logged in `contract-gaps.md` [C].
- Time only through injected `Clock`/scheduler; randomness through seeded `Random` [C master §1.5].

### 3.3 Edge cases

| # | Edge case | Expected behaviour | Ref |
|---|---|---|---|
| EC-01 | Duplicate `LoadRequest` while a load for the same engagement is in flight | No second loader call. The duplicate is deferred until the lease ends, then coalesces (G-05). | §D row 4, E2 |
| EC-02 | Requests from v6 and v7 jobs for one engagement | One load; both jobs get an outcome (DONE / COALESCED). | §D row 4, E2 |
| EC-03 | Worker crashes after claim, before load | Lease and visibility expire; another worker loads. ≤1 extra load. | §D row 5, E3 |
| EC-04 | Crash after load, before `recordObservation` | Reload once (≤1 extra load). | §D row 5, E3 |
| EC-05 | Crash after `recordObservation`, before publish | No reload; `PendingPublish` republishes. | §D row 6, E3 |
| EC-06 | Crash after publish, before ack | Duplicate event with the same `eventId`; harmless. Job outcome stays exact. | §D row 7, E3, E8 |
| EC-07 | Lease expiry during a load (load slower than lease) | Prevented by config validation: lease ≥ hard timeout + margin (G-14). | G-14 |
| EC-08 | Visibility expiry during a long load | Heartbeat extends visibility; no second delivery. | §C, E7 |
| EC-09 | Load exceeds the hard timeout | Treated as `Transient`; permit released; requeued. | §C |
| EC-10 | `retryAfter` > 15 min | Delay capped at 15 min; breaker keeps receives stopped meanwhile (G-12). | §C, G-12 |
| EC-11 | `C` not divisible by `N` | Each task gets `⌊C/N⌋`; remainder unused and documented (G-15). | §F, G-15 |
| EC-12 | `C/N < 5` | Firm cap = 1 (the `max(1, …)` floor). | §C |
| EC-13 | `C < N` | Configuration rejected at startup (a task with 0 permits never makes progress). | G-15 |
| EC-14 | Some lanes empty | Weighted polling is work-conserving: empty lanes are skipped (G-13). | §C, G-13 |
| EC-15 | All lanes empty | Receive returns empty; executor idles on the scheduler, no busy spin. | G-24 |
| EC-16 | Breaker half-open | Exactly one probe load; success closes, failure re-opens with a new cool-down. | G-03 |
| EC-17 | Observation shows a different template or branch | Still published; job outcome DONE. Tracking Service decides. | §D row 10 |
| EC-18 | Job closes when some items coalesced | COALESCED counts as done: `done + dlq = expected`. | §B FanoutJob, G-07 |
| EC-19 | A1 false (no `engagementSeq`) | `seqKnown=false`, fallback `eventId`, freshness by `observedAt` only. | §0 A1, G-06 |
| EC-20 | Per-firm cap reached but permits free | Release claim, requeue with short delay + jitter; other firms proceed. | §C, E5, G-21 |
| EC-21 | No permit after a successful claim | Release claim (so the requeued copy can claim), requeue with delay. | §C, G-21 |
| EC-22 | Publish (I6) fails after `recordObservation` | No ack; message reappears; claim returns `PendingPublish`; republish without reload. | §D row 6, G-22 |
| EC-23 | Unexpected `RuntimeException` (bug / poison payload) | Permit released; no ack; after `maxReceiveCount = 5` the queue dead-letters it. | §D row 14, G-23 |
| EC-24 | Duplicate `recordOutcome` for one `(jobId, engagementId)` | No-op; counters exact. | §D row 16, E8 |
| EC-25 | Job ID unknown to the job store when recording an outcome | Logged and ignored; message still acked (the observation is still useful). [R] | G-07 |
| EC-26 | `Transient` repeating forever for one engagement | After 8 attempts: `LoadFailed(TRANSIENT_EXHAUSTED)` + DLQ. | G-16 |
| EC-27 | Heartbeat fires after the load finished | No-op; heartbeat cancelled in `finally`. | G-10 |
| EC-28 | Two workers both see `PendingPublish` | Both publish; duplicate `eventId` is harmless; `markPublished` is idempotent. | G-04, G-19 |

### 3.4 Ambiguities → `contract-gaps.md`

Seeded G-01 … G-15 (master prompt §2 Phase 4), plus these found during analysis:

| ID | Topic | Why it matters |
|---|---|---|
| G-05 (changed) | `InFlightElsewhere` handling | The seeded recommendation (attach job and **ack**) loses the item if the lease owner crashes. Recommend the alternative: requeue until the lease ends. |
| G-16 | `Transient` retry bound and attempt tracking | `requeueWithDelay` resets `receiveCount`, so exponential backoff needs an attempt counter in the message, and an always-failing engagement would loop forever. |
| G-18 | E7 vs. hard timeout | E7 needs a 9-min load (3 × 3 min visibility), but the hard timeout is 5 min. The E7 test must run with a config where hard timeout > 3 × visibility. |
| G-19 | `claim` decision table | Exact rules for `AlreadyFresh` (W, `knownSeq`, A1 false), lease expiry, and `PENDING_PUBLISH` precedence. |
| G-21 | Releasing a claim on deferral | Claim precedes permit, so a "no permit" deferral would otherwise hold the lease and bounce the requeued copy as `InFlightElsewhere` until the lease (≥5 min) expires. Needs `LoadLedger.release(...)`. |
| G-22 | Publish failure | Not in §D explicitly. |
| G-23 | Poison messages in a typed in-memory queue | Records validate in constructors, so "bad schema" must be modelled differently. |
| G-24 | Receive sizing and deferral delays | Receiving more than free permits causes needless requeue churn. |
| G-25 | `FanoutJobStore` additions for the Planner | Contract has only `createIfAbsent` / `recordOutcome`; the Planner needs `addExpected`, `markPlanned`, lease and cursor operations, and tests need a read. |
| G-26 | Envelope fields | §A requires `schemaVersion`, `correlationId`, `region` on every message; `LoadRequest` and `EngagementLoadFailed` lack some. |
| G-27 | Breaker scope | One breaker per task (matches the static split) vs. shared per cell. |
| G-28 | Hard-timeout enforcement | Who enforces the 5-min timeout: the loader (via the `timeout` argument), the executor, or both. |

G-06 is widened: with A1 false, `markPublished(engagementId, long observedSeq)` has no sequence to pass, so its key must change (signature change, needs approval).

## 4. Functional requirements

| ID | Requirement | Source | Acceptance criterion | Verified by |
|---|---|---|---|---|
| FR-01 | Poll the three lanes with 6:3:1 weights, work-conserving. | §C lanes, G-13 | Over 100 receives with all lanes full, lane shares are 60/30/10 ± 1 slot; an empty lane's slots go to the others. | |
| FR-02 | Claim the ledger entry first; act on each `Claim` variant. | §B, §C step 1 | Every message produces exactly one claim call before any permit or loader call. | |
| FR-03 | `AlreadyFresh` → outcome COALESCED, ack, no loader call. | §D row 4, E2 | Duplicate and cross-version requests within W ⇒ exactly 1 loader call; all jobs counted. | |
| FR-04 | `InFlightElsewhere` → defer without losing the item. | §D row 4, G-05 | Duplicate arriving mid-load is not loaded again and still gets an outcome after the load ends, even if the owner crashed. | |
| FR-05 | `PendingPublish` → publish, `markPublished`, outcome, ack, with no loader call. | §D row 6, E3 | Crash after `recordObservation` ⇒ 0 extra loader calls; event published. | |
| FR-06 | `Claimed` → acquire a permit; on no permit or firm at cap, release the claim and `requeueWithDelay` with jitter. | §C, G-21 | Deferred message is a new delivery with `receiveCount = 1`; ledger lease released. | |
| FR-07 | Call the loader with a hard timeout; timeout ⇒ `Transient`. | §C heartbeat, G-28 | A load exceeding the timeout is requeued and its permit released. | |
| FR-08 | While loading, extend visibility by 3 min every 60 s. | §C heartbeat, E7 | A load lasting 3 × visibility is never delivered to a second worker. | |
| FR-09 | On success: `recordObservation` → publish `EngagementStateObserved` → `markPublished` → `recordOutcome(DONE)` → ack, in that order. | §C steps 4–8 | Recorded call order matches; event `eventId = engagementId:seq`; `jobIds` include the job. | |
| FR-10 | `Throttled`/`Transient` → release permit, breaker failure, `requeueWithDelay` with exponential backoff + full jitter, honouring `retryAfter`, capped at 15 min. | §C, §D row 11, E4, G-12, G-16 | Sustained 429 ⇒ 0 DLQ messages; all items complete after throttling stops. | |
| FR-11 | `NotFound`/`Permanent` → publish `EngagementLoadFailed`, `deadLetter`, `recordOutcome(DLQ)`. | §D row 13, E6 | Item in DLQ after 1 attempt; event emitted; job closes on `done + dlq = expected`. | |
| FR-12 | Circuit breaker CLOSED/OPEN/HALF_OPEN; while OPEN the executor does not receive. | §C breaker, G-03, G-27 | While OPEN, queue `receive` call count stays constant; HALF_OPEN admits one probe. | |
| FR-13 | Enforce `⌊C/N⌋` permits per task and the per-firm cap. | §C, E1 | Peak concurrent loader calls ≤ C; per firm ≤ cap × N. | |
| FR-14 | Release every permit exactly once on every path. | G-02 | After any test, permits in use = 0; double close is a no-op. | |
| FR-15 | Converge after a crash at any of the four points with ≤ 1 extra load and no lost item. | §D rows 5–7, E3 | Each crash point: all items DONE; loader calls ≤ items + crashes. | |
| FR-16 | Stay safe when A1 is false. | §0 A1, G-06 | Without `engagementSeq`: fallback `eventId`, `seqKnown=false`, freshness by `observedAt`. | |
| FR-17 | Events carry IDs, versions and the envelope (`schemaVersion`, `correlationId`, `region`). | §A envelope, G-26 | Event records contain no free-text content fields; envelope present on every event. | |
| FR-18 | Unexpected exceptions: release permit, no ack; queue dead-letters after `maxReceiveCount = 5`. | §D row 14, G-23 | A request that always throws reaches the DLQ after exactly 5 receives. | |
| FR-19 | Job store: `createIfAbsent` idempotent; `recordOutcome` idempotent per `(jobId, engagementId)`; load side closes on `done + dlq = expected`. | §B FanoutJob, §D rows 1, 16, E8 | Duplicate outcomes leave counters exact; status COMPLETE exactly when the rule holds. | |
| FR-20 | In-memory `WorkQueue` mirrors SQS: visibility timeout, receive count, ack, `extendVisibility`, `requeueWithDelay` (new message), delay cap 15 min, DLQ per lane. | §B WorkQueue, §C | Unit tests per behaviour under `MutableClock`. | `InMemoryWorkQueueTest` |
| FR-21 | In-memory `LoadLedger`: atomic claim with lease, W and `knownSeq`; `recordObservation` → `PENDING_PUBLISH`; `markPublished` → `DONE`; `release`. | §B LoadLedger, G-19, G-21 | Concurrent claims on one engagement ⇒ exactly one `Claimed`. | |
| FR-22 | Planner (Should): deterministic `jobId`; duplicate event ⇒ one job. | §B, §D row 1, E8 | Two identical events ⇒ one job, one planning pass. | |
| FR-23 | Planner: one planner per job via `plannerLease`. | §D row 3 | Two concurrent planners ⇒ one proceeds. | |
| FR-24 | Planner: page candidates; known rows → `ApplyHeadBatch` ≤ 100 ids; `UNKNOWN`/`UNVERIFIED` → `LoadRequest` (PUBLISH lane); save cursor after sends. | §C Planner, §D row 2 | Batches ≤ 100; `loadsExpected` = number of load requests; resume after crash re-sends at most one page. | |
| FR-25 | Planner: stop and mark `SUPERSEDED` when a higher `graphRev` exists; queued loads stay. | §C, §D row 9, E9 | Withdraw at higher `graphRev` stops the old planner; already-queued loads still complete. | |
| FR-26 | Tracking Service consumer contract: lower-seq observation ignored; duplicate `eventId` no-op. | §D rows 7, 8, E10 | Fake consumer state unchanged by stale or duplicate events. | |

## 5. Non-functional requirements

| ID | Requirement | Priority | Acceptance criterion | Verified by |
|---|---|---|---|---|
| NFR-01 | Capacity invariant: peak concurrent loader calls ≤ C; per firm ≤ cap per task. | Must | E1 with N tasks, 10k requests. | |
| NFR-02 | No lost work: every item ends DONE, COALESCED or in a DLQ. | Must | E3, E4, E6 end-state checks. | |
| NFR-03 | At most one extra load per crash. | Must | E3. | |
| NFR-04 | Determinism: no flaky tests; concurrency tests pass 5/5 runs. | Must | Story 16 runs the suite 5 times. | |
| NFR-05 | Full suite < 60 s. | Must | Surefire timing in story 16. | |
| NFR-06 | Data residency: payloads and logs carry IDs and versions only. | Must | Review checklist; event records have no content fields. | |
| NFR-07 | Java 21, no framework, no runtime dependencies. | Must | `pom.xml` has test-scope deps only. | `PomDependencyPolicyTest` |
| NFR-08 | Main code uses only injected `Clock`, scheduler and `Random`. | Must | Build-time grep check in story 16 for `Instant.now(`, `currentTimeMillis`, `Thread.sleep`. | `MainCodeTimeSourcesTest` |
| NFR-09 | Thread-safety stated in one Javadoc line per class. | Should | Review checklist. | |
| NFR-10 | Minimal observability: in-memory counters for permits in use, coalesced count, DLQ count, breaker state. | Could | Read by tests (§F Observability). | |

## 6. Technical dependencies and traceability

### 6.1 Dependencies

| Dependency | Scope | Why |
|---|---|---|
| JDK 21 | build/runtime | Virtual threads, records, sealed types, pattern matching [C]. |
| Maven wrapper (`./mvnw`) | build | Reproducible build without a global Maven [C]. |
| JUnit 5 (Jupiter) | test | Test runner, `@Timeout`, `@Tag`. |
| AssertJ | test | Readable assertions. |

### 6.2 Traceability matrix

Test column is filled in by each story.

| FR / NFR | Story | Test |
|---|---|---|
| FR-01 | 13 | |
| FR-02 | 08 | `LoadExecutorTest#stepOrder` |
| FR-03 | 05, 08 | `InMemoryLoadLedgerTest#alreadyFresh` (05), `ContractE02CoalescingTest` (08) |
| FR-04 | 05, 08 | `InMemoryLoadLedgerTest#inFlightElsewhereThenClaimedAfterExpiry` (05), `ContractE02CoalescingTest#duplicateMidLoad` (08) |
| FR-05 | 09 | `ContractE03CrashInjectionTest`, `PublishFailureTest` |
| FR-06 | 06, 08, 13 | `SemaphoreConcurrencyLimiterTest#budgetExhausted` (06), `LoadExecutorTest#noPermitDefers` (08) |
| FR-07 | 12 | |
| FR-08 | 12 | |
| FR-09 | 08 | `LoadExecutorTest#stepOrder` |
| FR-10 | 10 | `ThrottlingTest`, `BackoffTest`, `ContractE04ThrottlingNoDlqTest` |
| FR-11 | 11 | |
| FR-12 | 10 | `CircuitBreakerTest`, `ThrottlingTest#breakerStopsReceiving`, `#halfOpenProbe` |
| FR-13 | 06, 08 | `SemaphoreConcurrencyLimiterTest#hammer`, `#firmCap` (06), `ContractE01CapacityInvariantTest` (08) |
| FR-14 | 06, 08 | `SemaphoreConcurrencyLimiterTest#doubleCloseReleasesOnce`, `#releasedOnException` (06), `ContractE01CapacityInvariantTest` (08) |
| FR-15 | 09 | `ContractE03CrashInjectionTest` |
| FR-16 | 02, 05, 08 | `DomainBehaviourTest` (02), `InMemoryLoadLedgerTest#freshnessWithoutSequence` (05), `LoadExecutorTest#a1False` (08) |
| FR-17 | 02, 07 | `EventPayloadPolicyTest`, `RecordValidationTest` (02), `InMemoryObservationPublisherTest` (07) |
| FR-18 | 04, 11 | `InMemoryWorkQueueTest#maxReceiveCountDeadLetters` (04) |
| FR-19 | 07, 11 | `InMemoryFanoutJobStoreTest`, `ContractE08JobIdempotencyTest` (07) |
| FR-20 | 04 | `InMemoryWorkQueueTest` |
| FR-21 | 05 | `InMemoryLoadLedgerTest` |
| FR-22 | 14 | |
| FR-23 | 14 | |
| FR-24 | 14 | |
| FR-25 | 14 | |
| FR-26 | 15 | |
| NFR-01 | 08 | `ContractE01CapacityInvariantTest` |
| NFR-02 | 09, 10, 11 | `ContractE03CrashInjectionTest#crashMidBatch` (09), `ContractE04ThrottlingNoDlqTest` (10) |
| NFR-03 | 09 | `ContractE03CrashInjectionTest` |
| NFR-04 | 16 | |
| NFR-05 | 16 | |
| NFR-06 | 02, 16 | `EventPayloadPolicyTest` (02) |
| NFR-07 | 01 | `PomDependencyPolicyTest`, `BuildSmokeTest` |
| NFR-08 | 03, 16 | `MainCodeTimeSourcesTest`, `ManualSchedulerTest`, `MutableClockTest` (03) |
| NFR-09 | 16 | |
| NFR-10 | 10, 13 | `ThrottlingTest` via `breakerState()` (10) |

### 6.3 Contract coverage

| Contract section | Covered by |
|---|---|
| §0 Assumptions A1–A5 | §3.1 |
| §A Interfaces I1–I7, envelope | §1, §2, FR-17, FR-24 |
| §B Signatures | FR-02, FR-19 – FR-21, story 02 |
| §C Flow and semantics | FR-01 – FR-12, FR-22 – FR-25 |
| §D Failure matrix (16 rows) | §3.3, FR-03 – FR-05, FR-10, FR-11, FR-15, FR-18, FR-19, FR-22 – FR-26 |
| §E Tests E1–E10 | FR / NFR acceptance criteria; stories 08–15 |
| §F Tradeoffs, observability | A4, FR-13, NFR-10, story 17 |
