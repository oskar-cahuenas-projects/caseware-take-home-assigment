# AI Session History — Caseware Take-Home (Part 1 design)

| | |
|---|---|
| **Tool** | Claude (claude.ai chat, inside a Project holding the brief, the job description and the WBS) |
| **Date** | 2026-11-09 (times are America/Bogotá, UTC−5; "last activity" time of each chat) |
| **Sessions** | 16 chats: 1 framing chat, 2 research-prompt chats, 13 chats following the WBS (one per work package) |
| **Format** | Condensed log. My prompts are quoted verbatim where the chat history stored them (typos kept). Claude's replies are summarized. Each session lists the decisions I made and where AI output was corrected, in either direction. |

**How I worked.** One chat per WBS work package. Each chat followed the same loop: Claude read earlier decisions from project memory and past chats, asked clarifying questions with a proposed default for each, I answered or overrode, Claude drafted, and I asked it to "close" the section into a Markdown file. The closed files were the inputs to the next chat and to the final review (WBS 8).

**Gaps in this record.**
- In several chats the opening prompt was not stored in the chat history (shown below as *"opening prompt not captured"*). Its content can be inferred from Claude's first reply, which is summarized.
- The two research prompts (sessions 2–3) were run in a different AI tool. That tool's history is not included here.
- Claude's tool calls (web searches, file writes, scripts) are summarized, not reproduced.

---

## Index

| # | Session | Last activity | Output |
|---|---|---|---|
| 1 | Problem Analysis | 14:25 | WBS (4 h and 1 h versions) |
| 2 | Research prompt: event projections | 14:32 | Prompt for another AI |
| 3 | Research prompt: hot and cold path | 14:40 | Prompt for another AI |
| 4 | WBS 1.1 Assumptions and budget scenarios | 14:48 | `wbs-1.1-assumptions-budget-scenarios.md` |
| 5 | WBS 1.2 Version-graph semantics | 15:02 | `wbs-1.2-version-graph-semantics.md` |
| 6 | WBS 2.1 Architecture and data ownership | 15:25 | `wbs-2.1-architecture.md` |
| 7 | WBS 2.2 Data model and publish → flag sequence | 15:52 | `wbs-2.2-data-model-and-publish-flag-sequence.md` |
| 8 | WBS 3.1 Migration and backfill | 15:31 | `3.1-migration-backfill-reliable-delivery.md` |
| 9 | WBS 3.2 Unreliable delivery | 15:58 | `WBS-3.2-unreliable-delivery.md` |
| 10 | WBS 3.3 Failure recovery and data residency | 16:24 | Claude doc + `wbs-3.3-failure-recovery-data-residency.md` |
| 11 | WBS 4.1 Scale and cost arithmetic | 16:36 | `wbs-4.1-scale-cost-arithmetic.md` |
| 12 | WBS 4.2 SLOs and observability | 16:45 | `wbs-4-scale-cost-ops.md` |
| 13 | WBS 5 LLM summaries | 16:50 | `05-summaries-llm.md` |
| 14 | WBS 6 Tradeoffs, risks, challenged requirement | 16:55 | Claude doc + `section-6-tradeoffs-risks.md` |
| 15 | WBS 7 Fan-out worker contract | 17:07 | `07-fanout-worker-contract.md` |
| 16 | WBS 8 Review, polish, packaging | 17:23 | `caseware-submission.zip`, `review-notes.md`, `ai-usage-notes.md` |

Sessions 2–3 show earlier times because the framing chat (session 1) started first and stayed open while I worked; its last message came later.

---

## 1. Problem Analysis

**Prompts (verbatim)**

> Analize the job description and challenge in context andask me 
> any question. If no question, answer YES.

> 1) Help me to understand the task at hand.
> 2) We are going to create three different scenarios with different budgets from low to high. We are going to fill the details up later.
> 3) 4 hours is not a hard constraint, but I hope finish this excersice today.
> 4) Java 21 without any particular framework.
> 5) Markdown with mermaid diagrams. No code shall be generated in this stage.
> 6) Yes, please.
> 7) I have hands-on experience in React, SQS and DynamoDB, no experience in EKS, just ECS.I have architect level Java experience. I have knowledge of multithreading programming and JVM memory model.
>
> Answer YES if have understood the writing.

