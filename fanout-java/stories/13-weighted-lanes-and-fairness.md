# 13 — Weighted lanes and fairness

- **Epic:** E5 Lanes & fairness
- **Priority:** Must
- **Status:** To do
- **Depends on:** 08
- **Contract refs:** §C lanes and fairness, §D row 15
- **Contract tests:** E5
- **Checkpoint after:** CP3

## User story
As a small firm, I want my loads to start soon even when a 40k firm floods the queue, so that my pending-update flags appear within a bounded wait.

## Context
Covers FR-01, FR-06, NFR-10. Applies G-09, G-13, G-24.

## Acceptance criteria
- AC1 — Given all lanes non-empty, Then over 10 receive slots lanes are polled 6:3:1 (→ FR-01).
- AC2 — Given an empty lane, Then its slots go to non-empty lanes (work-conserving) (→ FR-01, EC-14).
- AC3 — Given a firm at its cap, Then its claimed request is released and requeued with short delay + jitter (→ FR-06, EC-20).
- AC4 — Given one 40k firm and 100 firms of 40, Then every small firm's first loader call is within the first 1,350 call starts (G-09: C = 50, N = 2, cap 5) (→ E5).

## Tasks
- [ ] `LanePoller` (weighted round-robin strategy).
- [ ] `ContractE05FairnessTest`.
- [ ] CP3 report.

## Out of scope
- SQS fair-queue simulation beyond FIFO-per-lane (the per-firm cap provides the bound).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
