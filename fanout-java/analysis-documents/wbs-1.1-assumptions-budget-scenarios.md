# WBS 1.1 — Assumptions and Budget Scenarios

**Status:** Closed, pending approval · **Date:** 2026-11-09 · **Pricing verified:** 2026-11-09

## 1. Purpose and framing

This section fixes the inputs that later WBS items depend on. The workload is the **same in all three scenarios**. Low / Mid / High vary only the levers that money buys: loader concurrency, how much reconciliation runs, summary granularity, and automated evaluation. The budget ceiling **$X is TBD** and will be set in WBS 4.1 against these figures.

Estimates use **us-east-1**, **on-demand** pricing, before taxes and support plans. Prices in the other cells' regions are not yet verified.

## 2. Pricing basis

| Service | Price used | Billing unit | Source | Status |
|---|---|---|---|---|
| DynamoDB on-demand (Standard table) | $0.625 per 1M write units · $0.125 per 1M read units · $0.25 per GB-month | Per request unit; per GB-month | https://aws.amazon.com/dynamodb/pricing/ | Verified, official |
| API Gateway WebSocket | $1.00 per 1M messages (32 KB increments) · $0.25 per 1M connection minutes | Per message; per connection minute | https://aws.amazon.com/api-gateway/pricing/ | Verified, official |
| EKS control plane | $0.10 per cluster-hour (standard version support) | Per cluster-hour | https://aws.amazon.com/eks/pricing/ | Verified, official |
| Fargate Linux/x86 (proxy for loader and worker pods) | $0.000011244 per vCPU-second · $0.000001235 per GB-second (≈ $0.0405/vCPU-h, $0.0044/GB-h) | Per second | https://aws.amazon.com/fargate/pricing/ | Verified, official |
| Lambda x86 | $0.20 per 1M requests · $0.0000166667 per GB-second | Per request; per GB-second | https://aws.amazon.com/lambda/pricing/ | Third-party; official page not read ⚠️ |
| **LLM: Claude Haiku 5.5** | $0.10 per 1M input tokens · $0.50 per 1M output tokens (prompts up to 100K tokens); $0.50 / $2.50 above 100K; batch 50% off | Per token | https://www.anthropic.com/claude/haiku (Anthropic's published price) | Verified on the Anthropic website. **Amazon Bedrock quote requested, awaiting reply** |
| Bedrock regional endpoint premium | +10% over global for Claude 4.5 and later | — | https://www.cloudforecast.io/blog/aws-bedrock-pricing/ | Third-party ⚠️ |

**Estimating tool:** AWS Pricing Calculator, https://calculator.aws/

## 3. Assumptions table

