# 01 — Create the Maven project

- **Epic:** E0 Setup
- **Priority:** Must
- **Status:** To do
- **Depends on:** None
- **Contract refs:** §0 A5, §F (in-memory adapters)
- **Contract tests:** None
- **Checkpoint after:** None

## User story
As a developer, I want a compiling Java 21 Maven project with a test runner, so that every later story ends on a green build.

## Context
Fixes the stack (NFR-07). No runtime dependencies; JUnit 5 and AssertJ in test scope.

## Acceptance criteria
- AC1 — Given a clean checkout, When `cd fanout-java && ./mvnw -q verify` runs, Then it succeeds with one smoke test passing (→ NFR-07).
- AC2 — Given `pom.xml`, When dependencies are listed, Then only JUnit 5 and AssertJ appear, both in `test` scope (→ NFR-07).
- AC3 — Given Surefire, When tests tagged `contract` exist, Then they run in the default build (→ NFR-04).
- AC4 — Given the root `CLAUDE.md`, Then it lists the build and test commands (incl. running one test class and the `contract` tag).

## Tasks
- [ ] Maven wrapper, `pom.xml` (`maven.compiler.release=21`, Surefire, UTF-8).
- [ ] Package skeleton `com.caseware.fanout.{domain,port,executor,planner,adapter.memory}` (package-info only).
- [ ] Smoke test.
- [ ] Commands in root `CLAUDE.md`.

## Out of scope
- Any domain or port type (story 02).

## Definition of Done
- All ACs covered by passing tests; `./mvnw -q verify` green; concurrency tests pass 5/5 runs (if applicable);
  card, index and traceability matrix updated; commit created.

## Notes / decisions
<Filled during implementation.>
