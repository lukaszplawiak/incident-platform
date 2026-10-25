# ADR-0044: A seed branch starts from main: the merged change is reverted and brought back on top of it, and scripts check the tree before and after the seeder (backlog #0-173)

- **Status:** Accepted (2026-10-25)
- **Backlog:** #0-173
- **Date:** 2026-10-25
- **Author:** human (owner chose F2 of the research, then the proposals of review round 1; written up by Claude Code in
  the owner's session)
- **Reversible:** yes

## Context

`/seed-bugs` measures the review panel: it plants a known defect in a merged change and records who caught it. The
seeder used to make its branch on the merged commit itself (`git switch -c seed/… <commit>`), in the working tree the
session runs in. The devcontainer and the owner's own sessions mount that same tree. Claude Code reads hook scripts from
disk on every call, and it reloads agents, skills and `CLAUDE.md` when they change (ADR-0042). So from the switch on,
the run used the old commit's definitions. On 2026-10-25 a run switched to `253e5b2a`:

- that commit's guard refused the seeder's reads;
- its settings named a `read-scope.sh` the old tree did not have, so every file read failed;
- the owner's session on the host lost its `verifier` agent until the tree was switched back.

A run that had gone through would have had the panel review under older agents and weaker guards.

## Decision

1. **The branch starts from the current `main`, never from the merged commit.**
   - `scripts/factory/seed-branch.sh <pattern> <run id> <commit>`, run by `factory-ops`, makes
     `seed/<pattern>-<run id>` from `main` with two commits:
     - A: the change's code reverted. This is the base the panel diffs against (`mergeBase`).
     - B: the change's code back, under the change's own subject.
   - The panel therefore reviews the real change with the defect inside it, as the benchmark means, and the tree keeps
     `main`'s hooks, agents and rules (F2 of the research; F1, planting on `main` with no change around the defect,
     would measure an easier task).
2. **Only the change's code is replayed.**
   - Markdown, including the backlog files, would clash with today's `main`, so it is left out.
   - A rename counts as a deletion plus an addition (`--no-renames`).
   - A merge commit is replayed as its whole pull request (the diff from its first parent), under its second parent's
     subject.
   - The files the seeder may plant in leave out the ones the change deleted.
   - Paths are passed to git one per array element and read literally (`--literal-pathspecs`).
3. **What `seed-branch.sh` refuses**, leaving a clean `main` behind each time:
   - a tree that is not a clean `main` at `origin/main`;
   - an autopilot run in progress (`.ai/runs/LOCK`). It takes no lock of its own: `preflight.sh` also wants Docker and
     a green CI, which a measure does not need;
   - a branch that already exists;
   - a change that touches a path of `protected-paths.md` (`protected` or `build-config`) or `CLAUDE.md`: reverting it
     would change the evaluator;
   - a change with no code to replay or to plant in;
   - a change that no longer applies to `main`. A newer commit is needed then: `253e5b2a` no longer applies,
     `76379e80` (PR #472) does.
4. **The workflow does not take the seeder's word for the tree** (review round 1).
   - Before the panel runs, `scripts/factory/seed-check.sh <pattern> <run id> <base> <head>` confirms all of these:
     - the tree is on the seed branch;
     - it is clean;
     - exactly one commit sits on top of `seed-branch.sh`'s head;
     - that commit touches only the change's files.
   - The workflow then checks that the ground truth's file is among the files the commit touched. Anything else fails
     the run.
   - The seeder keeps `git switch` among its allowed commands: its instructions forbid the switch and this check
     catches one after the fact (the run ends FAILED with `leftOn`). A guard that refuses it before the call is #0-172's.
   - The files the seeder may touch leave out the ones the change deleted, as in `seed-branch.sh`'s `files`.
   - A failed run that got as far as the branch says how to delete it (`cleanup`), as a measured one does.
5. **Every outcome ends on `main`, or says where the tree was left.**
   - `return-to-main.sh` never discards work. When it cannot switch back (a dirty tree), the outcome carries `leftOn`
     and the reason.
   - A measured run goes back to `main` before the Record phase writes its row. The row in
     `.ai/audit/benchmark/results.md` therefore dirties `main`, never a seed branch, so it can never ride onto the next
     seed commit and reach the reviewers' diff.
   - The owner commits the row before the next run. `seed-branch.sh` names that file when it refuses.

## Alternatives considered

- **F1: plant on `main`, in a file the change touched.** Simple, but the panel reviews the defect alone in a small diff.
  That is easier than the benchmark means, and the results would not compare with an autopilot review.
- **A worktree per run (`git worktree add`).** It isolates the tree, but the workflow's agents run in the session's own
  directory and read its hooks from there. A worktree would need the session started inside it, which is the owner's
  step, not the workflow's.
- **Let the clean-tree check ignore `results.md`.** That would allow several runs without a commit in between. But an
  uncommitted file follows `git switch -c` onto the new branch, and a seeder's `git commit -a`, or the next row, would
  put it in the seed commit: the results of earlier runs inside the diff under review.
- **Keep the results under the gitignored `.ai/runs/`.** That would need a change to `audit-synthesis`'s write scope
  and to the path the audit reads, and a manual copy, for no gain over a commit.

## Consequences

- A measure needs a recent commit: one whose code still applies to `main`. The owner picks it (#0-173's AC3 uses
  `76379e80`).
- Three runs of one pattern take three small commits of `results.md` by the owner, one between each pair of runs.
- Two factory scripts more (`seed-branch.sh`, `seed-check.sh`). They are tested in Factory guards
  (`test-seed-branch.sh`), and `factory-ops` runs them only while they are identical to `origin/main`, so they work
  from their merge on.
- The seeder still writes on the shared tree without a write scope (#0-172): `seed-check.sh` catches the result, not the
  attempt.
