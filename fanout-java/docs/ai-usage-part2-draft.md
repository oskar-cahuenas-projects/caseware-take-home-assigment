# AI usage in Part 2: draft

> Written by the AI in story 17 for the owner to edit. It records **what happened**. The judgments (what was useful, what to distrust, how to guide others) are the owner's to write in `ai-usage-notes.md`. Lines marked **[owner]** need the owner's input.

## Setup

- **Tool:** Claude Code (CLI) on the Opus 5.5 model, run from the repository root.
- **Driver:** one master prompt, [`prompts/master-prompt.md`](../prompts/master-prompt.md). It has three parts: shared context (role, sources and their precedence, fixed decisions, testing strategy, coding standards), a bootstrap prompt, and a story template. [`prompts/how-to-run.md`](../prompts/how-to-run.md) gives the operating steps.
- **Bootstrap (Prompt A), no code:**
  - The AI wrote the requirements, the 17 story cards, the contract gaps (G-01 … G-28) and the design.
  - The owner approved at two checkpoints: the backlog and risks, then the gap decisions.
- **Per story (Prompt B):**
  - The AI filled the template from the card and saved it under [`prompts/stories/`](../prompts/stories/).
  - It then ran it: preconditions, plan, tests first, implementation, the build gate, 5× runs for concurrency tests, a review checklist, closing (card, index, traceability), a commit and a ≤ 15-line report.
- **Checkpoint 3 (after story 13):** status of E1–E10, §D coverage, and a recommendation. The owner chose to build every story.

## What the AI did

- Turned the contract's open points into explicit gap entries with options and a recommendation, for example the ledger key, A1-false handling, deferral by requeue, receive sizing, and hard-timeout enforcement.
- Wrote all main and test code: in-memory adapters mirroring SQS and DynamoDB semantics, the executor's 8-step order, crash injection at four points, the heartbeat and timeout on a manual scheduler, SWRR lanes, the Planner, and the consumer contract test.
- Designed the testing approach the owner approved: deterministic tests on `MutableClock` + `ManualScheduler`, and real-time tests on virtual threads only where races matter. It replaced a wall-clock fairness bound with a rank bound (G-09).
- Measured rather than assumed. Examples: the worst fairness rank over 5 runs (≈ 1,000 against a bound of 1,350); 5× full-suite runs with timings; checking that every test named in the traceability matrix exists.

## Corrections and deviations seen during the work

| Where | What happened | Resolution |
|---|---|---|
| G-24 (after story 06) | The executor needed `freePermits()` on the limiter port, which is a signature change | The owner approved it before story 08 |
| Bootstrap (G-18), applied in story 12 | 3 × visibility (9 min) exceeds the 5-min hard timeout, so E7 couldn't run with contract defaults | E7 runs with a 10-min timeout; visibility and heartbeat stay as in the contract |
| Story 13 | `design.md` showed a lane pattern that SWRR does not produce | Corrected to the real cycle P B P P B P R P B P |
| Story 13 | One AI-written test received a batch of 2 where it expected 1 | Caught by the red run and fixed in the test |
| Story 14 | The Planner needed a producer port, a `graphRev` write path and an end-of-paging marker the contract doesn't name | Logged as G-29 and applied within the card's scope; **flagged for owner review** |
| Order after CP3 | The AI had recommended 16–17 before 14; with all stories in scope it ran 15 → 14 → 16 → 17 so the review covered the Planner | Stated to the owner at the time |
| **[owner]** | Anything you changed, rejected or overrode in stories 01–11 or during the bootstrap | |

## Verified vs. not verified

- **Verified by running:** all contract tests E1–E10, the §D rows listed in `requirements.md` §7, 5/5 full-suite runs, and no wall-clock calls in main code.
- **Not verified (assumptions or simulated):**
  - A1 (the loader returns `engagementSeq`);
  - real SQS fair-queue behaviour (the cap is what provides fairness here);
  - DynamoDB transaction limits;
  - loader interrupt behaviour (A8);
  - the Tracking Service, which is a fake.

## Open for the owner

- **[owner]** Your judgment on where AI help was strong or weak in Part 2, and how you would brief another engineer to use this prompt setup.
- **[owner]** Whether the per-story prompt files and commits should be kept as the session history for Part 2, or summarized.
