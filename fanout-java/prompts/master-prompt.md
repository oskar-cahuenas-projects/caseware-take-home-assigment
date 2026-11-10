# Fan-out Worker (Part 2) — Master Prompt

> Target: Claude Code, launched from the repository root (`caseware-take-home-assignment/`).
> Structure: §1 Shared context (read on every run) · §2 Prompt A, Bootstrap (run once) · §3 Prompt B, Story template (one filled copy per story).
> How to run: see `fanout-java/prompts/how-to-run.md`.

---

## §1. Shared context (every run reads and follows this section)

### 1.1 Role

You are a Senior Software Engineer and Technical Lead with strong Java concurrency experience. You work as a disciplined pair: you analyze before you build, you prove invariants with tests, and you choose the simplest design that satisfies the contract. You do not gold-plate.

### 1.2 Goal

Implement Part 2 of the Caseware take-home: the **template-publish fan-out worker**, in Java, under `fanout-java/`.

- **Must:** the **Load Executor** (Stage B), which calls the ~1-minute, capacity-limited `EngagementLoader` and reports observations.
- **Should (cuttable):** a **thin Planner** (Stage A): job creation, candidate paging, supersession. It is built only after the Executor's tests pass.

What reviewers evaluate (from the brief) [F]:
- Handling of concurrency, failure, idempotency and downstream capacity.
- Interfaces, contracts and correctness. Tests and validation logic are a plus.
- "This is not a full application build." "Please do not over-optimize."
- A short README explaining the key implementation tradeoffs.

Time budget for all of Part 2: **2.5–3 hours**.

### 1.3 Sources and precedence

Read these sources. When they disagree, the higher one wins.

| # | Source | Role |
|---|---|---|
| 1 | `docs/Challenge.pdf` | The brief. Facts [F]. |
| 2 | `fanout-java/fanout-worker-contract.md` | **The Part 2 specification**: interfaces I1–I7, Java signatures (§B), step order (§C), failure matrix (§D), 10 prioritized tests (§E), tradeoffs (§F). |
| 3 | `CLAUDE.md` (repository root) | Decided Part 2 constraints. |
| 4 | `fanout-java/docs/contract-gaps.md` | Resolutions of gaps in the contract. Created in Prompt A; **binding once approved**. |
| 5 | `docs/solution-design.md` | The Part 1 design document. |
| 6 | `fanout-java/analysis-documents/` | Background only. `WBS-3.2-unreliable-delivery.md` and `wbs-3.3-failure-recovery-data-residency.md` are the relevant ones. |

Known supersessions (do not follow the older text):
- Ledger key: WBS-3.2 `(engagementId, targetVersion)` and WBS-3.3 `(engagementId, templateId, targetVersion)` are replaced by **`engagementId` + `observedSeq`**.
- WBS-3.3 "token bucket" and "per-firm sub-queues" are replaced by the **semaphore with C/N permits, per-firm cap and weighted lanes**.

If a conflict changes behavior and the table does not settle it, record it in `contract-gaps.md` and ask.

### 1.4 Fixed decisions (do not reopen without asking)

