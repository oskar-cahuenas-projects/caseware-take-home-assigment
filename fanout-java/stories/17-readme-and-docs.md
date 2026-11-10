# 17 — README and docs

- **Epic:** E8 Documentation
- **Priority:** Must
- **Status:** To do
- **Depends on:** 16
- **Contract refs:** §F
- **Contract tests:** None
- **Checkpoint after:** None

## User story
As a reviewer, I want a short README with the key tradeoffs, so that I understand the implementation choices in five minutes.

## Context
Brief deliverable [F]. See master prompt §3 "Story 17" notes.

## Acceptance criteria
- AC1 — Given `fanout-java/README.md` (≤ ~1.5 pages), Then it covers scope, build/run, package layout, contract mapping (ports, step order, E1–E10), tradeoffs table, in-memory vs AWS, assumptions (A1 first), left out, limitations.
- AC2 — Given root `CLAUDE.md`, Then Part 2 status, scope and commands are current.
- AC3 — Given root `README.md`, Then only the Part 2 row changed.
- AC4 — Given `fanout-java/docs/ai-usage-part2-draft.md`, Then a draft exists; `ai-usage-notes.md` is untouched.

## Tasks
- [ ] README, CLAUDE.md, root README row, AI-usage draft.

## Out of scope
- Editing `ai-usage-notes.md`.

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
