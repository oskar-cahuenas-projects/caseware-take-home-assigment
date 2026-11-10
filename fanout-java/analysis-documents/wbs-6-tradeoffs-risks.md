## 6. Tradeoffs, Risks, Challenged Requirement, Omissions

The design trades a second source of truth (a regional projection) for real-time visibility. The riskiest part is the pending-flag function over a non-linear version graph. We challenge "within seconds" and replace it with separate latency, summary and completeness commitments.

Legend: **[F]** fact from the brief · **[A]** assumption · **[R]** recommendation · **[O]** open decision. Confidence is given where a claim is not established.

### 6.1 Key Architectural Tradeoffs

Each decision buys latency, safety or simplicity and pays for it somewhere else; the consequence column states the price.

| # | Decision | Alternative | Rationale | Consequence |
|---|---|---|---|---|
| T1 | Queryable regional projection: engagement → template, branch, version, decision | Load engagements on demand | A load takes ~1 min through a capacity-limited service [F]. One page view for a 40k-engagement firm would cost 40k load-minutes. | A second copy of the truth that can drift. We pay for outbox, backfill and reconciliation. |
| T2 | Materialize `pending` at publish time (fan-out of projection writes) | Compute at read time (recorded version vs. graph head) | Reads stay trivial and per-firm "at a glance" counts are precomputed. Write volume is small: ~20k rows per publish on average, ~800k per week, ≈1.3 writes/s on average [A: even spread across products, ~70%]. | Every publish **and every withdrawal** triggers a fan-out. A change to the pending function requires a replay. Large firms need hot-key handling (R5). |
| T3 | Eventual consistency; the engagement system stays authoritative for decisions | Transactions across the firm DB and the projection | No distributed transaction across systems owned by other teams. Outbox plus version guards guarantee convergence. | For a few seconds the flag can lag a decision. The engagement system loads the file to process a decision [F], so it validates the real version and a stale flag cannot produce a wrong decision. |
| T4 | Regional cells (US, CA, EU); only template-derived data is global | One global service | Residency is a contractual or legal requirement [F]. Template content is not firm-specific [F]. | Three deployments, runbooks and telemetry stacks, plus cross-region template event fan-in. |
| T5 | Explicit `UNKNOWN` state for unverified engagements | Optimistic "pending", or hiding the engagement | The system never claims "up to date" without evidence, which is safe under audit. | Weaker experience during the weeks of migration. Product must accept this state. |
| T6 | Pull (badge on refresh or poll) | Push (WebSocket or email) | No connection infrastructure per cell. Latency = fan-out time + poll interval. | The poll interval is a latency floor. No out-of-app alerts. |
| T7 | LLM summaries released without human review | Content-team approval per version pair | Summaries are available minutes after publish, with no human queue. | Errors reach users directly; we depend on automated checks (R3). |

### 6.2 Top Risks and Mitigations

R1 is the riskiest part to get wrong: its errors are silent and repeat across every firm. R1–R4 are architectural; R5 is operational.

| # | Risk | Impact | Mitigation | Residual exposure |
|---|---|---|---|---|
| **R1 — riskiest** | The pending function is wrong for branches, withdrawals or decline pins (e.g. v5 declined then v7 offered, or a withdrawn head) | Systematic, **silent** errors across all firms: a missing flag is invisible to the user. False decision history creates audit exposure. | One pure function `pending(state, graph)`; every row stores `fnVersion` and `graphRev`. Table-driven tests from the 1.2 rules, plus property tests (results insensitive to order and duplicates). Shadow verification: the cold path compares sampled rows with the loaded engagement. Fixes are applied by replay. | **Medium.** Tests prove we implemented *our* rules, not that the rules match content-team intent (O2). |
| R2 | Projection drift: lost events, or lifecycle paths that emit no event (roll-forward, copy, restore, delete) | Wrong or missing flags on individual engagements | Outbox on every producer. Refresh the row whenever an engagement is opened (it is loaded then anyway). Continuous, throttled verification sampling. Metrics: % `KNOWN` and drift rate. | Low–medium. An engagement nobody opens can drift until it is sampled. |
| R3 | A misleading or incomplete summary, with no human review | A user decides on wrong information, and the decision is hard to defend in November | The only input is the JSON diff. Every statement in the structured output cites diff paths. Automated checks for faithfulness (each statement maps to a path) and coverage (each material path is mentioned). The raw diff is always viewable. The record is immutable and hashed (model, prompt, input hash), and the decision references it. | **Medium.** Omissions and wrong emphasis are hard to detect automatically (O1). |
| R4 | Residency breach through global paths: prompts, logs, traces, DLQs, analytics | Contractual or legal breach; severe even as a single incident | Global components accept template-derived data only. Engagement IDs never appear in global payloads. Telemetry and DLQs stay in each cell. A CI check enforces data classification on event schemas. | Low probability, high impact. |
| R5 *(operational)* | Capacity limits: (a) loader budget during backfill and verification, ~11 days at 50 concurrent loads; (b) a 40k-row fan-out for a single firm | A long `UNKNOWN` tail; flag latency breaches for the largest firms | (a) Token bucket at the agreed budget, with priority: opened engagements > active large firms > the rest. (b) Partition rows by `engagementId`; write one aggregated count per firm per publish instead of per-row increments, because a single DynamoDB item caps at ~1,000 writes/s. | Medium during migration, low afterwards. The largest firms may see tens of seconds instead of seconds. |

