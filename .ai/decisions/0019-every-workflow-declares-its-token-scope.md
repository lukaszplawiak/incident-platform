# ADR-0019: Every workflow declares its token scope (backlog #0-59)

- **Status:** Accepted
- **Backlog:** #0-59
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

Each file under `.github/workflows/` declares a workflow-level `permissions:` block with only
`contents: read`, and only a job that needs more declares its own block: `detect-changes` adds
`pull-requests: read` (`dorny/paths-filter` lists a PR's files through the API), the Snyk jobs add
`security-events: write` (SARIF upload).
Nothing relies on the repository's "Workflow permissions" setting, which is invisible to review.
For a new job or workflow:

- A job-level `permissions:` replaces the workflow's, it does not add to it, so it must repeat
  `contents: read`. Every scope not listed is `none`.
- Never give a write scope to a job that runs a PR's own code (build, tests, Docker, smoke test).
  That is why the coverage report goes to the job summary rather than a PR comment (#0-57).
- `permissions:` does not protect secrets (`SNYK_TOKEN`, `NVD_API_KEY`). Keeping a compromised
  action or tool from running is #0-60 (actions pinned by SHA, below) and #0-61 (pinned Snyk CLI).