> Give me a high level overview of the business consideration, business domain, and key concepts.

> I agree with the core tension. Please generate a WBS to solve this challenge in about 4 hours or my time. Keep the WBS concise. From a technical perspective, I believe we need to use concepts from cloud computing, to support this workload in reasonable time frame (meaning the processing of a new template version consume no more that allocated time frame given by the problem statement)

> Please provide the Work Breakdown Structure (WBS) in a Markdown-formatted code block. And another codeblock of the WBS with time column ommited

> Refine the WBS and cut the time off to 1 hour, because I want take the 3 remaining hours to coding assigment.

**What Claude contributed**
- Seven clarifying questions (scope, budget `$X`, deadline, Java stack, format, AI-usage log, my background).
- Domain overview: firms, engagement files, templates, version graph, pending update, apply/decline, summaries. Named the core tension as ~1 min engagement loads vs. "real time", and proposed a queryable **projection** (~85% confidence this was the intended crux).
- A 4 h WBS, then a 1 h version that moved the Part 2 contract spec into the coding block.

**My decisions:** Java 21 with no framework; Markdown + Mermaid; no code in the design stage; three budget scenarios to be filled later; keep a running log of AI suggestions and my overrides.

**Corrections**
- **AI corrected my framing.** I proposed scaling processing with cloud compute. Claude showed that elasticity does not help, because the bottleneck is the other team's loader capacity (20k engagements × 1 min ≈ 333 h serial per publish). This produced the hot path / cold path split.

---

## 2. Research prompt: event projections

**Prompt (verbatim)**

> Give me a prompt to research in a different AI the concepts related to your proporsal:
> * "projection (a queryable index of engagement → template/version/decision state), populated from events and a one-time throttled backfill."

**Output:** a self-contained research prompt covering read models/CQRS, outbox and CDC, idempotency and ordering, live backfill and throttling, DynamoDB key design for skewed tenants, non-linear version semantics, and operations. I ran it in a different AI tool to check the concepts independently.

## 3. Research prompt: hot and cold path

**Prompt (verbatim)**

> Give me a prompt to research in a different AI the concepts related to your proporsal:
> * hot and cold path

**Output:** a research prompt covering definitions and origins, what belongs on each path, consistency between paths, AWS mapping, capacity arithmetic, anti-patterns, and likely interview questions. Claude noted (~85% confidence) that the hot/warm/cold terminology is documented mainly in Microsoft Azure references, so the prompt asked for both sources.

---

## 4. WBS 1.1 — Assumptions and budget scenarios

**Prompts**

Opening prompt (excerpt; the full prompt set a role, ten instructions, AWS pricing rules and interaction rules):

> Task: WBS 1.1 — Assumptions and Budget Scenarios … Do not invent budget limits, workload metrics, or AWS pricing. Use `TBD` where information is unavailable. … Never present hypothetical or outdated pricing as confirmed current pricing. … Start by asking clarifying questions. Do not generate the final tables immediately. … Do not proceed to WBS 1.2 without my explicit approval.

Answers to Batch 1 (verbatim):

> A) 1) Is the same workload in all scenarios. 2) TBD in section 4 about costs. 3) Costs associated with the engagement loader operated by the other team are included in the total cost estimate.
> B) 4) No specific figures has been provided. Before finalizing this question, please propose several meaningful cost scenarios … 5) Yes, we can assume optimistic capture.
> C) 6) Assume cells spread in Americas, USA, Canada, Europe, Asia. 7) Propose two senarios with their trade-off: Sumaries using only version diff, applicable to all firms. Tailored summaries for each firm with advantages (e.g. better UX, summary has less liability risk, permit better filtering capabilites, etc, vs more expensive)
> D) 8) Assume only Amazon Services. 9) Maybe we could explore both escenarios before closing this section and take a decision
> E) 10) Flag could be pushed to open sessions in the sake of better UX.
> Batch 2: … Distribution of engagements per product: skewed - Retention period for summaries and decisions 10 years - Compute preference: Let's decide between EKS and Lambda or both. - use provisioned to minimize costs where possible.

Follow-ups (verbatim):

