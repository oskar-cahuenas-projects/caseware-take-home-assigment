# Story 11 — Permanent errors and DLQ

Read §1 (Shared context) of `fanout-java/prompts/master-prompt.md` and follow it. Then execute this story only.

| Field | Value |
|---|---|
| Story card | `fanout-java/stories/11-permanent-errors-and-dlq.md` |
| Epic | E4 Resilience |
| Priority | Must |
| Depends on | 08 |
| Contract refs | §C flow (NotFound/Permanent branch), §D rows 13, 14, §B FanoutJob close rule |
| Contract tests | E6 |
| Checkpoint after | None |
| Commit mode | commit |
| Extra instructions | None |

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
- Commit mode `commit`: stage only this story's files and commit with `<type>(fanout): 11 Permanent errors and DLQ`, where type is `feat`, `test`, `build` or `docs`. Do not push.
- Commit mode `stage-only`: stage the files and show the proposed commit message.

## Step 7 — Report
- At most 15 lines: files changed, tests (passed / total, new), decisions, deviations, risks, next story.
- If "Checkpoint after" is `CP3`, also present, then **stop and wait for approval**:
  - status of contract tests E1–E10;
  - contract §D failure matrix coverage (each row → test, or "not covered");
  - open gaps and risks;
  - recommendation: continue with Epic E6 (stories 14–15) or go straight to stories 16–17.