- **Stack:** Java 21, Maven with the wrapper (`./mvnw`), no framework, no runtime dependencies. Test dependencies: JUnit 5 and AssertJ only. No Mockito: use handwritten fakes.
- **Everything in memory.** No AWS SDK, no Docker, no LocalStack, no network. SQS, DynamoDB, the event bus, the Engagement loader and the Tracking Service are all in-memory fakes behind ports.
- **Location:** Maven project in `fanout-java/` (`fanout-java/pom.xml`). Root package `com.caseware.fanout`.
- **Executor step order is load-bearing:** claim ledger → acquire permit → call loader → `recordObservation` → publish → `markPublished` → record job outcome → ack.
- **Ledger** keyed by `engagementId` with `observedSeq`. It doubles as the observation outbox (`PENDING_PUBLISH`).
- **Deferrals** (throttling, fairness, no permit) use `requeueWithDelay` (a new message, receive count resets), never a visibility change. `maxReceiveCount = 5` counts only real failures.
- **Circuit breaker open → the executor stops receiving.** It does not receive and then defer.
- **Concurrency:** virtual threads behind a semaphore. Static split of `C/N` permits per task. Per-firm in-flight cap `max(1, 0.2 × C/N)` per task. Lanes polled with 6:3:1 weights (publish : backfill : reconcile).
- **Assumption A1** (loader returns `engagementSeq`, 60%) may be false. The code must stay safe: without a sequence, observations may only fill `UNKNOWN` fields.
- **Contract names and signatures are kept.** Additions or changes are allowed only when logged in `contract-gaps.md`.
- **Payloads and logs carry IDs and versions only**, never engagement content (data residency rule).

### 1.5 Testing strategy (approved)

- **Hybrid time model.**
  - Concurrency tests use **real virtual threads**, with fake loader latency scaled to milliseconds. Races are the point of these tests.
  - All time-based rules use an injected `java.time.Clock`. Tests use a `MutableClock`. Main code never calls `Instant.now()`, `System.currentTimeMillis()` or `Thread.sleep()` directly.
  - Time-driven background actions (visibility expiry, delayed messages becoming visible, heartbeat, breaker cool-down) must be deterministic under the test clock. The mechanism is decided in Prompt A (recommended: an explicit `Scheduler`/tick abstraction with a manual test implementation).
- **One `ExecutorConfig` record** holds every duration and number. It has production defaults from the contract and a test factory with scaled values. Contract defaults: visibility 3 min; heartbeat every 60 s extending by 3 min; loader hard timeout 5 min; `maxReceiveCount` 5; freshness window W 24 h; lane weights 6:3:1; max requeue delay 15 min (SQS `DelaySeconds`).
- **Fake loader** (`FakeEngagementLoader`): configurable latency; scripted outcomes per engagement or per call (OK, `Throttled(retryAfter)`, `Transient`, `NotFound`, `Permanent`, timeout); counters for calls per engagement, current and peak concurrent calls, total and per firm.
- **Crash injection** at four points: after claim, after load, after `recordObservation`, after publish. A crash abandons the message without ack.
- **Randomness** (jitter) uses an injected, seeded `Random`.
- **No sleeps to wait for outcomes.** Use latches or a small await helper with a timeout from the test kit. Every test has `@Timeout`.
- **Contract tests** (§E of the contract) live in package `com.caseware.fanout.contract`, are tagged `@Tag("contract")` and named `ContractEnnXxxTest` (for example `ContractE01CapacityInvariantTest`), with `@DisplayName("E1 capacity invariant: …")`.
- The full suite runs in under 60 seconds and is never flaky. A test that fails once in five runs is a failing test.

### 1.6 Coding standards

- Ports and adapters. SOLID. Small classes with one reason to change.
- Package layout (adjust only through the design document):
  ```
  com.caseware.fanout
    domain          records, enums, sealed types (contract §B)
    port            I2–I7 and infrastructure ports
    executor        LoadExecutor, lane poller, circuit breaker, backoff, heartbeat
    planner         thin Planner (Epic E6)
    adapter.memory  in-memory adapters
  test: com.caseware.fanout.testkit (MutableClock, FakeEngagementLoader, CrashInjector, Await),
        tests mirroring main packages, and com.caseware.fanout.contract for §E tests
  ```
- Use records, sealed interfaces and exhaustive `switch` pattern matching, so the compiler checks failure classification.
- Immutability by default. No `null` across ports: use `Optional`/`OptionalLong`. Validate in record compact constructors.
- In-memory adapters mimic conditional writes atomically (`ConcurrentHashMap.compute` or explicit locks). State each class's thread-safety in one line of Javadoc.
- No static mutable state. Constructor injection. One small wiring/factory class instead of a framework.
- Logging with `System.Logger`, including `correlationId`, `jobId`, `engagementId`.
- Javadoc on ports and invariants only. No speculative abstractions, no unused interfaces, no dead code. A `TODO` requires a note in the story card.
- Patterns only where they earn their place: Adapter (ports), State (breaker), Strategy (backoff, lane selection).

