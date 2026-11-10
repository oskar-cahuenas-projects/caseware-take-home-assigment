# Contract gaps and resolutions

> Prompt A, Phase 4. Binding once `Approved` (master prompt §1.3, rank 4).
> All entries were approved with their recommended option at Checkpoint 1 (2026-11-09, "apply default solution for all G's").
> IDs G-17 and G-20 are unused: G-17 was folded into G-05 and G-20 into G-06.
> **Signature changes** (contract §B differs): G-04, G-06, G-16, G-21, G-25, G-26. Each is additive except G-06.

## Summary

| ID | Topic | Decision | Signature change | Status |
|---|---|---|---|---|
| G-01 | Observation publisher (I6) | `ObservationPublisher` port, two `publish` overloads | Added port | Approved |
| G-02 | Permit | `Permit extends AutoCloseable`, idempotent `close()` | Added type | Approved |
| G-03 | Circuit breaker | CLOSED/OPEN/HALF_OPEN, K = 5, cool-down on clock, one probe | — | Approved |
| G-04 | Claim payloads | Four records, `PendingPublish` carries `eventId` | Added records | Approved |
| G-05 | `InFlightElsewhere` | Requeue until the lease ends; no ack-and-attach | Behaviour vs. §C diagram | Approved |
| G-06 | A1 false | `OptionalLong engagementSeq`; fallback `eventId`; ledger keyed on `eventId` | **Changed** | Approved |
| G-07 | Outcome / FailureReason | `Outcome {DONE, COALESCED, DLQ}`; `FailureReason {NOT_FOUND, PERMANENT, TRANSIENT_EXHAUSTED}` | Added enums | Approved |
| G-08 | Undefined domain types | Minimal `DecisionRef`, `Page<T>`, `Cursor`, `ProjectionStatus` | Added types | Approved |
| G-09 | Fairness bound (E5) | Rank-based bound in loader-call starts | — | Approved |
| G-10 | Time-driven actions | Lazy expiry on the clock + `Scheduler` port for heartbeat and timeout | Added port | Approved |
| G-11 | Crash simulation | `SimulatedCrash extends Error`; instance discarded; expiry via clock | — | Approved |
| G-12 | Requeue delay cap | Cap at 15 min | — | Approved |
| G-13 | Lane weighting | Smooth weighted round-robin, work-conserving | — | Approved |
| G-14 | Ledger lease | `lease = hardTimeout + leaseMargin` (6 min) | — | Approved |
| G-15 | N tasks | N executors in one JVM; `⌊C/N⌋` permits; `C < N` rejected | — | Approved |
| G-16 | Transient retry bound | `attempt` field on `LoadRequest`; DLQ after 8 `Transient` | Added field | Approved |
| G-18 | E7 vs. hard timeout | E7 runs with `hardTimeout = 10 min` | — | Approved |
| G-19 | Claim decision table | `PENDING_PUBLISH` > fresh > live lease > `Claimed` | — | Approved |
| G-21 | Release on deferral | `LoadLedger.release(engagementId, workerId)` | Added method | Approved |
| G-22 | Publish failure | No ack; redelivery hits `PendingPublish` | — | Approved |
| G-23 | Poison messages | Unexpected `RuntimeException` → no ack → queue DLQ after 5 | — | Approved |
| G-24 | Receive sizing, deferral delays | `receive(min(10, free permits))`; delays defined below | — | Approved |
| G-25 | Job store for the Planner | `find`, planner lease, `savePage`, `markPlanned`, `markSuperseded`, `latestGraphRev` | Added methods | Approved |
| G-26 | Envelope fields | Add `schemaVersion`, `region` where missing | Added fields | Approved |
| G-27 | Breaker scope | One breaker per executor task | — | Approved |
| G-28 | Hard-timeout enforcement | Executor schedules the timeout and interrupts; loader also gets `timeout` | — | Approved |

## Entries

