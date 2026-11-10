# 04 — In-memory work queue

- **Epic:** E2 Adapters & test kit
- **Priority:** Must
- **Status:** To do
- **Depends on:** 03
- **Contract refs:** §A I4, §B WorkQueue/Delivery, §C "receive count is reserved for real failures", §D row 14
- **Contract tests:** None
- **Checkpoint after:** None

## User story
As the executor, I want a queue that behaves like SQS standard, so that visibility, receive counts and DLQ behave as they would in production.

## Context
Covers FR-20, FR-18. Applies G-12, G-23.

## Acceptance criteria
- AC1 — Given a received message not acked, When visibility expires on the clock, Then it is receivable again with `receiveCount + 1` (→ FR-20).
- AC2 — Given `extendVisibility`, Then the message stays invisible until the new deadline (→ FR-08).
- AC3 — Given `requeueWithDelay(d, 20 min)`, Then the old message is deleted and a new one appears after 15 min with `receiveCount = 1` on first receive (→ FR-20, G-12).
- AC4 — Given a message received for the 6th time with `maxReceiveCount = 5`, Then it moves to the lane DLQ instead of being delivered (→ FR-18).
- AC5 — Given `deadLetter(d, reason)`, Then the message is in the DLQ with its reason and gone from the queue.
- AC6 — Given a stale receipt (after redelivery), When acked, Then the call is a no-op (SQS receipt semantics).
- AC7 — Given concurrent receivers, Then no message is delivered to two receivers within one visibility window.

## Tasks
- [ ] `InMemoryWorkQueue<T>` (one per lane) with DLQ.
- [ ] Unit tests under `MutableClock`; concurrency test for AC7.

## Out of scope
- Lane weighting (story 13).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