### 1.7 Working rules

- Tag claims in documents: **[F]** fact from the brief, **[C]** contract or decided constraint, **[A n%]** assumption with confidence, **[R]** recommendation.
- **Ask before:** changing a contract signature, adding a dependency, reopening a §1.4 decision, exceeding a story's scope, or any destructive or remote git operation (no push, no force, no history rewrite).
- **Build gate:** `cd fanout-java && ./mvnw -q verify` is green at the end of every story.
- Keep in sync: the story card, `fanout-java/stories/index.md`, the traceability matrix in `fanout-java/docs/requirements.md`, and the commands section of the root `CLAUDE.md`.
- Every run ends with a short report (≤ 15 lines): files changed, test results, decisions, deviations, open questions. No step-by-step narration.

---

## §2. Prompt A — Bootstrap (run once; phases 1–5)

Execute the phases below in order. **Do not write Java code in this prompt. Do not create per-story prompts.** Read the contract and the brief in full before Phase 1.

### Phase 1 — Problem analysis

**Output:** `fanout-java/docs/requirements.md`, sections 1–3.

1. Problem summary (≤ 10 lines).
2. Scope:
   - In: Load Executor, thin Planner, in-memory adapters and fakes, tests, README.
   - Out: real AWS, Tracking Service implementation, projection, UI, LLM summaries, LocalStack.
3. Assumptions (A1–A5 from the contract, with confidence, plus any new ones), constraints, and edge cases. Derive edge cases from contract §D and add your own, for example:
   - duplicate delivery while a load is in flight;
   - lease expiry during a load;
   - `retryAfter` longer than the 15-minute maximum delay;
   - `C` not divisible by `N`; per-firm cap when `C/N < 5`;
   - empty lanes; breaker half-open probe;
   - observation showing a different template or branch;
   - a job closing when some items were coalesced;
   - A1 false (no `engagementSeq`).
4. Ambiguities: each becomes an entry in `contract-gaps.md` (Phase 4).

**Exit criteria:** every contract section §A–§F is referenced at least once.

### Phase 2 — Requirements engineering

**Output:** `fanout-java/docs/requirements.md`, sections 4–6.

- **Functional requirements:** table with ID (`FR-nn`), requirement, source, testable acceptance criterion, verified by (test name, filled in later).
- **Non-functional requirements:** capacity invariant (peak loader calls ≤ C, per firm ≤ cap); no lost work; at most one extra load per crash; determinism (no flaky tests); suite runtime < 60 s; IDs-only payloads and logs; minimal observability (in-memory counters: permits in use, coalesced count, DLQ count, breaker state) as Could.
- **Technical dependencies:** JDK 21, Maven wrapper, JUnit 5, AssertJ.
- **Traceability matrix:** FR → story → test. Leave the test column empty; stories fill it.

### Phase 3 — Epics and user stories

**Outputs:** one file per story, `fanout-java/stories/NN-story-slug.md` (two-digit number, kebab-case slug), and `fanout-java/stories/index.md` linking every story.

Story card format (no time estimates):

```markdown
# NN — Story title

- **Epic:** E? Name
- **Priority:** Must | Should | Could
- **Status:** To do | In progress | Done | Dropped
- **Depends on:** NN, NN | None
- **Contract refs:** §B …, §C …, §D rows …
- **Contract tests:** E1, E2 | None
- **Checkpoint after:** None | CP3

## User story
As a <role>, I want <capability>, so that <value>.

## Context
<Why this story exists; links to FRs and gap decisions.>

## Acceptance criteria
- AC1 — Given … When … Then … (→ FR-nn, contract test En)

## Tasks
- [ ] …

## Out of scope
- …

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
```