### G-01 — Observation publisher port (I6)
- **Ref:** §A I6, §B Outbound.
- **Problem:** §B defines the event records but no port to send them.
- **Options:** (a) one `ObservationPublisher` with two `publish` overloads; (b) a generic `EventBus<T>`.
- **Recommendation [R]:** (a). `void publish(EngagementStateObserved e)`, `void publish(EngagementLoadFailed e)`; both throw unchecked `PublishFailedException`. The in-memory adapter records events in order and can fail the next N calls.
- **Status:** Approved.

### G-02 — Permit
- **Ref:** §B ConcurrencyLimiter.
- **Problem:** `Permit` is undefined; release must be safe on every path.
- **Options:** (a) `Permit extends AutoCloseable`, used in try-with-resources, `close()` idempotent and exception-free; (b) explicit `limiter.release(permit)`.
- **Recommendation [R]:** (a). The permit is held only around the loader call (step 3), not until ack.
- **Status:** Approved.

### G-03 — Circuit breaker
- **Ref:** §C Circuit breaker, §D rows 11–12.
- **Problem:** States, thresholds and recovery are unspecified.
- **Options:** (a) consecutive-failure breaker; (b) failure-rate window.
- **Recommendation [R]:** (a). CLOSED → OPEN after K = 5 consecutive `Throttled`/`Transient`; OPEN → HALF_OPEN after a cool-down (production 30 s) measured on the injected clock; HALF_OPEN admits exactly one probe load; success → CLOSED, failure → OPEN with a new cool-down. Any success in CLOSED resets the count. The executor checks `allowReceive()` before every `receive`. `NotFound`/`Permanent` do not count.
- **Status:** Approved.

### G-04 — Claim payloads
- **Ref:** §B `sealed interface Claim`.
- **Problem:** The four variants have no fields.
- **Options:** (a) minimal payloads; (b) one record with a status enum.
- **Recommendation [R]:** (a). `Claimed(Instant leaseUntil)`, `AlreadyFresh(OptionalLong observedSeq, Instant observedAt)`, `InFlightElsewhere(String ownerWorkerId, Instant leaseUntil)`, `PendingPublish(EngagementTemplateState state, String eventId, List<String> jobIds)`. `eventId` is added so a republish reuses the original ID (G-06).
- **Status:** Approved.