> Lets finish budget scenarios assumptions with the following: * Generic Summary. Estimate with Bedrock pricing https://aws.amazon.com/bedrock/pricing/ for one region in US select Jamba or Jurassic model, then we can refine it * No human review in summaries for the sake of time * Use websocket for each cell * Lambda + EKS * Use on demand services

> Close with Jurassic model

> Search for another model available in foreseable future and try to search in web for an estimation of cost. I know that website has a form for asking for a quotation, but we can search some estimation with reference URL for Claude Haiku

> Ok we can quote Haiku 5.5 web site with price and say we are waiting for a quote for amazon. Generate the final output for this session

**What Claude contributed:** priced options from AWS pages (official vs. third-party marked); loader concurrency scenarios N = 20 / 50 / 200 (backfill cost fixed at ~13.3k slot-hours, only duration changes); generic vs. tailored summary comparison; review options; compute comparison; assumptions table A1–A22; Low / Mid / High table; open questions U1–U8.

**My decisions:** same workload in all scenarios; loader cost counted in `$X`; generic summaries; no human review; WebSocket per cell; Lambda + EKS; on-demand pricing; 10-year retention; Claude Haiku 5.5 priced from Anthropic's site with a Bedrock quote pending.

**Corrections**
- **AI challenged my liability assumption.** I listed "less liability risk" as an advantage of tailored per-firm summaries. Claude argued the reverse (~75%): thousands of unreviewed texts with confidential data in prompts are a larger liability surface.
- **AI narrowed my "provisioned" request** to steady workloads only; I then switched to on-demand.
- **AI flagged my model choices.** Jamba retires on 26 Nov 2026; Jurassic-2 is likely retired (~85%), has an 8K context and is ~60× pricier. I closed with Jurassic as a cost ceiling, then asked for a long-lived model. Claude chose Haiku 5.5 over Haiku 4.5 (4.5 is past its earliest retirement date) and flagged thinking-token and tokenizer cost effects.
- **Result:** ~$133 / $157 / $209 a month visible, ~$1.7k one-time backfill. *(Later superseded by WBS 4.1.)*

---

## 5. WBS 1.2 — Version-graph semantics

**Prompts:** opening prompt not captured. My answers (verbatim):

> Batch 1
> 1) I assume that each template version is uniquely identified by (templateId, versionId) and has a reference to its parent version. I also assume that the database maintains version lineage information, either through a lineageId field or another mechanism that allows the complete version graph to be traced
> 2) A branch is a market line
> 3) Assume accumulative versions, not necessarily all lineage. For example template v7 has v5 updates, but v6 could be in another lineage.
> 4) Decline pins only current template version.
> Batch 2
> 5) Withdrawal cannot be reversed. Do not notify about this.
> 6) Default
> 7) Manual decision by user within the firm
> 8) New engagement created with last template version.

> O1) Not, it cannot apply to a declined thead. O2) single chain by market O3) your assumption by default O4) Backfill must import O5) By default

> generate the output to close this section

**What Claude contributed:** questions with defaults; definitions (`cur`, `cand`, `head`, `declined`, `status`); rules R1–R20; a 13-row decision matrix; two state machines (engagement status, offer lifecycle); downstream implications for 2.2, 2.3, 3.1, 3.2 and 5.1.

**My decisions:** parent pointer and stored lineage; branch = market line, single chain; cumulative versions along a lineage only; a decline pins only its target; a declined head can never be applied; withdrawal is irreversible and silent; manual per-engagement decisions; new engagements start at the head; backfill imports past decisions.

**Corrections / interpretations**
- Claude stated how it read two ambiguous answers and asked me to confirm: Q4 as "decline suppresses only that target" (85%), O5 as "plain diff with no marker for earlier declines" (75%).
- Claude flagged that version numbers are not ordered across branches, so ordering comes from lineage only.
- Open risk carried forward: whether old decision records name their target version (~60%).

---

## 6. WBS 2.1 — Architecture and data ownership

**Prompts:** opening prompt not captured. My answers (verbatim):

