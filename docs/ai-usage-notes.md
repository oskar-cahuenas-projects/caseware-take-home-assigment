# AI Usage Notes

I used Claude (chat, inside a project holding the brief) throughout Part 1, one session per work package, and closed each package into a Markdown file that fed the next session. For Part 2 I used Claude Code (CLI, Opus 5.5) driven by one master prompt ([`fanout-java/prompts/master-prompt.md`](../fanout-java/prompts/master-prompt.md)): a bootstrap that produced requirements, story cards and contract-gap decisions, then one generated prompt per story, each ending in a commit. In both parts I made the decisions; the AI proposed, questioned, calculated, edited and, in Part 2, wrote all the code and tests. The session logs are in [`chat-session-log/`](chat-session-log/).

## Where AI helped

- **Framing and planning.** Breaking the exercise into a work-breakdown structure, and asking clarifying questions before proposing a design.
- **Architecture options.** Proposing the split between a seconds-level hot path and a throttled cold path, and comparing alternatives (read-time vs. publish-time flags, polling vs. push, outbox relay vs. CDC, FIFO vs. standard queues).
- **Failure-mode enumeration.** Duplicates, reordering, withdraw-before-publish, lost events, partial fan-out.
- **Arithmetic and pricing lookups.** Scenario tables and cost formulas, with a source and a confidence level on each figure.
- **Diagrams and editing.** Mermaid diagrams, shortening the document to the page limit, and a final cross-section consistency review.
- **Finding gaps in my own contract (Part 2).** It turned the contract's open points into 29 explicit gap entries with options and a recommendation, for example `markPublished` keyed by `eventId` when the loader returns no sequence (A1 false), and a 5-minute hard timeout that made the 9-minute heartbeat test impossible.
- **Code and tests (Part 2).** In-memory adapters with SQS and DynamoDB semantics, the executor's eight-step order, crash injection at four points, heartbeat and timeout on a manual scheduler, weighted lanes, the Planner, and a deterministic test approach (fake clock and scheduler; real virtual threads only where races matter).
- **Proving the tests catch bugs (Part 2).** For each concurrent component it broke the code on purpose (removed a lock, made a claim non-atomic, removed the firm cap) and confirmed the test failed before restoring it.

## Where I corrected or rejected AI output

| Topic | AI output | What I did |
|---|---|---|
| Summaries | Explored per-firm tailored summaries | Chose generic summaries per version pair: no confidential data in prompts, one text to defend |
| Projection index | An index holding only pending engagements | Required a full, filterable list per firm, so the index covers every status |
| Offer history | Separate Offer records written on the hot path | Chose state on the projection item plus an append-only history from DynamoDB Streams |
| Bulk decisions | Flagged a conflict with per-engagement decisions | Narrowed bulk to "same summary" and deferred it out of v1 |
| SLO draft | Written without reading the closed cost section: 5 cells, WebSocket push, EKS, p95 ≤ 10 s | Rejected and redone against the closed section: 3 cells, polling, Fargate, p95 ≤ 15 s with pre-warm |
| First cost estimate | ~\$130–210 a month, with push and without the read API or the outbox relay | Superseded once polling and the relay were priced: ~\$255–1,475 |
| LLM cost in the summaries draft | \$150–500 a month | Inconsistent with the model's published price; recomputed to ≈ \$13 a month |
| Summary generation region and replication | `ca-central-1` and DynamoDB Global Tables | Conflicted with earlier decisions; kept us-east-1 with S3 replication and regional tables |
| Component labelling | Drew the Template Tracking Service as an existing service | Corrected: the brief names no such service, so it is new |
| LLM model choice | Two earlier candidates | Dropped after retirement dates and context limits were checked; chose a model with a longer availability window |

Part 2:

| Topic | AI output | What I did / what happened |
|---|---|---|
| Git workflow | Created a feature branch before committing | Told it to commit directly on `main`; a branch strategy adds nothing to a take-home |
| Duplicate request mid-load (G-05) | The seeded option acked the duplicate and attached it to the loading worker | The AI itself flagged that a crash would lose the item; approved "requeue until the lease ends" |
| Capacity test E1 | First version spread requests round-robin across firms, so a build without the firm cap still passed | Rewritten so each firm's requests arrive together; the broken build now fails |
| Throttling test E4 | Waited for 1,500 throttled calls, which an open circuit breaker never allows | Changed to assert what E4 is about: every item throttled more than 5 times, none dead-lettered |
| Repeat test runs | Combined a tag filter with a class list, which silently skipped untagged classes | Caught by the AI from the reports; rerun |
| Port signature changes | `freePermits()`, a `requeueWithDelay` overload, and Planner additions (G-29) | I approved the first two explicitly; G-29 was added within the story's scope and still needs my review |

One correction went the other way: I first proposed scaling the load path with more compute. The AI pointed out that the ~1 minute load belongs to another team with limited capacity, which led to the projection design.

## What was verified, and what was not

- **Verified:** the Section 3 arithmetic was re-run from its stated inputs with a script; the model price and EventBridge cross-Region destination support were checked against published sources.
- **Verified (Part 2):** all contract tests E1–E10 and the §D failure rows listed in `fanout-java/docs/requirements.md`; the full suite passed 5 of 5 runs (~10 s); a build-time check that main code never reads the wall clock; a demo run whose numbers match the invariants.
- **Not verified (Part 2):** that the real loader returns `engagementSeq` (A1) or honours interruption (A8), real SQS fair-queue and DynamoDB transaction behaviour, and the Tracking Service, which is a fake.
- **Not verified:** the Amazon Bedrock price for the model, the cost of DynamoDB warm-throughput pre-warming, and the 15 s flag target, which needs a load test. Every figure about Caseware's own systems (loader capacity, cell split, user counts) is a labelled assumption.

## How I would guide other engineers using AI on this system

1. **Write decisions down and feed them forward.** The AI drifted between sessions (cells, compute, latency targets). A closed decision file per section, re-read at the start of the next, caught it.
2. **Demand sources and confidence levels,** and re-run every calculation from its inputs.
3. **Keep humans as owners of semantics and contracts:** what a decline pins, what may leave a region, how much loader capacity another team grants.
4. **Use AI to attack the design,** for example to list failure modes or draft review questions, not only to produce it.
5. **Review generated code and tests for the failure paths first;** the happy path is where AI output is most reliable and least valuable.
6. **Give the AI a spec, a backlog and checkpoints, not a goal.** A master prompt with fixed decisions, one story per run and a stop for approval at each checkpoint kept the code inside the contract and every change reviewable as one commit.
7. **Ask for proof that a test can fail.** A green concurrency test means little until a deliberately broken version turns it red; two of the AI's own tests (E1, E4) were wrong in exactly this way.

## Where AI should not be trusted in this domain

- **Prices, service limits and model retirement dates:** they change monthly and were wrong or stale more than once here.
- **Any claim about Caseware's existing systems:** the AI cannot know them and will fill gaps plausibly.
- **Domain semantics:** version-graph rules must be confirmed by content teams. Tests only prove the rules were implemented.
- **Legal and regulatory interpretation,** including residency and retention.
- **Audit content shown to practitioners:** an LLM summary is never evidence by itself. That is why the design derives it from deterministic facts, gates it on coverage, stores the inputs immutably, and always shows the full change list.
