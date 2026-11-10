# WBS 3.3 — Failure Recovery & Data Residency

## Scope and constraints

The projection is a derived, rebuildable read model kept strictly in-region; recovery relies on in-region replay and pull reconciliation, never cross-region failover.

**Confirmed constraints**

- Residency: strict in-region isolation for EU and Canada (different jurisdictions).
- Global infrastructure holds no tenant identifiers or engagement metadata at all.
- Template content and LLM summaries are global, non-tenant data.
- No RPO/RTO targets given; no cross-region failover allowed.
- A stale indicator is acceptable during outages.
- WBS 3.1/3.2 mechanisms are in place: outbox, idempotency key `(engagementId, templateId, targetVersion)`, monotonic version guard, reconciliation sweep.

**Open assumption:** the engagement system is the system of record for apply/decline decisions; the projection only mirrors them. If wrong, §2 changes (decision journal, tight RPO).

**Design consequence:** with no tenant IDs globally, the global layer cannot fan out to engagements. It broadcasts *template T, version V, published/withdrawn* to a static list of regions; each region resolves affected engagements locally. Global fan-out is to ~3–4 regions, not 800k engagements.

## 1. Failure recovery

The hot path (publish → flag) never calls the 1-minute engagement loader, so a loader outage degrades only backfill and verification.

- **Classify before retrying.** Transient (throttling, 5xx, timeouts) → capped exponential backoff with full jitter, bounded attempts. Permanent (unknown engagement, version not in graph, firm offboarded, schema violation) → DLQ immediately. Poison messages → SQS `maxReceiveCount` → same-region DLQ.
- **Hot path independence.** Publish → flag reads and writes only the regional projection. This is the main availability property of the design.
- **Partial fan-out.** Each regional fan-out is a *publish job* record (expected / done / DLQ counts). Work units are idempotent conditional writes. The job closes when `done + dlq = expected`; residue is caught by reconciliation (§2). No step assumes fan-out completed.
- **Crashes, timeouts, duplicates.** Loader-bound work extends SQS visibility (`ChangeMessageVisibility` heartbeat). A crashed message reappears; duplicates are harmless under the 3.2 idempotency key and version guard.
- **Downstream protection.** Per-region concurrency cap (token bucket) agreed with the owning team; circuit breaker on 429/5xx rate; fair scheduling across firms (per-firm sub-queues or weighted round-robin) so a 40,000-engagement firm cannot starve median firms; DLQ redrive at throttled velocity, never a bulk dump.
- **Regional outage (no failover).** Regions run multi-AZ. During a full regional outage, the indicator is stale and the UI shows "last updated at". EventBridge cross-region delivery retries up to 24h, then lands in a target DLQ in the global region (no tenant data, so allowed). On recovery: archive replay, then pull catch-up (§2).
- **Global template-DB outage.** Publishing stops, so nothing new needs flagging. Regions keep serving existing flags and local summary copies.

## 2. Consistency and recovery guarantees

Push is never the only delivery path, and a full loader-based rebuild is too slow to be the recovery plan.

**Catching missed or delayed updates (three layers)**

1. **Push:** the normal event.
2. **Replay:** from the EventBridge archive.
3. **Pull reconciliation (per region, scheduled):** compare the projection's head version per `(product, branch)` with the template DB. Cheap: 40 products, global non-tenant data. A gap triggers a local re-fan-out.

**Engagement-side drift** is detected against the engagement system's outbox stream position, not by loading engagements. Sampled loader verification on the cold path acts as a correctness probe within a small fixed budget.

**Projection rebuild order**

1. DynamoDB PITR restore, then replay engagement and template events since the restore point (primary).
2. Full rebuild via the loader (last resort). Cost ≈ 800,000 loader-minutes ÷ C, where C = allowed concurrency: C = 50 → ~267 h (~11 days); C = 200 → ~67 h (~2.8 days).

**RPO/RTO by data class** — no targets were given; these are proposals to agree, not commitments.

| Data | System of record | Effective RPO | RTO driver |
| --- | --- | --- | --- |
| Decisions | Engagement system (assumed) | Owned there; projection loses nothing | — |
| Projection | Derived | Rebuildable; PITR bounds rework | PITR restore + replay volume |
| Template events | Template DB + archive | Archive retention | Replay time |
| Summaries | Immutable store (5.2) | Zero loss (audit evidence) | Read-path availability |

**Preserving decisions.** Projection rows keep template-derived state and decision state in separate attributes, each with its own guard. Template replays never write decision fields. Because a decline pins a version (1.2), replaying an older publish cannot resurrect a pending state.

**No stale overwrites.** Writes are conditional on a monotonic sequence assigned at the source: template outbox per `(product, branch)`, engagement outbox per engagement. Wall-clock time is never used for ordering. A withdrawal is its own event with a higher sequence.

**Last line of defense.** At apply time, the engagement system validates the target version against the template DB (not withdrawn, still on the branch). The projection may lag; the decision cannot be wrong.

## 3. Data residency and regional isolation

Only non-tenant template data is global; everything that carries a tenant identifier stays in its home region, enforced by account and policy boundaries.