> Q1) It is computed at publish time to have track from the beginning.
> Q2) Assume that a template tracking service exists
> Q3) Templates summaries are created en east-region and engage management runs by cell to fulfill legal matter of accounting document must be stored in specific region case Europe and others. Rest default.
> Q4) Yes
> Q5) Vía periodical polling to a regional API
> Q6) It supports via SQL Server
> Q7) Default

> Finish output generation to close this section

**What Claude contributed:** Mermaid context diagram (global template plane in us-east-1, regional cells EU / CA / US, hot vs. cold path edges); ownership table; key decisions with tradeoffs; hot/cold arithmetic (~20k writes per average publish ≈ 5 s at default DynamoDB warm throughput); risks (EventBridge cross-Region targets ~60%, hot partitions, polling latency, Engagement Management outbox).

**My decisions:** pending computed **at publish time**; a Template Tracking Service owns the projection; summaries generated in us-east-1; Engagement Management per cell; users **poll** a regional API; Template Store on SQL Server with an outbox; Lambda for handlers, ECS/Fargate for the worker.

**Corrections**
- **I overruled AI.** Claude recommended computing pending at read time (~75%). I chose publish time for traceability from the first moment. Claude kept my choice and flagged write amplification and hot partitions for 2.2.
- These answers replaced two 1.1 choices: WebSocket push → polling, EKS → Fargate.

---

## 7. WBS 2.2 — Data model and publish → flag sequence

**Prompts:** opening prompt not captured (it listed seven points). My answers (verbatim):

> Q1) List by engament for clients with filtering capabilities. A plus for massive approval or denies by criteria. Caseware pending updates list as well
> Q2) Store state in projection item + version change history
> Q3) Default Q4) Yes Q5) Default

> Bulk functionality only for same template and summary for the sake of simplicity, could be a suggestion and not implemented at all. Suggest. it with trade offs

> Continue with your default decisions and generate document to close this section

**What Claude contributed:** single-table DynamoDB design per cell (EngagementState, DecisionRecord, FirmCounts, FirmMeta, PublishRun, SummaryRecord pointer, EngagementHistory); GSI1 per firm (sharded), GSI2 per template branch (32 shards, keys-only); conditional-write idempotency without a dedup table; publish → flag sequence diagram; dependencies D1–D6.

**My decisions:** full filterable per-firm list plus an internal Caseware list; state on the projection item plus a version change history; the UI passes `summaryId` and `contentHash` with each decision; engagement IDs can be listed cheaply; bulk decisions limited to the same template and summary, as a suggestion only.

**Corrections**
- **I changed AI's index proposal.** Claude proposed a firm index holding only pending engagements; I required every status, so GSI1 covers all.
- **I changed the history design.** Claude proposed separate Offer records; I chose state plus history, which Claude then sourced from DynamoDB Streams to avoid extra hot-path writes.
- **AI flagged a conflict.** Bulk by criteria conflicts with the per-engagement rule (R15). I narrowed it; Claude recommended deferring it from v1 and keeping two hooks (`bulkJobId`, per-item target guard).

---

## 8. WBS 3.1 — Migration and backfill

**Prompts:** opening prompt not captured (it merged 3.1 and 3.2). My answers (verbatim):

> 1) Create outbox table in DB, no CDC. No events at the present
> 2) There is a way to do this.
> 3) No monotinic revision
> 4) Don't have enough data, we could do a gues swith default value
> 5) No
> 6) Yes
> 7) Yes

> Create the final output to close this phase

**What Claude contributed:** a four-phase zero-downtime plan (outbox first in shadow mode → seed UNKNOWN rows → throttled backfill → UI per firm); a per-engagement `engagement_seq` table (and why `IDENTITY` ids are unsafe for relay polling); **"backfill is just another event"** through the same outbox, so backfill cannot race live events; piggyback indexing on file open; AIMD throttling and per-firm fairness; four reconciliation checks, cheapest first.

**My decisions:** outbox table in each DB, no CDC; no monotonic revision exists; pre-cutover decisions cannot be reconstructed; tri-state UI accepted; loader capacity guessed with a default (25 slots per region, low confidence).

**Note:** my "No" to Q5 (past decisions not reconstructable) contradicts my 1.2 answer O4 ("backfill must import"). This was caught in WBS 8 (finding 7) and is still open.

---

