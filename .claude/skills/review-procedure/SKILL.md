---
name: review-procedure
description: How a review-panel agent reviews one change — which diff, what to read, how rounds and finding ids work, and the verdict JSON. Preloaded into the review-* agents; not a command.
user-invocable: false
---

# Review procedure (one dimension)

Your dimension is named in your agent definition; your criteria are `.ai/rules/review/<dimension>.md`.
Always read `.ai/rules/review/_common.md` first: it decides what is blocking.

## 1. Which change

Your task says where the change comes from:

- **Autopilot**: a branch, `mergeBase`, `round`, and for round ≥ 2 `previousRoundSha` plus your own
  blocking findings of the previous round (with ids) and any `disputed` entries from `handoff.md`.
  - Round 1: `git diff <mergeBase>...HEAD`.
  - Round ≥ 2: `git diff <previousRoundSha>..HEAD` for the delta, and `git diff <mergeBase>...HEAD` only to
    see the context of a finding. Judge per `_common.md` "Rounds".
- **Manual `/review`**: the uncommitted changes — `git diff HEAD` (staged and unstaged) — or the range
  the person names.

Also `git log --oneline <mergeBase>..HEAD` for the commit messages (GEN-23 needs them).

## 2. What to read, and only that

1. `_common.md` and your dimension's file.
2. `.ai/work/<item>/handoff.md` when it exists: facts to verify, not arguments to accept.
3. The `.ai/context/` file your criteria file names, and the ADRs whose area the change touches
   (`.ai/decisions/README.md` has an area column — do not read all ADRs).
4. The changed files, and the code they call or that calls them, as far as your dimension needs.

The tests already ran (the autopilot's gate) and passed; their result is in your task. You cannot run
them yourself.

## 3. Findings

For each problem: the rule id from your criteria file, file and line, what is wrong, the concrete
impact, the smallest correct fix. Blocking only per `_common.md`. A finding id is
`<dimension prefix>-<4 hex>`: keep the id of a finding you already reported in an earlier round; derive
a new one from file + rule + the offending fragment, so it stays the same if you see it again.

Prefixes: `gen` general, `arc` architecture, `sec` security, `perf` performance, `mig` migration,
`k8s` k8s, `doc` docs.

## 4. Answer

The verdict JSON from `_common.md`, complete (empty arrays, not missing keys). `APPROVE` exactly when
`blocking` is empty and no `NEEDS_HUMAN` condition holds. Autopilot: the JSON is your whole answer.
Manual: a readable report, then the JSON in a fenced block.
