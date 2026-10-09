# ADR-0018: Coverage is enforced twice: per module, and on a PR's changed lines (backlog #0-57)

- **Status:** Accepted
- **Backlog:** #0-57
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

> Note (2026-10-05): the `code-reviewer` agent named below was split; its test-adequacy section is
> now `review-general` (`.ai/rules/review/general.md`, rules GEN-01 to GEN-09).

Until #0-57 no coverage rule had ever run: surefire's explicit `<argLine>` replaced the property
`jacoco:prepare-agent` sets, so no `jacoco.exec` was written and `jacoco:check` skipped itself with
a green build. The PR comment (`madrapps/jacoco-report`) never failed a job either — its thresholds
only pick an emoji — and was never even posted: the workflow's read-only `GITHUB_TOKEN` got "Resource
not accessible by integration", hidden by the action's default `continue-on-error`. It now writes to
the job summary; granting `pull-requests: write` was rejected because this job runs the PR's own
test code. Now:

- `jacoco:check` (in `verify`): LINE ≥ 60% per module, over the classes it does not exclude.
- CI's "Enforce coverage of changed lines" step: `diff-cover` (pinned) over the JaCoCo XML,
  ≥ 60% of the Java lines a PR adds or changes. Chosen over the madrapps output, which averages
  whole changed files, so a small fix in an old weakly covered file failed and an untested new class
  could hide behind a big tested one.
- The patch score is still one number over all changed lines, like Codecov's "patch": a PR that is
  mostly well tested can carry one untested class (its missing lines are listed in the job summary).
  Judging tests per class is the reviewer's job, not the gate's: following the "Test adequacy"
  section of its instructions, the `code-reviewer` agent maps each changed behaviour to the test
  that would fail if it broke.
- `report` and `check` carry the same `excludes`; keep them equal, or the two gates judge different
  code. They are not set at plugin level because `prepare-agent` would take them as "do not
  instrument".
- JaCoCo counts only a module's own tests. `shared` classes exercised by service tests still show
  as uncovered in `shared` (backlog #0-58), so a PR changing them needs tests in `shared`.