| Scope | Data and components | Movement |
| --- | --- | --- |
| Global (one home region) | Template DB, version graph, JSON diffs, LLM summaries, publish/withdraw events, static region list | Replicated freely to regions |
| Regional (never leaves) | Projection, decision mirror, fan-out queues, DLQs, workers, reconciliation jobs, logs/metrics/traces with tenant IDs, backups, PITR, any archive carrying tenant data | None cross-region |

- **One-way event direction:** global → regional only. Regions emit nothing cross-region except aggregate, non-tenant health metrics. The cross-region event schema is validated so no tenant field can be added by accident.
- **Isolation boundary:** one AWS account per region, with SCPs denying actions outside approved regions (`aws:RequestedRegion`). Enforcement by policy, not convention.
- **Backups:** in-region, multi-AZ. Same-jurisdiction cross-region copies (e.g., Frankfurt ↔ Ireland, Montréal ↔ Calgary) count as replication and are excluded until legal confirms. This is a legal requirement to verify, not a design choice.
- **Observability:** CloudWatch is regional by default. Third-party APM (New Relic) is the likeliest residency leak: use region-matched accounts or scrub tenant identifiers before export. Confidence ~70% that New Relic offers US and EU data centers but none in Canada — verify with the vendor.
- **LLM:** inference sees only template diffs (non-tenant), so it can run in one region. Summaries are replicated to every regional read store; regional UIs never call the home region at runtime. Hard rule: no engagement content in prompts. Any future engagement-aware summary must run in-region on an in-region model; Bedrock model availability differs by region and is thinner in Canada (confidence ~75%).

## 4. AWS mapping

AWS features cover infrastructure availability (multi-AZ, PITR, EventBridge retry); correctness recovery (sequence guards, reconciliation, apply-time validation) is application-level.

| Mechanism | AWS choice | Note / limit |
| --- | --- | --- |
| Global → regional broadcast | EventBridge cross-region rules | Retries up to 24h, then target DLQ (~90% confident on defaults) |
| Global replay | EventBridge archive & replay | No tenant data, so the archive stays global |
| Regional work queues | SQS standard + DLQ + redrive | Visibility timeout max 12h; retention max 14 days |
| Throttled redrive | SQS DLQ redrive with max velocity | ~80% confident on the velocity setting |
| Projection | DynamoDB (regional, not Global Tables) + PITR | PITR window up to 35 days; conditional writes for guards |
| Publish-job tracking | DynamoDB item per `(publishId, region)` | Fan-out progress + reconciliation input |
| Summary distribution | S3 + replication to regional buckets | Global content only; Object Lock for audit (5.2) |
| Isolation | Account per region + SCP region deny | Enforced boundary |
| Backup | AWS Backup, in-region vaults | No cross-region copy until legal confirms |
| Retry design | Backoff + jitter (AWS Builders' Library) | Application-level; SDK retry alone is not enough |

## 5. Trade-offs and residual risks

The largest open input is downstream loader capacity; the largest accepted risk is a stale feature for the full duration of a regional outage.

1. **No regional failover.** A regional outage means a stale feature for its whole duration. Acceptable per stakeholder answer; must be recorded as an accepted business risk.
2. **Downstream capacity C unknown.** Drives backfill, full-rebuild and redrive times.
3. **Decision ownership unconfirmed.** If the projection is the system of record, it needs a decision journal, tight RPO and cross-AZ durability guarantees.
4. **Observability tooling** is the most likely accidental residency breach.
5. **Same-jurisdiction backup copy** pending legal decision; until then, regional data loss recovers from in-region backups only.

## 6. Review defense

**Q: Why not active-active multi-region for availability?**
Residency forbids it for EU/CA tenant data. The projection is derived and rebuildable, and decisions live in the engagement system. Active-active to protect a read model that tolerates staleness is poor value; multi-AZ plus pull catch-up covers realistic failures.

**Q: A region is down for 8 hours during a publish. What does the user see afterward?**
During the outage, a stale badge with a timestamp. On recovery, EventBridge delivers retried events (or we replay from the archive), the pull reconciler detects the head-version gap within one cycle, and fan-out runs locally. No decision is affected; if a version was withdrawn meanwhile, the apply-time check blocks it.

**Q: How do you prove the projection is correct, rather than assume it?**
Drift metrics from reconciliation, with an SLO on drift rate; sampled loader verification on the cold path; and apply-time validation, which makes projection errors visible but not harmful.

## References

Official AWS documentation; links not re-verified for this draft (confidence ~85% each resolves as listed).

- [SQS dead-letter queues](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html)
- [SQS DLQ redrive](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-configure-dead-letter-queue-redrive.html)
- [SQS visibility timeout](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html)
- [EventBridge archive & replay](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-archive.html)
- [EventBridge cross-region events](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-cross-region.html)
- [EventBridge retry policy & DLQs](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-rule-dlq.html)
- [DynamoDB point-in-time recovery](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Point-in-time-recovery.html)
- [DynamoDB condition expressions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.ConditionExpressions.html)
- [S3 replication](https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication.html)
- [AWS Backup](https://docs.aws.amazon.com/aws-backup/latest/devguide/whatisbackup.html)
- [SCP examples (region deny)](https://docs.aws.amazon.com/organizations/latest/userguide/orgs_manage_policies_scps_examples_general.html)
- [Bedrock cross-region inference](https://docs.aws.amazon.com/bedrock/latest/userguide/cross-region-inference.html)
- [Builders' Library: timeouts, retries and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/)