`index.md` contains: the epic list; a table (number, linked story title, epic, priority, status, contract tests, checkpoint); and the milestones from Phase 5.

**Proposed backlog** (refine it; do not expand it without a reason; keep it ≤ 20 stories):

| NN | Slug | Epic | Priority | Contract tests | Summary |
|---|---|---|---|---|---|
| 01 | create-maven-project | E0 Setup | Must | — | Maven wrapper, Java 21, JUnit 5, AssertJ, Surefire, package skeleton, smoke test. Add build/test commands to root `CLAUDE.md`. |
| 02 | domain-model-and-ports | E1 Domain & ports | Must | — | Contract §B records, enums, sealed types and ports, plus the approved gap types. Validation tests. |
| 03 | clock-config-and-testkit | E2 Adapters & test kit | Must | — | `MutableClock`, `ExecutorConfig` (production defaults + test profile), scheduler/tick mechanism, await helper. |
| 04 | in-memory-work-queue | E2 | Must | — | Three lanes; visibility timeout; receive count; ack; `extendVisibility`; `requeueWithDelay` (new message, count reset, 15-min cap); `deadLetter`; `maxReceiveCount` → DLQ. |
| 05 | in-memory-load-ledger | E2 | Must | groundwork E2, E3 | `claim` outcomes, lease, freshness W, `knownSeq`, attached job IDs, `recordObservation` → `PENDING_PUBLISH`, `markPublished` → `DONE`; atomic. |
| 06 | concurrency-limiter | E2 | Must | groundwork E1 | C/N semaphore plus per-firm cap; `Permit` released exactly once. |
| 07 | job-store-publisher-and-fake-loader | E2 | Must | E8 (duplicate outcomes) | `FanoutJobStore` with idempotent outcomes and exact counters; in-memory observation publisher with failure injection; `FakeEngagementLoader`; `CrashInjector`. |
| 08 | executor-core-step-order | E3 Executor core | Must | E1, E2 | Receive loop on virtual threads; the 8-step order; claim branches (coalescing); harness with N executor instances; A1 fallback. |
| 09 | crash-recovery-and-outbox | E3 | Must | E3 | Crash at the four points; `PendingPublish` republishes without reloading; ≤ 1 extra load; no lost item. |
| 10 | throttling-backoff-circuit-breaker | E4 Resilience | Must | E4 | Error classification; backoff with jitter via requeue; breaker stops receiving; recovery after throttling ends; zero DLQ. |
| 11 | permanent-errors-and-dlq | E4 | Must | E6 | `NotFound`/`Permanent` → `EngagementLoadFailed` + DLQ + job `dlq++`; job closes on `done + dlq = expected`; poison message. |
| 12 | visibility-heartbeat | E4 | Must | E7 | Heartbeat extends visibility; a load lasting 3× visibility is never processed twice; hard timeout → `Transient`. |
| 13 | weighted-lanes-and-fairness | E5 Lanes & fairness | Must | E5 | 6:3:1 work-conserving lane polling; per-firm cap deferral; fairness bound. **Checkpoint CP3.** |
| 14 | thin-planner | E6 Planner (stretch) | Should | E8 (duplicate events), E9 | `createIfAbsent`, `plannerLease`, paging over in-memory `CandidateSource`, cursor saved after sends, `ApplyHeadBatch` ≤ 100 to an in-memory sink, `LoadRequest` for UNKNOWN rows, supersession by `graphRev`. |
| 15 | tracking-service-contract-test | E6 | Should | E10 | In-memory fake Tracking Service consumer (sequence guard + `eventId` dedupe) verifying the events we publish. |
| 16 | full-test-run-and-review | E7 Validation & review | Must | all | Full suite × 5; traceability and §D coverage; review report; fix list. |
| 17 | readme-and-docs | E8 Documentation | Must | — | `fanout-java/README.md` (tradeoffs), root `CLAUDE.md` status and commands, root `README.md` Part 2 row, AI-usage draft. |

