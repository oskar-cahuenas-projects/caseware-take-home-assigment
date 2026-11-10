# Fan-out Worker (Part 2): Story index

Requirements: [`../docs/requirements.md`](../docs/requirements.md) · Gaps: [`../docs/contract-gaps.md`](../docs/contract-gaps.md) · Design: [`../docs/design.md`](../docs/design.md)

## Epics

| Epic | Name | Priority |
|---|---|---|
| E0 | Setup | Must |
| E1 | Domain & ports | Must |
| E2 | Adapters & test kit | Must |
| E3 | Executor core | Must |
| E4 | Resilience | Must |
| E5 | Lanes & fairness | Must |
| E6 | Planner (stretch) | Should |
| E7 | Validation & review | Must |
| E8 | Documentation | Must |

## Stories

| NN | Story | Epic | Priority | Status | Contract tests | Checkpoint |
|---|---|---|---|---|---|---|
| 01 | [Create the Maven project](01-create-maven-project.md) | E0 | Must | Done | — | None |
| 02 | [Domain model and ports](02-domain-model-and-ports.md) | E1 | Must | Done | — | None |
| 03 | [Clock, config and test kit](03-clock-config-and-testkit.md) | E2 | Must | Done | — | None |
| 04 | [In-memory work queue](04-in-memory-work-queue.md) | E2 | Must | Done | — | None |
| 05 | [In-memory load ledger](05-in-memory-load-ledger.md) | E2 | Must | Done | groundwork E2, E3 | None |
| 06 | [Concurrency limiter](06-concurrency-limiter.md) | E2 | Must | Done | groundwork E1 | None |
| 07 | [Job store, publisher and fake loader](07-job-store-publisher-and-fake-loader.md) | E2 | Must | Done | E8 (outcomes) | None |
| 08 | [Executor core and step order](08-executor-core-step-order.md) | E3 | Must | Done | E1, E2 | None |
| 09 | [Crash recovery and outbox](09-crash-recovery-and-outbox.md) | E3 | Must | Done | E3 | None |
| 10 | [Throttling, backoff and circuit breaker](10-throttling-backoff-circuit-breaker.md) | E4 | Must | Done | E4 | None |
| 11 | [Permanent errors and DLQ](11-permanent-errors-and-dlq.md) | E4 | Must | Done | E6 | None |
| 12 | [Visibility heartbeat and hard timeout](12-visibility-heartbeat.md) | E4 | Must | Done | E7 | None |
| 13 | [Weighted lanes and fairness](13-weighted-lanes-and-fairness.md) | E5 | Must | Done | E5 | CP3 |
| 14 | [Thin Planner](14-thin-planner.md) | E6 | Should | Done | E8 (events), E9 | None |
| 15 | [Tracking Service consumer contract test](15-tracking-service-contract-test.md) | E6 | Should | Done | E10 | None |
| 16 | [Full test run and review](16-full-test-run-and-review.md) | E7 | Must | Done | all | None |
| 17 | [README and docs](17-readme-and-docs.md) | E8 | Must | Done | — | None |

Must stories cover contract tests E1–E7. Story 07 covers the outcome half of E8.

## Milestones

| Milestone | Stories | Verifiable result |
|---|---|---|
| M1 | 01 | Build green, commands in root `CLAUDE.md` |
| M2 | 02–07 | Domain, ports, adapters and test kit, unit-tested |
| M3 | 08–09 | Contract tests E1, E2, E3 green |
| M4 | 10–13 | Contract tests E4, E5, E6, E7 green → **CP3** (stop for approval) |
| M5 | 14–15 | Contract tests E8, E9, E10 green (stretch; cut if over budget) |
| M6 | 16–17 | Full validation report and README |

Order within M2: 02 → 03 → (04, 05, 06, 07 in any order). Within M4, 10–12 depend only on 08; 13 last because it carries CP3.
