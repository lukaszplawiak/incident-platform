---
name: review
description: Run one or more of the project's review subagents (code-reviewer, security-reviewer, performance-reviewer, migration-reviewer, k8s-manifest-reviewer, docs-reviewer) against the current uncommitted changes
argument-hint: [1=code 2=security 3=performance 4=migration 5=k8s-manifest 6=docs, e.g. 123, 13, 2, 16 — 4/5 always auto-added when relevant, regardless of what you type; 6 only runs when explicitly typed]
disable-model-invocation: true
allowed-tools: Bash(git status *) Bash(git diff *)
---

Review the current uncommitted changes using the project's review subagents.

## Step 1: Parse explicitly requested reviewers from $ARGUMENTS

Argument: $ARGUMENTS

- `1` → `code-reviewer` (architecture/conventions)
- `2` → `security-reviewer` (auth, injection, tenant exposure, secrets)
- `3` → `performance-reviewer` (N+1, Kafka throughput, concurrency, memory)
- `4` → `migration-reviewer` (Flyway migrations)
- `5` → `k8s-manifest-reviewer` (Dockerfile/Deployment/service-parent/kustomize)
- `6` → `docs-reviewer` (README.md/CLAUDE.md/.ai/ consistency with the diff)

Read every digit present in $ARGUMENTS, in any order, separator, or
duplication.

If $ARGUMENTS is empty (bare `/review`), the explicitly-requested set is
`1, 2, 3`. `6` is never part of this default — it only runs when typed
explicitly, same as `4`/`5` would if their auto-gate didn't also cover them.

If $ARGUMENTS contains characters that aren't `1`–`6` and nothing valid at
all, stop and ask which reviewer(s) were meant instead of guessing.

This step only determines 1/2/3/6 (and any explicit 4/5) — it does not yet
decide the final set. Step 2 always runs regardless of what was parsed
here, but it only ever adds 4/5, never 6.

## Step 2: Confirm there's something to review, and always gate-check 4/5

Run `git status` and `git diff --name-only` (also `git diff --cached
--name-only` if anything is staged) to get the list of changed files. If
there are no changes at all, say so and stop.

**Run this check every time, no matter what $ARGUMENTS was** — it's two
cheap `grep`s, not worth skipping:

- Migration files: does the changed-files list contain a path matching
  ```
  grep -E 'src/main/resources/db/migration/.*\.sql$'
  ```
  If yes, add `migration-reviewer` to the final set, even if `4` wasn't
  typed.
- K8s/build files: does the changed-files list contain a path matching
  ```
  grep -E '(^|/)k8s/|(^|/)Dockerfile$|(^|/)pom\.xml$'
  ```
  If yes, add `k8s-manifest-reviewer` to the final set, even if `5` wasn't
  typed.

If `4` or `5` was explicitly typed but its check finds no match, note it as
skipped in the final report rather than running it anyway — the gate is
deterministic either way, whether a reviewer entered the set by explicit
request or by auto-detection.

`docs-reviewer` (`6`) has no auto-gate — it runs only if `6` was explicitly
typed. Don't add it here under any condition.

**Final set = (reviewers from Step 1) ∪ (migration-reviewer and/or
k8s-manifest-reviewer, whichever gates matched).**

## Step 3: Dispatch the final set

Invoke every reviewer in the final set in the same turn, so they run in
parallel. Each subagent reviews the current uncommitted changes
independently — don't summarize the diff for them first, they read it
themselves.

If the final set is empty (shouldn't normally happen, since 1/2/3 default
in), say so and don't invoke anything.

## Step 4: Report results

Present each reviewer's findings under its own heading (`## Code review`,
`## Security review`, `## Performance review`, `## Migration review`,
`## Kubernetes/build review`, `## Docs review` — only for the ones that
actually ran). If `migration-reviewer` or `k8s-manifest-reviewer` ran
because their gate matched even though not explicitly requested, say so in
one line (e.g. "Also ran: migration-reviewer — this change touches a
Flyway migration"). If either was explicitly requested but skipped by its
gate, note that too. Don't merge or re-rank findings across reviewers; each
has its own severity ordering already. After all sections, add one line
stating whether any reviewer raised a blocking issue (tenant isolation,
auth, shared-security, or startup-breaking migration/build findings count
as blocking; style/consistency notes and docs-drift don't) — this is what
decides whether it's safe to move on to `/ship`.
