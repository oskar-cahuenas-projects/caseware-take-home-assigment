# 12 — Visibility heartbeat and hard timeout

- **Epic:** E4 Resilience
- **Priority:** Must
- **Status:** To do
- **Depends on:** 08
- **Contract refs:** §C visibility heartbeat
- **Contract tests:** E7
- **Checkpoint after:** None

## User story
As the platform, I want visibility extended while a load runs and a hard timeout on the call, so that slow loads are never processed twice and stuck loads don't hold permits forever.

## Context
Covers FR-07, FR-08. Applies G-10, G-18, G-28.

## Acceptance criteria
- AC1 — Given a load running 3 × visibility (config per G-18), Then the message is never delivered to another worker and the loader is called once (→ FR-08, E7).
- AC2 — Given the heartbeat interval, Then each tick calls `extendVisibility(d, 3 min)`; the heartbeat is cancelled on every exit path (→ FR-08, EC-27).
- AC3 — Given a load exceeding the hard timeout (timeout task fires on the `Scheduler` and interrupts the load), Then it is treated as `Transient`; the permit is released only when the loader call returns; permit and claim released, requeued (→ FR-07, EC-09).

## Tasks
- [ ] `Heartbeat` on the `Scheduler`.
- [ ] Hard-timeout enforcement per G-28.
- [ ] `ContractE07HeartbeatTest`.

## Out of scope
- SQS 12-h visibility ceiling (not reachable with a 5-min hard timeout).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
