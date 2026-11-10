# 04 — In-memory work queue

- **Epic:** E2 Adapters & test kit
- **Priority:** Must
- **Status:** Done
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
- [x] `InMemoryWorkQueue<T>` (one per lane) with DLQ.
- [x] Unit tests under `MutableClock`; concurrency test for AC7.

## Out of scope
- Lane weighting (story 13).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
- AC → test (`InMemoryWorkQueueTest`): AC1 `visibilityExpiryRedelivers`; AC2 `extendVisibility`; AC3 `requeueWithDelayCapsAndResets`, `requeueOldReceiptIsDead`; AC4 `maxReceiveCountDeadLetters`, `deadLetteringDoesNotBlockOthers`; AC5 `explicitDeadLetter`; AC6 `staleReceiptIsNoOp`, `ackDeletes`; AC7 `concurrentReceiversGetDistinctMessages` (8 virtual threads, 2,000 messages). 5/5 runs green. AC7 verified to fail 3/3 with the `receive` lock removed.
- `receive` never blocks; `wait` is ignored. A real long poll would run on wall-clock time and break `MutableClock` tests; the executor idles on the `Scheduler` when lanes are empty (EC-15).
- DLQ rule: a message already received `maxReceiveCount` times is moved to the DLQ on its next receive instead of being delivered (design §6). The receive keeps scanning, so a poison message never blocks others.
- `send(T)` / `send(T, Duration)` are adapter-only producer methods; the `WorkQueue` port is the consumer side from §B and is unchanged. The Planner (story 14) and tests use the adapter type.
- Receipts are counter-based (`r-<n>`), not random, for reproducible logs. A receipt dies on redelivery, ack, requeue or dead-letter; any call with a dead receipt is a no-op.
- One `ReentrantLock` (not `synchronized`) so virtual threads don't pin carriers. Logs carry the queue name and message sequence only, never the body.
- Constructor takes explicit values (name, clock, visibility, maxReceiveCount, maxDelay); wiring from `ExecutorConfig` is story 08's factory.
