---
name: review
description: Run one or more of the project's review agents (general, security, performance, migration, k8s, docs, architecture) against the current uncommitted changes, in parallel, and report their findings
argument-hint: "[1=general 2=security 3=performance 4=migration 5=k8s 6=docs 7=architecture, e.g. 123, 13, 2, 16 — 4/5 always auto-added when relevant; 6 only runs when typed]"
disable-model-invocation: true
allowed-tools: Bash(git status *) Bash(git diff *)
---

Review the current uncommitted changes using the project's review agents. This is the manual entry to
the same panel the autopilot runs: the agents, criteria (`.ai/rules/review/`) and verdict format are the
same, so a change you review here is judged exactly as an autopilot change would be.

## Step 1: Parse explicitly requested reviewers from $ARGUMENTS

Argument: $ARGUMENTS

- `1` → `review-general` (correctness, test adequacy, conventions — the former `code-reviewer`'s test and
  conventions sections)
- `2` → `review-security` (tenant isolation, auth, injection, secrets)
- `3` → `review-performance` (N+1, Kafka throughput, concurrency, memory)
- `4` → `review-migration` (Flyway migrations, entity ↔ schema)
- `5` → `review-k8s` (Dockerfile/Deployment/service-parent/kustomize, POM supply chain)
- `6` → `review-docs` (README.md/CLAUDE.md/.ai/ and Javadoc consistency with the diff)
- `7` → `review-architecture` (boundaries, decided patterns, ADRs — the former `code-reviewer`'s
  shared-security and patterns sections)

Read every digit present in $ARGUMENTS, in any order, separator, or duplication.

If $ARGUMENTS is empty (bare `/review`), the explicitly-requested set is `1, 2, 3, 7` (the old default
`1, 2, 3`, now that the old code reviewer is split into general and architecture). `6` is never part of
this default — it only runs when typed explicitly.

If $ARGUMENTS contains characters that aren't `1`–`7` and nothing valid at all, stop and ask which
reviewer(s) were meant instead of guessing.

## Step 2: Confirm there's something to review, and always gate-check 4/5

Run `git status` and `git diff --name-only` (also `git diff --cached --name-only` if anything is staged)
to get the list of changed files. If there are no changes at all, say so and stop.

**Run this check every time, no matter what $ARGUMENTS was:**

- Migration files or entities: does the changed-files list contain a path matching
  `src/main/resources/db/migration/.*\.(sql|java)$`, or does the diff add/change an `@Entity`, `@Table`,
  `@Column` or `@Version`? If yes, add `review-migration`.
- K8s/build files: does the changed-files list contain a path matching
  `(^|/)k8s/|(^|/)Dockerfile$|(^|/)pom\.xml$`? If yes, add `review-k8s`.

If `4` or `5` was explicitly typed but its check finds no match, note it as skipped in the final report.
`review-docs` (`6`) has no auto-gate here.

**Final set = (reviewers from Step 1) ∪ (review-migration and/or review-k8s, whichever gates matched).**

## Step 3: Dispatch the final set

Invoke every reviewer in the final set in the same turn, so they run in parallel. Tell each one: "Manual
review of the uncommitted changes (`git diff HEAD`); round 1; write the readable report and end with the
verdict JSON." Don't summarize the diff for them — they read it themselves.

## Step 4: Report results

Present each reviewer's findings under its own heading (`## General review`, `## Security review`,
`## Performance review`, `## Migration review`, `## Kubernetes/build review`, `## Docs review`,
`## Architecture review` — only for the ones that ran). Say in one line which gated reviewers were added
or skipped. Don't merge or re-rank findings across reviewers.

Then one line: whether any reviewer raised a **blocking** finding per `.ai/rules/review/_common.md` (tenant
isolation, auth, shared security, startup-breaking migration/build, data loss — and any finding citing a
rule with a concrete impact), or a `NEEDS_HUMAN`. That decides whether it's safe to move on to `/ship`.