| # | Assumption / constraint | Value | Source / rationale | Confidence | Architectural / cost impact | Validation status |
|---|---|---|---|---|---|---|
| A1 | Firms | ~4,000 | Brief | High | Number of tenants | Confirmed |
| A2 | Active engagements; per-firm skew | 800k; median ~40, max ~40k (mean ~200) | Brief | High | Hot partitions; UX for the largest firms | Confirmed |
| A3 | Products; publish rate | 40; ~1/week each → ~173/month | Brief + arithmetic | High | Hot-path frequency; number of summaries | Confirmed |
| A4 | Engagements per product | Skewed | User | Medium | One publish may touch a large share of engagements | Confirmed (shape only) |
| A5 | Engagement load | ~1 min, hard constraint; loader owned by another team with limited capacity | Brief | High | Loads stay off the hot path; requires a throttled cold path | Confirmed |
| A6 | Loader cost counted in $X | Included | User | High | Backfill and reconciliation become budget lines | Confirmed |
| A7 | Loader slot size | 2 vCPU / 8 GB ≈ $0.117 per slot-hour | Proxy from Fargate pricing | Low | Loader cost scales linearly with slot size | Proposed |
| A8 | Loader concurrency N | 20 / 50 / 200 | Proposed scenarios | Low | Changes backfill duration, not its cost | Proposed |
| A9 | Backfill volume | 800k loads + 10% retries; opportunistic capture allowed (reduction TBD) | User + proposal | Medium | ~14.7k slot-hours one-time | Proposed |
| A10 | Workload across scenarios | Identical | User | High | Scenarios vary only quality levers | Confirmed |
| A11 | Budget ceiling $X | **TBD** | User | — | Set in WBS 4.1 | Open |
| A12 | Residency cells | USA us-east-1 · Canada ca-central-1 · Europe eu-central-1 · Americas (LatAm) sa-east-1 · Asia ap-southeast-1 | User (cells) / proposal (regions) | Medium | Fixed overhead is paid 5 times | Proposed |
| A13 | Engagement split across cells | **TBD** | — | — | Sizing and backfill per cell | Open |
| A14 | Summary scope | Generic, from the version diff only, generated once globally | User | High | No engagement data reaches the LLM, so residency is not affected | Confirmed |
| A15 | LLM | Claude Haiku 5.5 on Amazon Bedrock, global endpoint, Standard on-demand | User + research | Medium | Low cost; availability expected until at least ~Oct 2027 | Confirmed; **Bedrock quote pending** |
| A16 | Summary size | Generation ~26k in / 4.6k out (includes ~2k thinking tokens); judge ~28k in / 1.5k out; all below 100K | Hypothetical, adjusted for the new tokenizer | Low | LLM cost scales linearly with this | Proposed |
| A16b | Global routing of template content | Allowed (template content is not firm data) | Brief | Medium | US-only routing adds +10%; negligible | Open (minor) |
| A17 | Human review of summaries | None; automated checks only | User | High | Weaker regulatory defence; Mid/High partly compensate with a judge model | Confirmed |
| A18 | Retention of summaries and decisions | 10 years, immutable, hashed, model and prompt version recorded | User | High | Immutable storage needed; volume negligible | Confirmed |
| A19 | Push to open sessions | WebSocket in each cell; ~10k users connected 8 h/day, 22 days/month | User / hypothetical | Low (user count) | ~$26/month | Proposed |
| A20 | Compute | Lambda for the hot path; EKS for the fan-out worker; existing EKS clusters shared | User / proposal | Medium | Shared clusters cost $0 extra; 5 dedicated clusters ≈ $365/month | Proposed |
| A21 | Pricing model | On-demand everywhere | User | High | Fits bursty load; no commitments | Confirmed |
| A22 | Meaning of "real time" | Pending flag within seconds; summary may lag | Requirement challenge | Medium | Keeps the LLM off the hot path | Proposed |

## 4. Low / Mid / High scenario comparison

| Lever / cost | Low | Mid | High |
|---|---|---|---|
| Loader concurrency N (all cells combined) | 20 | 50 | 200 |
| Naive backfill duration (24/7, no capture) | 27.8 days | 11.1 days | 2.8 days |
| **One-time:** backfill (proxy, +10% retries) | ~$1.7k | ~$1.7k | ~$1.7k |
| Reconciliation sample per month | 0.5% (4k loads) ≈ $8 | 2% (16k) ≈ $31 | 5% (40k) ≈ $78 |
| Summary granularity | One per publish (~173/month) | One per publish | One per distinct from→to pair, ~5 on average (~867/month) |
| Automated evaluation | None | Haiku 5.5 as judge | Haiku 5.5 as judge |
| LLM per month (Haiku 5.5) | ~$0.85 | ~$1.50 | ~$7.30 |
| DynamoDB (~3.5M writes/month + storage) | ~$3 | ~$3 | ~$3 |
| WebSocket, 5 cells | ~$26 | ~$26 | ~$26 |
| Lambda hot path | <$5 | <$5 | <$5 |
| EKS fan-out worker (0.5 vCPU / 1 GB, one pod per cell) | ~$90 | ~$90 | ~$90 |
| **Visible recurring subtotal per month** | **~$133** | **~$157** | **~$209** |
| Not yet costed (WBS 4.1) | Fixed overhead per cell × 5: NAT, VPC endpoints, CloudWatch/New Relic, data transfer, S3/SQS, price uplift outside us-east-1 | same | same |

### Arithmetic

- Backfill duration = 800,000 ÷ N ÷ 1,440 min/day. Backfill work = 800,000 min = 13,333 slot-hours, +10% retries ≈ 14,667 slot-hours.
- Slot rate = 2 × $0.000011244 × 3,600 + 8 × $0.000001235 × 3,600 ≈ $0.1165 per hour.
- Publishes per month = 40 × 52 ÷ 12 ≈ 173.
- Pending-flag writes per month ≈ 800k × 4.33 ≈ 3.47M × $0.625 per 1M ≈ $2.17.
- One summary = 26k × $0.10/1M + 4.6k × $0.50/1M ≈ $0.0049. One judge call ≈ $0.0036.
- WebSocket = 10k users × 480 min × 22 days ≈ 105.6M minutes × $0.25/1M ≈ $26.40.
- One worker pod = (0.5 × $0.0405 + 1 × $0.0044) × 730 h ≈ $18 per cell.

