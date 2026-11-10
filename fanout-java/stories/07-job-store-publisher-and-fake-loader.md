# 07 — Job store, publisher and fake loader

- **Epic:** E2 Adapters & test kit
- **Priority:** Must
- **Status:** To do
- **Depends on:** 03
- **Contract refs:** §A I5, I6, I7; §B FanoutJobStore, EngagementLoader, events; §D row 16
- **Contract tests:** E8 (duplicate outcomes)
- **Checkpoint after:** None

## User story
As a test author, I want exact job counters, a recording publisher and a scriptable loader, so that executor tests can assert outcomes, events and loader concurrency.

## Context
Covers FR-19, FR-17. Applies G-01, G-07, G-11, G-25 (read method only).

## Acceptance criteria
- AC1 — Given `createIfAbsent` twice with one `jobId`, Then the second returns `false` and nothing changes (→ FR-19).
- AC2 — Given duplicate `recordOutcome(job, eng, …)` (any outcome), Then counters change once; COALESCED counts as done (→ FR-19, E8).
- AC3 — Given `loadsDone + loadsDlq = loadsExpected`, Then the load side is complete; not before (→ FR-19).
- AC4 — Given `InMemoryObservationPublisher` set to fail N times, Then the next N publishes throw and are not recorded (→ G-22).
- AC5 — Given `FakeEngagementLoader` with scripted outcomes and latency, Then it returns/throws as scripted and reports calls per engagement and peak concurrency, total and per firm.
- AC6 — Given `CrashInjector` armed at a point, Then the executor's hook at that point throws `SimulatedCrash` once (→ G-11).
- AC7 — Given 100 threads recording outcomes for the same items concurrently, Then counters are exact (→ FR-19).

## Tasks
- [ ] `InMemoryFanoutJobStore`, `InMemoryObservationPublisher`.
- [ ] Test kit: `FakeEngagementLoader`, `CrashInjector`.
- [ ] `ContractE08JobIdempotencyTest` (outcome half; event half in story 14).

## Out of scope
- Planner-side job operations (story 14).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