### G-05 — Coalescing vs. job accounting (`InFlightElsewhere`)
- **Ref:** §C flow ("AlreadyFresh / InFlightElsewhere → ack, record coalesced"), §D row 4.
- **Problem:** Acking an `InFlightElsewhere` duplicate and relying on the lease owner to record its outcome loses the item if the owner crashes (the duplicate is gone; the owner's own message is redelivered but knows nothing about the attached job).
- **Options:** (a) attach job ID to the entry and ack (seeded); (b) `requeueWithDelay` until the lease ends; the later delivery sees `AlreadyFresh` → COALESCED, or `Claimed` if the owner crashed.
- **Recommendation [R]:** (b). Delay = `min(leaseUntil − now, 15 min) + jitter`. One extra message per duplicate; no lost item; outcome always recorded by the message's own processing. **Deviation from the §C diagram**, logged here.
- **Status:** Approved.

### G-06 — A1 false (no `engagementSeq`)
- **Ref:** §0 A1, §B `EngagementTemplateState`, `LoadLedger.recordObservation`, `markPublished`.
- **Problem:** `long engagementSeq` cannot express "unknown"; `markPublished(engagementId, long observedSeq)` and `eventId = engagementId:seq` have no value to use.
- **Options:** (a) `OptionalLong` + `eventId` as the ledger's observation key; (b) sentinel `-1`; (c) a ledger-internal revision counter returned by `recordObservation`.
- **Recommendation [R]:** (a).
  - `EngagementTemplateState.engagementSeq` → `OptionalLong`.
  - `eventId` = `engagementId:<seq>` if present, else `engagementId:obs:<requestId>` (domain helper `ObservationIds.of(state, requestId)`).
  - `EngagementStateObserved` adds `boolean seqKnown` (consumer fills `UNKNOWN` fields only when `false`).
  - `recordObservation(String engagementId, EngagementTemplateState s, String eventId)` and `markPublished(String engagementId, String eventId)` — **changed signatures**.
  - Ledger freshness uses `observedAt` only when the sequence is unknown.
- **Status:** Approved.

### G-07 — Outcome and FailureReason
- **Ref:** §B `recordOutcome`, `EngagementLoadFailed`, FanoutJob close rule.
- **Recommendation [R]:** `Outcome {DONE, COALESCED, DLQ}`; `FailureReason {NOT_FOUND, PERMANENT, TRANSIENT_EXHAUSTED}` (third value from G-16). `recordOutcome` is idempotent per `(jobId, engagementId)`: the first outcome wins. Load side closes when status ≠ `PLANNING` and `loadsDone (incl. COALESCED) + loadsDlq = loadsExpected`; a `PLANNED` job then becomes `COMPLETE`. Unknown `jobId` → logged and ignored (EC-25).
- **Options considered:** last-outcome-wins (rejected: counters would need decrement logic).
- **Status:** Approved.

### G-08 — Undefined domain types
- **Ref:** §B `DecisionRef`, `Page`, `Cursor`, `ProjectionStatus`.
- **Recommendation [R]:** `DecisionRef(String versionId, Decision decision)` with `Decision {APPLIED, DECLINED}`; `Page<T>(List<T> items, Optional<Cursor> next)`; `Cursor(String token)`; `ProjectionStatus {KNOWN, UNKNOWN, UNVERIFIED}`.
- **Options considered:** opaque `Object` placeholders (rejected: no compiler help).
- **Status:** Approved.

### G-09 — Fairness bound for E5
- **Ref:** §E 5, §D row 15.
- **Problem:** "bounded wait" has no number; wall-clock bounds are flaky.
- **Options:** (a) wall-clock bound; (b) bound on the **rank** of each small firm's first loader-call start among all starts.
- **Recommendation [R]:** (b). With per-task firm cap `k` and N tasks, the big firm holds ≤ `N·k` permits, so small firms always share `S = C − N·k ≥ 0.8·C`. Each small firm takes at most `N·k` concurrent slots, so all `F` small firms have started within `W = ⌈F · min(N·k, n) / S⌉ + 2` waves of `C` starts. Test parameters C = 50, N = 2 (k = 5), F = 100, n = 40, big firm 40k enqueued **first**: bound = (⌈1000 / 40⌉ + 2) × 50 = 1,350 starts. Without the cap (FIFO) the first small firm would be at rank ≈ 40,000.
- **Status:** Approved.

### G-10 — Time-driven actions
- **Ref:** §C heartbeat, master §1.5.
- **Options:** (a) all expiry evaluated lazily against the clock + a `Scheduler` port for periodic/timed actions; (b) a global tick loop driving every adapter.
- **Recommendation [R]:** (a). Visibility, delayed messages, leases, freshness and breaker cool-down are compared with `clock.instant()` when read (no background thread). Only the heartbeat and the hard timeout need a `Scheduler` (`schedule(Runnable, Duration)`, `scheduleAtFixedRate(...)`, returning a `Cancellable`). Production/concurrency tests: virtual-thread scheduler with the system clock and ms-scaled config. Deterministic tests: `MutableClock` + `ManualScheduler.runDue()` + `LoadExecutor.pollOnce()`.
- **Status:** Approved.

### G-11 — Crash simulation
- **Ref:** §E 3.
- **Recommendation [R]:** `CrashInjector` throws `SimulatedCrash extends Error` at the armed point. The executor catches `Exception` only, so the crash skips ack, requeue and claim release. The test discards the instance (models process death; its permits die with it) and starts a new instance with a new `workerId`. Lease and visibility expire by advancing `MutableClock`.
- **Options considered:** killing a thread (non-deterministic; rejected).
- **Status:** Approved.

### G-12 — Requeue delay cap
- **Ref:** §C, §F open uncertainties (SQS `DelaySeconds` max 15 min, ~90%).
- **Recommendation [R]:** The queue adapter caps any delay at `maxDelay` (15 min) and logs when capping. A `retryAfter` > 15 min is capped; the breaker keeps receives stopped meanwhile, and the jittered backoff keeps retries spread.
- **Options considered:** reject long delays (would force a visibility change, against §C).
- **Status:** Approved.

### G-13 — Lane weighting
- **Ref:** §C lanes.
- **Recommendation [R]:** Smooth weighted round-robin (6:3:1) producing a fixed 10-slot sequence; if a slot's lane returns nothing, the poller tries the next lanes in sequence order within the same receive (work-conserving).
- **Options considered:** random weighted choice (non-deterministic; rejected).
- **Status:** Approved.

### G-14 — Ledger lease
- **Recommendation [R]:** `lease = hardTimeout + leaseMargin` (production 5 + 1 = 6 min). `ExecutorConfig` rejects `lease < hardTimeout + margin`, so a lease cannot expire during a load that is still within its timeout. The lease covers steps 3–6; the outbox (`PENDING_PUBLISH`) covers anything after.
- **Options considered:** renewing the lease from the heartbeat (extra write per minute; rejected as unnecessary with a hard timeout).
- **Status:** Approved.

### G-15 — N tasks
- **Recommendation [R]:** N `LoadExecutor` instances in one JVM sharing queues, ledger, job store and publisher; each with its own limiter of `⌊C/N⌋` permits, firm cap `max(1, ⌊0.2·⌊C/N⌋⌋)`, and breaker (G-27). Remainder `C mod N` is unused (documented in README). `C < N` is rejected.
- **Status:** Approved.

### G-16 — Transient retry bound and attempt tracking
- **Ref:** §C (requeue resets receive count), §D row 11, WBS-3.3 "bounded attempts".
- **Problem:** Backoff needs an attempt count that survives `requeueWithDelay`; an engagement that always times out would loop forever.
- **Options:** (a) `int attempt` in `LoadRequest`, bound on `Transient` only; (b) bound both; (c) no bound, rely on reconciliation.
- **Recommendation [R]:** (a). `LoadRequest` gains `int attempt` (0 on first send; `withNextAttempt()` on retry). Backoff = full jitter over `min(maxDelay, base · 2^attempt)` (base 30 s production), at least `retryAfter` when throttled. `Transient` at `attempt ≥ maxTransientAttempts` (8) → `EngagementLoadFailed(TRANSIENT_EXHAUSTED)` + `deadLetter` + `recordOutcome(DLQ)`. `Throttled` never dead-letters (E4). Deferrals (G-05, G-24) do not increment `attempt`.
- **Status:** Approved.

### G-18 — E7 vs. hard timeout
- **Problem:** 3 × 3 min visibility = 9 min > 5 min hard timeout.
- **Options:** (a) E7 config with `hardTimeout = 10 min`, `lease = 11 min`; (b) shrink visibility in E7.
- **Recommendation [R]:** (a). Keeps the contract's 3-min visibility and 60-s heartbeat literal; only the timeout is raised for this test.
- **Status:** Approved.

### G-19 — Claim decision table
Evaluated atomically, first match wins:

| # | Ledger entry | Result | Side effect |
|---|---|---|---|
| 1 | `PENDING_PUBLISH` | `PendingPublish(state, eventId, jobIds)` | attach `jobId` |
| 2 | `DONE`, `observedAt + W > now`, and (seq unknown **or** `observedSeq ≥ knownSeq`) | `AlreadyFresh` | — |
| 3 | `LEASED` by another worker, `leaseUntil > now` | `InFlightElsewhere` | — |
| 4 | otherwise (absent, stale, expired lease, same worker) | `Claimed(now + lease)` | owner = worker, attach `jobId` |

- **Status:** Approved.

### G-21 — Releasing a claim on deferral
- **Problem:** Claim (step 1) precedes permit (step 2). A deferral after `Claimed` would hold the lease for 6 min and bounce the requeued copy as `InFlightElsewhere`.
- **Options:** (a) `LoadLedger.release(String engagementId, String workerId)`; (b) swap steps 1 and 2 (rejected: violates §1.4 step order and burns permits on duplicates).
- **Recommendation [R]:** (a). Called on no-permit, firm-cap, `Throttled`, `Transient` and unexpected-exception paths before requeue. No-op if the caller is not the owner or the entry is not `LEASED`.
- **Status:** Approved.

### G-22 — Publish failure
- **Recommendation [R]:** If step 5 throws, the handler does not ack. The message reappears after visibility; `claim` returns `PendingPublish`; the executor republishes without loading. After 5 failed receives the queue dead-letters the message; the ledger stays `PENDING_PUBLISH` for reconciliation (A9).
- **Options considered:** immediate requeue with delay (hides a real failure from `maxReceiveCount`; rejected).
- **Status:** Approved.

### G-23 — Poison messages
- **Problem:** A typed queue cannot hold a malformed payload; records reject bad data at construction.
- **Recommendation [R]:** Model poison as any unexpected `RuntimeException` during handling (test: a loader or publisher scripted to throw `IllegalStateException`). Handler: release permit and claim, log IDs, no ack. The queue dead-letters after `maxReceiveCount = 5`. No job outcome is recorded (limitation: such a job stays open until reconciliation; documented).
- **Options considered:** a raw `String` queue with a decoder (more code for no extra insight; rejected).
- **Status:** Approved.

### G-24 — Receive sizing and deferral delays
- **Recommendation [R]:** If the breaker blocks or `freePermits = 0`, skip receiving. Otherwise `receive(min(10, freePermits), wait)`. Delays: no-permit and firm-cap deferral = `firmCapDeferral` (production 30 s) × (1 + jitter[0,1)); `InFlightElsewhere` per G-05. All capped by G-12.
- **Status:** Approved.

### G-25 — `FanoutJobStore` additions for the Planner
- **Recommendation [R]:** Add `Optional<FanoutJob> find(String jobId)`; `boolean tryAcquirePlannerLease(String jobId, String owner, Instant until)`; `void savePage(String jobId, String owner, Cursor next, int flagBatchesSent, int loadsAdded)` (conditional on the lease, atomic); `void markPlanned(String jobId, String owner)`; `void markSuperseded(String jobId)`; `OptionalLong latestGraphRev(String productId, String branchId)`. `find` is added in story 07; the rest in story 14.
- **Options considered:** a separate `PlannerStateStore` (splits one DynamoDB item across two ports; rejected).
- **Status:** Approved.

### G-26 — Envelope fields
- **Ref:** §A envelope ("every message carries `schemaVersion`, `correlationId`, `region`").
- **Recommendation [R]:** Add `schemaVersion` and `region` to `LoadRequest`, `ApplyHeadBatch` and `EngagementLoadFailed` where missing. Additive; other records are unchanged.
- **Status:** Approved.

### G-27 — Breaker scope
- **Options:** (a) one per task; (b) shared per cell.
- **Recommendation [R]:** (a). Matches the static permit split (§F); no shared state. Cost: each task discovers an outage separately (≤ K failures per task).
- **Status:** Approved.

### G-28 — Hard-timeout enforcement
- **Recommendation [R]:** The executor passes `timeout` to the loader **and** schedules a timeout task on the `Scheduler` that interrupts the loading virtual thread. A timed-out call is `Transient`; any late result is discarded. The permit is released only when the loader call actually returns, so the capacity invariant counts real concurrent calls even if a loader ignores interruption (A8).
- **Options considered:** `Future.get(timeout)` (real-time wait, not controllable by `MutableClock`; rejected).
- **Status:** Approved.
