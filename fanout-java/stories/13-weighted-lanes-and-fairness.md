# 13 — Weighted lanes and fairness

- **Epic:** E5 Lanes & fairness
- **Priority:** Must
- **Status:** Done
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
- [x] `LanePoller` (weighted round-robin strategy).
- [x] `ContractE05FairnessTest`.
- [x] CP3 report.

## Out of scope
- SQS fair-queue simulation beyond FIFO-per-lane (the per-firm cap provides the bound).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC → test: AC1 `LanePollerTest#cycleIsSmoothSixThreeOne`, `#firstChoicesFollowWeights`, `LaneQueuesTest#weightedWhenAllLanesBusy` (10 single-message receives through the executor → 6:3:1 loads); AC2 `LanePollerTest#orderCoversAllLanesForWorkConservation`, `LaneQueuesTest#workConserving`, `#singleBusyLane`; AC3 `FirmCapDeferralTest`; AC4 `ContractE05FairnessTest`. 5/5 runs green.
- New `LaneQueues` (composite `WorkQueue<LoadRequest>`) puts the three lanes behind the existing `ExecutorPorts.queue`, so `LoadExecutor` and `ExecutorPorts` are unchanged. Operations other than receive route by `LoadRequest.lane`, so deferrals and retries stay in their lane. Added to `design.md` §2 and §4.
- Weighting is per receive call (one slot per receive), not per message. A receive returns the first non-empty lane's batch only, and does not top up from other lanes. Only the last lane tried gets the long-poll `wait`.
- SWRR breaks ties by enum order, which gives P B P P B P R P B P. `design.md` gave an illustrative pattern (P B P R …), now corrected.
- AC3 behaviour (firm cap → release, deferral `firmCapDeferral × [1, 2)`) already came from story 08's no-permit branch; `FirmCapDeferralTest` pins it for the firm-cap cause specifically.
- E5 runs in real time with 50 ms loader latency, so that latency far exceeds the time to scan the 40k backlog, as 60 s does in production. Measured worst small-firm first-start rank over 5 runs: 987–1,018 against the bound of 1,350. Main risk: on a heavily loaded machine the scan slows and the big firm gains starts. The headroom is about 6 waves.
- NFR-10 (Could): there is no coalesced-count accessor. Permits in use, DLQ and breaker state are readable. Not built.
- Test kit: `ExecutorHarness` gains `backfill`, `reconcile`, `lanes` and `send(Lane, …)`. `h.queue` stays the publish lane.
