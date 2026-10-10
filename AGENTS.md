# AGENTS.md

Instructions for any AI coding agent working in this repository (Codex, Qwen Code, Claude Code, others).
Claude Code reads `CLAUDE.md`, which says the same at more length; keep the two consistent.

1. Read `.ai/README.md` first: it says what to read for your task and which files you may write.
2. Build and test commands are in `CLAUDE.md` ("Commands"). Tests that use Testcontainers need Docker.
3. Tenant isolation is the property most easily broken here. Before touching authentication, a tenant
   id, Kafka, an HTTP client between services or a notification recipient, read `.ai/context/security.md`.
4. Review and acceptance criteria are in `.ai/rules/`. They are owned by the human maintainer: read
   them, never edit them, and never edit a path listed in `.ai/rules/protected-paths.md`.
5. Stay inside the task. A defect you notice outside it becomes a proposed backlog item in your report,
   not a change in your diff.
6. Never weaken a check to make it pass: no skipped or disabled tests, no `-DskipTests` in a
   verification run, no edited Flyway migration that is already on `main`, no new Maven repository or
   plugin without saying so in your report.
7. If something is ambiguous and you cannot ask, stop and say what decision is needed. Do not guess.
