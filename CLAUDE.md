# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A Caseware take-home assignment in two parts:

- **Part 1, the design (done):** a system that flags engagements when a new template version is published, without loading engagements on any user-facing path. Each engagement load costs ~1 min through another team's capacity-limited loader. The design is written in Markdown with Mermaid diagrams.
- **Part 2, the code (in progress):** a Java 21 implementation of the **Load Executor** (Stage B of the fan-out worker), in `fanout-java/`. Stories and status: `fanout-java/stories/index.md`.

## Commands (Part 2)

Run from `fanout-java/`. Maven wrapper pins Maven 3.9.10; JDK 21 required.

- Full build and tests (the build gate): `./mvnw -q verify`
- Tests only: `./mvnw -q test`
- One test class: `./mvnw -q test -Dtest=BuildSmokeTest` (one method: `-Dtest=BuildSmokeTest#runsOnJava21`)
- Contract tests only (§E, `@Tag("contract")`): `./mvnw -q test -Dgroups=contract`
- Everything except contract tests: `./mvnw -q test -DexcludedGroups=contract`
- Reports: `target/surefire-reports/`

## Layout and source of truth

- `docs/solution-design.md`: the final, consolidated design document (5 sections). **This is the deliverable.** When it disagrees with an analysis file, the design document wins.
- `fanout-java/fanout-worker-contract.md`: the **Part 2 specification**. It defines the interfaces I1–I7, the Java record and interface signatures, the executor step order, the failure matrix, and the 10 prioritized tests. Read it before writing any Part 2 code.
- `fanout-java/analysis-documents/`: one closed file per WBS work package (1.1 to 6). They were inputs to the design document. Where two of them disagree, the later section was applied during the WBS 8 review. Two that still matter for Part 2: `WBS-3.2-unreliable-delivery.md` (idempotency and outbox) and `wbs-3.3-failure-recovery-data-residency.md`.
- `docs/chat-session-log/log-chat-design-document.md`: the AI session log. Its "Open items" section lists unresolved questions.
- `ai-usage-notes.md` (repository root) and `docs/ai-usage-notes.md` are identical copies. Edit both, or ask which one to keep.
- `caseware-design-*.pdf`: exported renderings of the design. They are not regenerated automatically.

## Diagrams

Each Mermaid diagram exists in three places: inline in `docs/solution-design.md` (Figures 1–4), as a source file in `docs/diagrams/mermaid/image-0N.mmd`, and as a rendered `docs/img/image-0N.png`, which the design links to. When you change a diagram, keep all three in sync. The node style classes are `existing` (grey) and `new` (blue). Line styles: thick = hot path, dotted = cold path, thin = user path.

## Part 2 constraints (decided; don't reopen without asking)

- Java 21, **no framework**. Use in-memory adapters behind ports (`WorkQueue`, `LoadLedger`, `ConcurrencyLimiter`, `FanoutJobStore`, `EngagementLoader`). Use no AWS SDK and no Docker. LocalStack with Docker Compose is an optional extra only.
- Tests use a deterministic `Clock` and a fake loader with controllable latency and failures. Implement them in the priority order from section E of the contract. The capacity invariant (peak concurrent loader calls ≤ C) comes first.
- The executor step order is load-bearing: claim ledger → acquire permit → call loader → `recordObservation` → publish → `markPublished` → record job outcome → ack.
- The ledger is keyed by `engagementId` with `observedSeq`, **not** `(engagementId, targetVersion)`. The contract supersedes 3.2 on this point.
- Throttling and fairness deferrals use `requeueWithDelay` (a new message), never a visibility change, so that `maxReceiveCount` counts only real failures. When the circuit breaker is open, the executor stops receiving.
- Concurrency uses virtual threads behind a semaphore: a static split of C/N permits per task, plus a per-firm in-flight cap of `max(1, 0.2 × C/N)`. Lanes are polled with 6:3:1 weights (publish : backfill : reconcile).
- Assumption A1, "the loader returns `engagementSeq`" (60% confidence), is the riskiest. Code must stay safe if A1 is false: observations then only fill `UNKNOWN` fields.

## Writing conventions for the design documents

- Tag claims as **[F]** (a fact from the brief) or **[A n%]** (an assumption with a confidence level). Give prices and AWS limits a source and a confidence level.
- The design must stay within the page limit (the text-only PDF measures ≈ 3.5 pages), so make edits concise rather than additive.
- Anything about Caseware's existing systems (loader capacity, cell split, whether the Template Tracking Service already exists) is an assumption. Don't present it as fact.