### Key findings

1. **Backfill cost does not depend on N; only its duration does.** Concurrency is set by the loader team's spare capacity, not by budget.
2. **Variable cost is small.** The budget will be driven by fixed overhead per cell × 5 and the one-time backfill, not by inference (~85% confidence).
3. **Inference is negligible with Haiku 5.5.** Even a 10× error in diff size keeps it under ~$75/month.

## 5. Challenges raised during this activity

| Topic | Challenge | Outcome |
|---|---|---|
| "Real time within seconds" | Applies to the pending flag only; the summary may lag | Proposed (A22) |
| Tailored per-firm summaries | They increase liability: thousands of unreviewed, non-deterministic texts built from confidential data | Generic summaries chosen (A14) |
| "Use provisioned capacity" | Bursty, low-volume load is cheaper on-demand | On-demand chosen (A21) |
| Jamba / Jurassic models | Jamba retires 26 Nov 2026; Jurassic-2 appears retired and reads only 8K tokens at once | Replaced by Haiku 5.5 (A15) |
| Haiku 5.5 cost traps | Thinking tokens are billed as output by default; the new tokenizer counts ~30% more tokens | Included in A16 |
| No human review | Weakens the case in a regulatory review months later | Accepted for time; revisit in WBS 5.2 |

## 6. Unresolved questions for later WBS items

| ID | Question | WBS |
|---|---|---|
| U1 | What does a decline pin? This sets the number of distinct summary pairs (k) | 1.2 |
| U2 | Store a pending flag per engagement or compute it at read time; DynamoDB key design for skewed tenants | 2.2 |
| U3 | Loader availability window (24/7 or off-peak); actual reduction from opportunistic capture | 3.1 |
| U4 | How engagements split across cells; Asia region choice (Singapore vs. Tokyo) | 3.3 / 4.1 |
| U5 | Fixed overhead per cell; price uplift outside us-east-1; S3/SQS prices; concurrent users; the $X ceilings | 4.1 |
| U6 | Amazon Bedrock quote for Haiku 5.5; model and prompt versioning; automated evaluation design | 5.1 / 5.2 |
| U7 | Real diff sizes in tokens | 5.1 |
| U8 | EKS clusters shared or dedicated | 4.1 |

## 7. AI usage log (WBS 1.1)

| # | AI contribution | Human decision / correction |
|---|---|---|
| 1 | Separated a seconds-level hot path from a throttled cold path | Accepted; the user had first proposed scaling with cloud compute |
| 2 | Argued that tailored summaries increase, not reduce, liability; narrowed "provisioned" to steady load only | User chose generic summaries and on-demand pricing |
| 3 | Flagged that Jamba retires on 26 Nov 2026 | User switched to Jurassic |
| 4 | Flagged that Jurassic appears retired, reads only 8K tokens at once and is expensive | Used only as a cost ceiling |
| 5 | Researched a model with a long availability window; chose Haiku 5.5 over Haiku 4.5; flagged thinking-token and tokenizer costs | User accepted Haiku 5.5, priced from Anthropic's site, with an AWS quote pending |

**Where AI should not be trusted here:** model prices and retirement dates change monthly. Every figure above carries a source and a verification date and must be rechecked before submission.

## 8. Sources

- DynamoDB pricing: https://aws.amazon.com/dynamodb/pricing/
- API Gateway pricing: https://aws.amazon.com/api-gateway/pricing/
- EKS pricing: https://aws.amazon.com/eks/pricing/
- Fargate pricing: https://aws.amazon.com/fargate/pricing/
- Lambda pricing: https://aws.amazon.com/lambda/pricing/
- Bedrock pricing: https://aws.amazon.com/bedrock/pricing/
- Bedrock model lifecycle: https://docs.aws.amazon.com/bedrock/latest/userguide/model-lifecycle.html
- Bedrock regional availability: https://docs.aws.amazon.com/bedrock/latest/userguide/models-region-compatibility.html
- Claude Haiku 5.5 (Anthropic): https://www.anthropic.com/claude/haiku
- Regional endpoint premium (third-party): https://www.cloudforecast.io/blog/aws-bedrock-pricing/
- AWS Pricing Calculator: https://calculator.aws/