Rules: each story is independently verifiable and ends with a green build; Must stories cover contract tests E1–E7.

### ⛔ CHECKPOINT 1 — stop and wait for approval

Present: scope summary, FR/NFR counts, the backlog table, the top three risks, and the list of gaps that will go into `contract-gaps.md`. Continue to Phase 4 only after explicit approval; apply any corrections first.

### Phase 4 — Architecture and design

**Outputs:**

1. **`fanout-java/docs/contract-gaps.md`.** One entry per gap: ID (`G-nn`), contract reference, problem, options (≥ 2), recommendation, status (`Proposed` → `Approved`). Seed it with these recommendations [R] and add any others found:
   - **G-01 Observation publisher port (I6):** `ObservationPublisher` with `publish(EngagementStateObserved)` and `publish(EngagementLoadFailed)`; the in-memory implementation records events and can fail on demand.
   - **G-02 Permit:** `Permit extends AutoCloseable`, released in `finally`, release idempotent.
   - **G-03 Circuit breaker:** CLOSED / OPEN / HALF_OPEN; opens after K consecutive `Throttled`/`Transient` (default 5); cool-down on the clock; one probe in HALF_OPEN; the executor checks it before every receive.
   - **G-04 Claim payloads:** `Claimed(leaseUntil)`, `AlreadyFresh(observedSeq, observedAt)`, `InFlightElsewhere(ownerWorkerId, leaseUntil)`, `PendingPublish(state, jobIds)`.
   - **G-05 Coalescing vs. job accounting:** `AlreadyFresh` → `recordOutcome(job, COALESCED)`, counted as done. `InFlightElsewhere` → attach the job ID to the ledger entry and ack; the worker that loads records the outcome (DONE or DLQ) for every attached job and publishes them in `jobIds`. Alternative: requeue with delay until the entry is fresh (simpler, more messages).
   - **G-06 A1 false:** `engagementSeq` becomes `OptionalLong`; fallback `eventId = engagementId:obs:<requestId>`; event marked `seqKnown=false` (consumer fills `UNKNOWN` only); ledger freshness by `observedAt` only.
   - **G-07 Outcome and FailureReason:** `Outcome { DONE, COALESCED, DLQ }`; `FailureReason { NOT_FOUND, PERMANENT }`; `recordOutcome` idempotent per `(jobId, engagementId)`; close rule `loadsDone (incl. COALESCED) + loadsDlq = loadsExpected`.
   - **G-08 Undefined domain types:** minimal `DecisionRef`, `Page<T>`, `Cursor`, `ProjectionStatus { KNOWN, UNKNOWN, UNVERIFIED }`.
   - **G-09 Fairness bound for test E5:** a concrete bound, in waves of loader latency, derived from C, N and the firm cap; the test asserts it.
   - **G-10 Time-driven actions:** the scheduler/tick mechanism under the test clock.
   - **G-11 Crash simulation:** the crashed executor instance is discarded with its permits (models process death); the message is not acked; the lease and visibility expire via the clock; a new instance takes over.
   - **G-12 Requeue delay cap:** delays capped at 15 minutes; a longer `retryAfter` is capped and documented.
   - **G-13 Lane weighting:** weighted round-robin over 10 slots, skipping empty lanes (work-conserving).
   - **G-14 Ledger lease:** loader hard timeout plus a margin.
   - **G-15 N tasks:** N `LoadExecutor` instances in one JVM sharing queues, ledger and job store, each with its own `C/N` limiter; remainder permits when `C % N ≠ 0` are unused and documented.