### 6.3 Requirement to Challenge

**"Real time — within seconds of a template being published."** Seconds is achievable only for engagements whose version the projection already knows. It is not achievable for:

- engagements not yet backfilled (days to weeks, bounded by loader capacity);
- engagements affected by lost events (bounded by the reconciliation cycle);
- summaries (LLM generation takes minutes);
- the largest firms (tens of seconds).

"Seconds" mixes latency with completeness. In an audit product, a fast but incomplete indicator is worse than an honest, slightly slower one. We propose three measurable commitments; values are proposals aligned with 4.2 [R]:

1. **Latency:** publish → flag visible, p95 ≤ 30 s and p99 ≤ 2 min, for `KNOWN` engagements, including the poll interval.
2. **Summary:** available within 10 min (p95). Until then the flag shows "summary in progress".
3. **Completeness:** 100% of active engagements `KNOWN` by the end of the backfill window; verified drift rate ≤ 0.1% per sampling cycle; an `UNKNOWN` engagement is never shown as up to date.

The business goal stays intact: users learn about an update as soon as the system can actually know it.

### 6.4 Known Omissions and Open Decisions

Three items are deliberately out of scope; four need confirmation before production.

| # | Item | Type | Confidence / next step |
|---|---|---|---|
| — | Applying template content [F]; push and out-of-app notifications; firm-level policies such as auto-decline (bulk actions are UI-only) | Out of scope | — |
| O1 | No human review of summaries | Stakeholder decision | Revisit with audit-methodology and legal owners. At ~40 version pairs per week it is the cheapest lever on R3. |
| O2 | Versions are cumulative, and a decline pins only its target version | Unvalidated assumption | ~80%. Confirm with content teams. |
| O3 | Every engagement lifecycle path (roll-forward, copy, restore, delete) can emit an event | Unvalidated assumption | ~50%. Confirm with Engagement Management. |
| O4 | The template DB can emit publish and withdraw events that carry a monotonic graph revision | Unvalidated assumption | ~70%. Confirm with the template platform owners. |

### 6.5 Design Review Defense

**Q1. Read-time computation needs one write per publish. Why fan out?**

Reads dominate, and the firm-level "at a glance" view needs counts that would otherwise be aggregated on every read. Fan-out volume is small (≈1.3 writes/s on average). The pure function plus `graphRev` makes recomputation safe and replayable; replay on withdrawals and function changes is the accepted cost.

**Q2. The projection can be wrong. What stops a wrong decision?**

The projection drives *visibility* only. The engagement system loads the file to process apply or decline, and rejects decisions made against an offer that is no longer valid. A wrong projection costs latency or a missed flag, never a wrong state change. `UNKNOWN` is never rendered as "no updates", and drift is measured, not assumed.

**Q3. In November, an auditor asks what the user saw in March. What can you produce?**

- The decision record, held in-region: engagement, from/to version, user, timestamp, `summaryId` and content hash.
- The summary record, immutable and stored as WORM (e.g. S3 Object Lock, compliance mode): model ID, prompt version, input-diff hash.
- The diff itself, regenerated from the immutable template versions.

We can prove exactly what was shown and that it has not changed. We do not claim the summary was perfect: the raw diff was available beside it, and the decision remained professional judgment.

### References

- [Scaling DynamoDB: partitions, hot keys and split for heat (AWS Database Blog, part 1)](https://aws.amazon.com/blogs/database/part-1-scaling-dynamodb-how-partitions-hot-keys-and-split-for-heat-impact-performance) — per-partition limits of 3,000 read and 1,000 write units per second (R5).
- [Scaling DynamoDB, part 3 (AWS Database Blog)](https://aws.amazon.com/blogs/database/part-3-scaling-dynamodb-how-partitions-hot-keys-and-split-for-heat-impact-performance/) — partition-level limits are always enforced, even with burst capacity (R5).
- [Locking objects with S3 Object Lock (AWS docs)](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html) — WORM storage; compliance mode prevents overwrite or deletion by any user (6.5 Q3).