## 9. WBS 3.2 — Unreliable delivery

**Prompts:** opening prompt not captured. My answers (verbatim):

> 1) All in SQL Server
> 2) Yes
> 3) DB as source of truth
> 4) Default
> 5) Default
> 6) Yes event bridge, retention
> 7) default

> Tell me if 3.3 is required. Generate output document for closing this session

**What Claude contributed:** at-least-once delivery with version-guarded consumers; events carry state, not deltas; SQS standard instead of FIFO (FIFO dedup window is only 5 min); outbox only where the event is the sole cheap record of a change; a firm relay using **nudge + 5-min sweep** instead of polling ~4,000 databases; reconciliation table; failure-scenario table; residual risks.

**Corrections**
- My answer 6 omitted the retention value; Claude assumed a **30-day** EventBridge archive and flagged it to confirm.
- Claude confirmed 3.3 was required by the brief (residency and failure recovery) and scoped it to what 3.2 did not cover.

---

## 10. WBS 3.3 — Failure recovery and data residency

**Prompts:** opening prompt not captured. My answers (verbatim):

> A) 1) Strict isolation, they are different countries. 2) No 3) No tenant data. Global
> B) 4) No 5)No
> C) 6) Yes 7) Yes

> create output document

> Create output md

**What Claude contributed:** failure classification (transient vs. permanent); hot path independent of the loader; resumable publish jobs; regional outage handling without failover; three catch-up layers (push, archive replay, pull reconciliation); rebuild order (PITR + replay first, full reload last: 800k load-min ÷ C); RPO/RTO proposed per data class; residency boundaries (account per region + SCP region deny); third-party APM as the likeliest leak; AWS mapping.

**My decisions:** strict in-region isolation; no tenant identifiers in global infrastructure; templates and summaries are global; no RPO/RTO targets; no cross-region failover; stale indicator acceptable.

**Corrections**
- **AI challenged an unstated assumption:** that Engagement Management, not the projection, owns decisions. I did not confirm it; it remains a documented assumption.
- Consequence Claude derived from my answer 2: with no tenant IDs globally, the global layer can only broadcast to regions; each region resolves affected engagements locally.

---

## 11. WBS 4.1 — Scale and cost arithmetic

**Prompts:** opening prompt not captured. Follow-up (verbatim):

> Generate output document to close this section

**What Claude contributed:** facts derived from the brief (173 publishes a month, 13,333 slot-hours for a full backfill); scenario parameters S1–S19; formulas; volumes, monthly and one-time cost tables per scenario (computed by a script); bottlenecks; sensitivity table; pricing basis with sources.

**Headline:** ~$255 / $735 / $1,475 a month all-in; ~$1.1k / $1.5k / $1.8k one-time backfill; US-cell backfill 5 / 19 / 76 days; burst flag time 3 / 33 / 152 s at default DynamoDB warm throughput.

**Corrections**
- **AI reconciled 4.1 with my later decisions:** 3 cells instead of 5, polling instead of WebSocket, Fargate instead of EKS. My 1.1 rule "same workload in all scenarios" (A10) was superseded because these scenarios vary the workload.
- **AI corrected its own 1.1 finding** that variable cost is tiny: polling plus the outbox relay are ~75% of Mid; inference is under 2%.
- Lambda and API Gateway prices re-checked on official pages; pre-warm charge partly from memory (~75%).

---

## 12. WBS 4.2 — SLOs and observability

**Prompts:** opening prompt not captured. Follow-up (verbatim):

> Tell me if analysis is complete, if it is generate document for closing section 4

**What Claude contributed:** SLO table (flag freshness, per-publish completeness, event integrity, projection correctness, cold-path progress, summaries); metrics; five alerts; observability flow with `publishId` correlation; blind spots; review questions.

**Corrections**
- **AI's first draft was wrong and was caught before closing.** It was written without reading the closed 4.1 and reused superseded 1.1 assumptions (5 cells, WebSocket, EKS, p95 ≤ 10 s). The closed file uses 3 cells, polling (user-visible p95 ≤ 45 s as a derived SLO), Fargate, backend p95 ≤ 15 s with a DynamoDB pre-warm, and adds a relay-lag SLI.
- Still open: load-test the High burst (~75% confident 15 s holds); legal check that tenant-free aggregates may leave a region.

