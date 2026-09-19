---
name: review
description: Run one or more of the project's review subagents (code-reviewer, security-reviewer, performance-reviewer) against the current uncommitted changes
argument-hint: [1=code-reviewer 2=security-reviewer 3=performance-reviewer, e.g. 123, 13, 2 — omit for all three]
disable-model-invocation: true
---

Review the current uncommitted changes using the project's review subagents.

## Step 1: Parse which reviewers to run

Argument: $ARGUMENTS

- Digit `1` → the `code-reviewer` subagent (architecture/conventions)
- Digit `2` → the `security-reviewer` subagent (auth, injection, tenant
  exposure, secrets)
- Digit `3` → the `performance-reviewer` subagent (N+1, Kafka throughput,
  concurrency, memory)

Read every digit present in $ARGUMENTS, in any order or combination —
`123`, `13`, `2`, `21`, etc. all work; duplicates and separators (spaces,
commas) don't matter, only which digits appear.

If $ARGUMENTS is empty (bare `/review`), run all three.

If $ARGUMENTS contains no `1`, `2`, or `3` at all (e.g. stray text, a `4`),
stop and ask which reviewer(s) were meant instead of guessing.

## Step 2: Confirm there's something to review

Run `git status` / `git diff`. If there are no uncommitted changes, say so
and stop — don't invent a review of already-committed or nonexistent
changes.

## Step 3: Dispatch the selected subagents

Invoke every selected subagent in the same turn, so they run in parallel
rather than one after another. Each subagent reviews the same current
uncommitted changes independently — don't summarize the diff for them
first, they read it themselves.

## Step 4: Report results

Present each subagent's findings under its own heading (`## Code review`,
`## Security review`, `## Performance review` — only for the ones that ran).
Don't merge or re-rank findings across subagents; each has its own severity
ordering already. After all sections, add one line stating whether any
reviewer raised a blocking issue (tenant isolation, auth, or shared-security
findings count as blocking; style/consistency notes don't) — this is what
decides whether it's safe to move on to `/ship`.