2. **`fanout-java/docs/design.md`** (≤ 2 pages plus diagrams):
   - Component diagram (Mermaid) and package layout.
   - Responsibilities of each main class.
   - Executor sequence diagram (Mermaid): the 8 steps and the failure branches.
   - Concurrency model: threads, permits, firm cap, lane polling, breaker.
   - Time model: clock and scheduler.
   - In-memory adapter semantics mirroring AWS: visibility, receive count, DLQ, delay cap, conditional writes.
   - Crash simulation model.
   - Key tradeoffs with rejected alternatives.
3. Update story cards affected by the design.

### Phase 5 — Implementation plan

**Output:** milestones in `fanout-java/stories/index.md`, and the `Checkpoint after` field set on each card.

| Milestone | Stories | Verifiable result |
|---|---|---|
| M1 | 01 | Build green, commands in `CLAUDE.md` |
| M2 | 02–07 | Domain, ports, adapters and test kit, unit-tested |
| M3 | 08–09 | Contract tests E1, E2, E3 green |
| M4 | 10–13 | Contract tests E4, E5, E6, E7 green → **CP3** |
| M5 | 14–15 | Contract tests E8, E9, E10 green (stretch) |
| M6 | 16–17 | Full validation report and README |

### ⛔ CHECKPOINT 2 — stop and wait for approval

Present: a design summary, the gap decisions needing approval (table), and the changes made to stories. After approval, set the approved gaps to `Approved` and end the bootstrap. No production code is written in Prompt A.

---

## §3. Prompt B — Story execution template (phases 6–8 for one story)

**How to instantiate:** copy the block between `BEGIN STORY PROMPT` and `END STORY PROMPT`, replace every `{{…}}` placeholder with values from the story card and `index.md`, and save it as `fanout-java/prompts/stories/{{STORY_NUMBER}}-{{STORY_SLUG}}.prompt.md`. Change nothing else. Write `None` for any empty value.

| Placeholder | Value source | Example |
|---|---|---|
| `{{STORY_NUMBER}}` | Card file name | `08` |
| `{{STORY_SLUG}}` | Card file name | `executor-core-step-order` |
| `{{STORY_TITLE}}` | Card title | `Executor core and step order` |
| `{{EPIC}}` | Card | `E3 Executor core` |
| `{{PRIORITY}}` | Card | `Must` |
| `{{DEPENDS_ON}}` | Card | `02, 03, 04, 05, 06, 07` |
| `{{CONTRACT_REFS}}` | Card | `§B, §C step order, §D rows 4–6` |
| `{{CONTRACT_TESTS}}` | Card | `E1, E2` |
| `{{CHECKPOINT}}` | Card | `None` or `CP3` |
| `{{COMMIT_MODE}}` | Your choice | `commit` or `stage-only` |
| `{{EXTRA_INSTRUCTIONS}}` | Your choice | `None` |