---

## 13. WBS 5 — LLM summaries

**Prompts:** opening prompt (excerpt; it was cut off when sent):

> Caseware Take-Home — Step 5: LLM Summaries. Act as a Staff-level distributed-systems architect and AWS expert, with experience in production LLM systems, audit software, data governance, and regulated SaaS environments. … 5.1 — LLM Summary Generation … 5.2 — Evaluation and Audit Defensibility … Ask clarifying questions before proposing the solution. … I have no additional authoritative informatio[n]

> Create output document

**What Claude contributed:** ten clarifying questions with defaults; a deterministic **ChangeFacts** layer between the JSON diff and the LLM (enables caching, hard quality gates, and a no-LLM fallback); immutable summary records; decision records storing `summaryId` and `outputHash` in-region; failure handling (deterministic change list shown if the summary is missing or fails gates).

**My decisions:** accepted the defaults by asking for the output.

**Corrections**
- Claude noticed my prompt was truncated and stated how it completed it.
- Claude flagged its least-confident points: no human pre-approval (50%), Bedrock pricing **$150–500 a month** (55%), cross-region inference (60%).
- **This draft contained errors caught later (WBS 8):** the $150–500 LLM cost was ~20× too high (recomputed to ~$13), and it placed generation in `ca-central-1` with DynamoDB Global Tables, contradicting 2.1 and 3.3.

---

## 14. WBS 6 — Tradeoffs, risks, challenged requirement, omissions

**Prompts:** opening prompt not captured. My answers (verbatim):

> Q1) Publish time
> Q2) unknown
> Q3) No
> Q4) Pull
> Q5) Default
> Q6) a

> Create final document for this section

> Generate md document

**What Claude contributed:** tradeoffs T1–T7; risks R1–R5; challenged requirement; omissions and open decisions O1–O4; design-review Q&A (why fan out, what stops a wrong decision, what an auditor gets in November).

**My decisions:** publish-time flags; UNKNOWN state for unverified engagements; **no human review** of summaries; pull, not push; challenge "real time within seconds"; riskiest part = the pending function over the version graph.

**Corrections**
- **AI pushed back on two of my answers** but kept them: publish time (made the withdrawal and function-change fan-out cost explicit in T2) and no human review (logged as O1, the cheapest risk reduction at ~40 pairs a week).
- Claude asked me to check its backfill and SLO figures against the closed 4.1/4.2; the mismatch was fixed in WBS 8 (finding 3).

---

## 15. WBS 7 — Fan-out worker contract (Part 2 design)

**Prompts:** opening prompt not captured. My answers (verbatim):

> Q1) Implement the worker
> Q2) Read only call
> Q3) your suggestion
> Q4) Default
> Q5) Default
> Q6) Inmemory or something like openstack in a container, including its docker compose.Choose easiest option
> Q7) Default

> create md document

**What Claude contributed:** two-stage worker (Planner, no loader; Load Executor, capacity-bound); interface table I1–I7; Java 21 record and interface signatures (no implementation); processing flow; correctness and failure matrix; ten prioritized tests; tradeoffs (Fargate vs. Lambda vs. Step Functions, static permit split, SQS fair queues, ledger as outbox, requeue-with-delay so throttling does not consume `maxReceiveCount`).

**My decisions:** implement the Load Executor; read-only loader call; worker emits observation events and does not write the projection; coalesce loads per engagement; three priority lanes with per-firm fairness; in-memory adapters.

**Corrections**
- I wrote "openstack"; Claude read it as **LocalStack** and chose in-memory adapters as the easiest option, with LocalStack + Docker Compose as an optional extra.
- Coalescing changed the 3.2 ledger key from `(engagementId, targetVersion)` to per engagement + `observedSeq`. Claude flagged this as an edit to 3.2.
- Riskiest assumption: the loader returns `engagementSeq` (60%), to raise with the interviewers.

---

## 16. WBS 8 — Review, polish, packaging

**Prompts:** opening prompt not captured (Claude noted it was cut off at "12. Final Acceptance Checklist… Unreliable"). Then (verbatim, WBS table abbreviated):

