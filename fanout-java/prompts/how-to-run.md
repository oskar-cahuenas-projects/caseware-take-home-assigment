# How to run the prompts

All commands are typed in Claude Code, started from the repository root (`caseware-take-home-assignment/`).

## 1. Bootstrap (once)

```text
Read fanout-java/prompts/master-prompt.md. Follow §1 and execute §2 (Prompt A).
```

- The AI writes `fanout-java/docs/requirements.md`, `fanout-java/stories/NN-story-slug.md` and `fanout-java/stories/index.md`, then stops at **Checkpoint 1**.
- Review the backlog. Reply `approved`, or give corrections.
- The AI writes `fanout-java/docs/contract-gaps.md` and `fanout-java/docs/design.md`, then stops at **Checkpoint 2**.
- Review the gap decisions. Reply `approved`, or give corrections. The bootstrap ends here; no Java code has been written.

## 2. Each story (in index order)

**a. Create the story prompt**

```text
Create the prompt for story NN from §3 of fanout-java/prompts/master-prompt.md.
Use commit mode <commit|stage-only> and extra instructions: <None|...>.
```

The AI fills the placeholders from the story card and saves `fanout-java/prompts/stories/NN-story-slug.prompt.md`. Review it.

**b. Run the story**

```text
Execute fanout-java/prompts/stories/NN-story-slug.prompt.md
```

The story ends with a green build, an updated card and index, a commit (or staged files) and a short report.

## 3. Checkpoint 3 (after story 13)

Story 13 ends with a review report and stops. Decide:
- **Continue:** run stories 14–15 (thin Planner, Tracking Service contract test).
- **Skip:** mark 14–15 as `Dropped` in their cards and in `index.md`, then go to story 16.

## 4. Finish

Run story 16 (full test run and review), then story 17 (README and docs).

## If something goes wrong

- **The build is red before a story starts:** the AI stops. Fix it first, or ask the AI to fix it in a separate prompt.
- **A story needs a decision that isn't covered:** the AI stops and asks. Answer, and it records the decision in `contract-gaps.md`.
- **You are running out of time (budget 2.5–3 h):** drop the remaining Should stories and go straight to stories 16–17.