<!-- BEGIN STORY PROMPT -->
```markdown
# Story {{STORY_NUMBER}} — {{STORY_TITLE}}

Read §1 (Shared context) of `fanout-java/prompts/master-prompt.md` and follow it. Then execute this story only.

| Field | Value |
|---|---|
| Story card | `fanout-java/stories/{{STORY_NUMBER}}-{{STORY_SLUG}}.md` |
| Epic | {{EPIC}} |
| Priority | {{PRIORITY}} |
| Depends on | {{DEPENDS_ON}} |
| Contract refs | {{CONTRACT_REFS}} |
| Contract tests | {{CONTRACT_TESTS}} |
| Checkpoint after | {{CHECKPOINT}} |
| Commit mode | {{COMMIT_MODE}} |
| Extra instructions | {{EXTRA_INSTRUCTIONS}} |

## Step 0 — Preconditions
- Confirm every story in "Depends on" is `Done` in `fanout-java/stories/index.md`. If not, stop and report.
- Except for story 01, run `cd fanout-java && ./mvnw -q verify`. If it is red, stop and report.
- Read the story card, the contract sections in "Contract refs", the approved entries in `fanout-java/docs/contract-gaps.md` and the relevant parts of `fanout-java/docs/design.md`.
- Set the card status to `In progress`.

## Step 1 — Plan
- Write a plan of at most 10 bullets: files to add or change, tests to write, risks.
- Proceed without waiting, unless the story needs a decision not covered by approved gaps or the design, changes a contract signature, adds a dependency, or exceeds the card's scope. In those cases, stop and ask.

## Step 2 — Tests first
- For each acceptance criterion, write the test first (contract tests follow §1.5 naming and tagging).
- Confirm each new test fails for the right reason (a compile failure for not-yet-existing types is acceptable).

## Step 3 — Implement
- Write the minimum code that makes the tests pass, following §1.6.
- Preserve the executor step order and every §1.4 decision.
- Do not implement other stories' work. If a stub is unavoidable, note it in the card.

## Step 4 — Validate
- `cd fanout-java && ./mvnw -q verify` is green.
- If the story touches concurrency (queue, ledger, limiter, executor, planner), run the affected test classes 5 times in a row; all 5 runs must pass.
- Map every acceptance criterion to a passing test.

## Step 5 — Review
Fix anything that fails this checklist before closing:
- [ ] Every acceptance criterion has a passing test; failure paths are tested, not only the happy path.
- [ ] Contract names and signatures unchanged, or the change is logged in `contract-gaps.md`.
- [ ] No `Instant.now()`, `System.currentTimeMillis()` or `Thread.sleep()` in main code.
- [ ] Permits are released on every path; no message is left without ack, requeue or dead-letter unless a crash is being simulated.
- [ ] Logs and payloads carry IDs only.
- [ ] No dead code, unused abstractions or unexplained `TODO`.

## Step 6 — Close
- Update the card: tick tasks, set status `Done`, record deviations in "Notes / decisions".
- Update the status in `fanout-java/stories/index.md` and the test column of the traceability matrix in `fanout-java/docs/requirements.md`.
- If build or test commands changed, update the commands section of the root `CLAUDE.md`.
- Commit mode `commit`: stage only this story's files and commit with `<type>(fanout): {{STORY_NUMBER}} {{STORY_TITLE}}`, where type is `feat`, `test`, `build` or `docs`. Do not push.
- Commit mode `stage-only`: stage the files and show the proposed commit message.

## Step 7 — Report
- At most 15 lines: files changed, tests (passed / total, new), decisions, deviations, risks, next story.
- If "Checkpoint after" is `CP3`, also present, then **stop and wait for approval**:
  - status of contract tests E1–E10;
  - contract §D failure matrix coverage (each row → test, or "not covered");
  - open gaps and risks;
  - recommendation: continue with Epic E6 (stories 14–15) or go straight to stories 16–17.
```
<!-- END STORY PROMPT -->

### Story-specific notes for the template

- **Story 16 (full test run and review):** run the full suite 5 times; complete the traceability matrix; add a §D coverage table and a review section to `fanout-java/docs/requirements.md` (requirements met, gaps, improvements, what was deliberately left out).
- **Story 17 (README and docs):**
  - `fanout-java/README.md`, at most about 1.5 pages: purpose and scope; how to build and run the tests; package layout; how the code maps to the contract (ports, step order, tests E1–E10); **key implementation tradeoffs** (table: decision, rejected alternative, why, cost); what is simulated in memory versus what it would be on AWS; assumptions (A1 first); what was left out; known limitations. Link the design document instead of repeating it.
  - Root `CLAUDE.md`: update the Part 2 status, scope (Executor plus thin Planner) and commands.
  - Root `README.md`: update the Part 2 row only.
  - Write a draft Part 2 AI-usage section in `fanout-java/docs/ai-usage-part2-draft.md`. Do **not** edit `ai-usage-notes.md`; the owner writes those judgments.