> There are generated documents an WBS plan
> # WBS — Caseware Take-Home, Part 1 Design (~1h) … [the 1-hour WBS table]

> Verify that the caseware-design document addresses all questions and problems outlined in Challenge.pdf within the project context.

> show the md differences with previous version

**What Claude contributed**
- Refused to start from partial chat snippets and asked for the closed section files first, to avoid silently altering approved decisions.
- Cross-checked all sections: **19 findings** (see table below), applied the later section where two disagreed, and re-ran the Section 3 arithmetic by script.
- Built the package: `caseware-design.md` (5 sections, 4 Mermaid diagrams with PNGs), a text-only PDF (≈ 3.5 pages measured), a full PDF, `ai-usage-notes.md`, and internal `review-notes.md` with five hard review questions.
- Verified the document against the brief: all five required topics covered; four points thin after cuts (branches, the "at a glance" view, engagement creation, withdrawal on the hot path); Part 2 code and README missing.
- Diff request: reported that no earlier version of the file existed to compare against, and offered two alternatives.

**Main review findings**

| # | Finding | Resolution |
|---|---|---|
| 1 | LLM cost $150–500 (WBS 5) vs. ~$13 (4.1) | ~$13, recomputed |
| 2 | Monthly cost 1.1 vs. 4.1 | 4.1 figures |
| 3 | Latency targets differ across 4.1, 4.2, 6 | 4.2 everywhere |
| 4 | Firm relay: continuous polling (3.1, priced in 4.1) vs. nudge + sweep (3.2) | Nudge + sweep; cost left conservative |
| 5 | Backfill duration quoted five ways | 4.1, with inputs shown |
| 6 | "One-way flow" contradicted by lazy summary requests | Rule restated: cell → global only if tenant-free |
| 7 | Backfill imports past declines (1.2) vs. not reconstructable (3.1) | 3.1 used; **open, my confirmation needed** |
| 9 | Template Tracking Service drawn as existing; brief names no such service | Marked new; **open, my confirmation needed** |
| 10 | Summary region `ca-central-1` + Global Tables (5) vs. us-east-1 + S3 replication | us-east-1 + S3 replication |
| 16 | 15 s flag target has no headroom | Not changed; needs a load test |
| 18 | EventBridge cross-Region targets to CA/EU (60% in 2.1) | Resolved: supported in all commercial Regions since Nov 2021 |

---

## Consolidated log: where AI output was corrected or overruled

| Session | AI proposed or produced | Outcome |
|---|---|---|
| 2.1 | Compute pending at read time | **I overruled:** publish time |
| 2.2 | Index of pending engagements only | **I overruled:** index covers every status |
| 2.2 | Separate Offer records on the hot path | **I overruled:** state + history from Streams |
| 2.2 | Bulk decisions by any criteria | **I narrowed** to same summary; deferred from v1 |
| 4.1 | 1.1 claim that variable cost is tiny | AI corrected itself: polling and relay dominate |
| 4.2 | First draft ignored closed 4.1 | Rejected and rewritten against 4.1 |
| 5 | LLM cost $150–500 a month | Wrong by ~20×; corrected in WBS 8 |
| 5 | Generation in `ca-central-1` with Global Tables | Contradicted 2.1/3.3; corrected in WBS 8 |
| 6 | Pushback on publish-time flags and no human review | I kept both; documented as T2 and O1 |
| 8 | Template Tracking Service marked as new, against my 2.1 answer | Open: I need to confirm |

**Where AI corrected me:** the idea that more compute would meet "seconds" (session 1); "tailored summaries reduce liability" (1.1); "use provisioned capacity" (1.1); my Jamba / Jurassic model choice, which AI showed was retiring or retired (1.1); my contradictory answers on pre-cutover declines (1.2 vs. 3.1, found in WBS 8).

## Open items at the end of the sessions

- Confirm whether pre-cutover declines can be imported (finding 7).
- Confirm whether the Template Tracking Service exists (finding 9).
- Load-test the 15 s backend flag target.
- Get the Amazon Bedrock quote for Haiku 5.5 and verify the DynamoDB pre-warm charge.
- Part 2: Java code and README, with the ledger key matching the WBS 7 contract.
