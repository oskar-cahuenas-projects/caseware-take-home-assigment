# Caseware Take-Home: Template Update Tracking

This repository contains my solution to the Caseware take-home architecture exercise ([brief](docs/Challenge.pdf)). The system shows users which engagement files have pending product template updates and gives them a human-readable summary of the changes. The difficulty is that each engagement takes about one minute to load, through a service with limited capacity. Part 1 is the design. Part 2 implements one component in Java: the template-publish fan-out worker.

## Deliverables

| # | Deliverable (from the brief) | Location |
|---|---|---|
| 1 | Design document | [docs/solution-design.md](docs/solution-design.md) |
| 2 | Code for Part 2, with a short README | [fanout-java/](fanout-java/) (Java 21, Maven): Load Executor plus thin Planner, 218 tests including contract tests E1–E10, and a runnable demo on fake data. [README and tradeoffs](fanout-java/README.md); specified in [fanout-java/fanout-worker-contract.md](fanout-java/fanout-worker-contract.md) |
| 3 | Session history of the AI tools I used | Part 1: [docs/chat-session-log/log-chat-design-document.md](docs/chat-session-log/log-chat-design-document.md). Part 2: [docs/chat-session-log/log-chat-coding-fanout.md](docs/chat-session-log/log-chat-coding-fanout.md) |
| 4 | Diagrams | Mermaid sources: [image-01](docs/diagrams/mermaid/image-01.mmd), [image-02](docs/diagrams/mermaid/image-02.mmd), [image-03](docs/diagrams/mermaid/image-03.mmd), [image-04](docs/diagrams/mermaid/image-04.mmd). PNG files: [image-01](docs/img/image-01.png), [image-02](docs/img/image-02.png), [image-03](docs/img/image-03.png), [image-04](docs/img/image-04.png) |

The working notes behind each design section, one file per work package, are in [fanout-java/analysis-documents/](fanout-java/analysis-documents/).

## How I used AI

- **Part 1 (design):** I wrote [docs/solution-design.md](docs/solution-design.md) with Claude Chat in the desktop app, using the Opus 5.5 model. I worked in one chat session per work package, following these steps:

  1. **Problem understanding and planning:** I started a chat session with AI acting as a Staff Java Engineer specializing in banking, audit and accounting software, and microservices architecture. The AI reviewed the challenge requirements and job description, then summarized the problem. It asked clarifying questions to assess my understanding and explained relevant accounting and auditing terminology. We agreed on key assumptions and organized the work into a Work Breakdown Structure (WBS), documented in [docs/wbs.md](docs/wbs.md).

  2. **Design exploration:** For each WBS item (in [fanout-java/analysis-documents](fanout-java/analysis-documents)) I created a separate chat session to discuss the topic, evaluate alternatives, and document assumptions and proposed solutions in Markdown files. Topics included hot and cold paths, rollback strategies, AWS services, scalability, and cost estimates.

  3. **Final documentation and implementation scope:** Using the documents produced in previous steps, I completed the final WBS task: consolidating the analysis into the solution design document and defining the scope for the Java implementation, focusing on the selected fan-out worker component.

- **Part 2 (code):** I built [fanout-java/](fanout-java/) with Claude Code CLI in VS Code, using the Opus 5.5 model. One master prompt, [fanout-java/prompts/master-prompt.md](fanout-java/prompts/master-prompt.md), drove the work:

  1. **Bootstrap (no code):** the AI turned the contract into requirements, 17 story cards and 28 contract-gap decisions ([fanout-java/docs/](fanout-java/docs/), [fanout-java/stories/](fanout-java/stories/)). I approved them at two checkpoints.

  2. **One story at a time:** for each story the AI filled the story template into [fanout-java/prompts/stories/](fanout-java/prompts/stories/) and executed it: tests first, implementation, build gate, repeated runs of concurrency tests, and one commit. A third checkpoint after story 13 let me decide what to build next.

- **AI usage notes:** [ai-usage-notes.md](docs/ai-usage-notes.md) explains where AI helped, where I corrected or ignored it, how I would guide other engineers, and where AI should not be trusted in this domain.
- **Session logs:** the AI sessions, in Markdown format, are in [docs/chat-session-log/](docs/chat-session-log/).
