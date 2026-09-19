---
name: ship
description: Run tests for the changed modules, and only if they pass, create a branch, commit, push, and open a PR following this repo's CLAUDE.md conventions
argument-hint: [short description of the change]
disable-model-invocation: true
allowed-tools: Bash(git checkout -b *) Bash(git add *) Bash(git commit *) Bash(git push *) Bash(git status *) Bash(git diff *) Bash(./mvnw test *) Bash(gh pr create *)
---

Ship the current change: $ARGUMENTS

This skill assumes the implementation is already done and reviewed in
conversation — analysis, options, and implementation happen beforehand,
per the "Working style" section of CLAUDE.md. This skill only covers testing
and getting a finished change into a PR. Don't do any design/implementation
work here even if it seems like a one-line fix — if something looks
unfinished or untested, stop and say so instead of finishing it here.

1. **Verify there's something to ship.** Run `git status` / `git diff`. If
   there are no staged or obviously-relevant uncommitted changes, stop and
   say so instead of proceeding.

2. **Run tests for every changed module — this is a gate, not a formality.**
   From the changed file paths, determine which Maven module(s) are affected
   (top-level directory: `shared`, `auth-service`, `incident-service`, etc.).
   If `shared` changed, treat all 7 services as affected, per CLAUDE.md.
   Run `./mvnw test -pl <affected-modules>` for exactly those modules.

    - If any test fails: **stop here.** Do not branch, commit, or push. Report
      which test(s) failed and the relevant failure output, and ask whether I
      want you to investigate the failure or whether I'll handle it myself.
      Never commit code with failing tests, and never skip or comment out a
      failing test to make this step pass.
    - If tests pass: continue to step 3.

3. **Branch.** Pick the right prefix (`fix/`, `feat/`, `refactor/`, `docs/`,
   `test/`, `chore/`, `perf/`, `build/`, `ci/`, `style/`) and a short
   kebab-case name, based on the changed files and the description above.
   Create and switch to the branch with `git checkout -b`.

4. **Commit.** Stage only the changes relevant to this piece of work (check
   `git status`/`git diff` — don't blindly `git add -A`). Commit using
   Conventional Commits with a service scope, e.g. `fix(incident-service): ...`,
   per the Conventions section of CLAUDE.md. Write a full commit body, not
   just a one-line subject: explain what changed and why, and reference the
   backlog item if one applies.

5. **Push.** Push the new branch to origin with `-u`.

6. **Open PR.** Use `gh pr create` with:
    - `--base main --head <branch>`
    - a title matching the commit's subject line
    - a body with `## Summary` (bullet points of what changed and why), a note
      that tests pass for the affected module(s), and if relevant
      `## Notes for reviewers` (anything that could look like a mistake but
      isn't, discrepancies with docs, etc.)

Stop and ask before any step if:
- the changes span what looks like more than one logical commit — ask whether
  to split them
- you're unsure which branch prefix, service scope, or affected module applies
- the test run reveals the change is incomplete (e.g. a new class with no
  test, which CLAUDE.md's coverage gate will fail on anyway)