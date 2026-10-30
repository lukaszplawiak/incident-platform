# ADR-0044: A seed branch starts from main: the merged change is reverted and brought back on top of it, and scripts check the tree before and after the seeder (backlog #0-173)

- **Status:** Accepted (2026-10-25); decision 5 amended and decision 6 added by #0-177 (2026-10-26); the seeder commits
  nothing since #0-172 (ADR-0047, 2026-10-30): where this ADR folds "the seeder's commit", the fold now takes its edit
- **Backlog:** #0-173, #0-177
- **Date:** 2026-10-25
- **Author:** human (owner chose F2 of the research, then the proposals of review round 1; for #0-177, 1a with a script,
  A, A+ and B2 of its research; written up by Claude Code in the owner's session)
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
     a branch from `main` (`seed/<pattern>-<run id>` until #0-177, now `review/<8 hex>`: decision 6) with two commits:
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
   - A measured run goes back to `main` before the Record phase writes its row.
   - **Amended by #0-177 (2026-10-26).** The row went to the tracked `.ai/audit/benchmark/results.md`, and the owner
     committed it before the next run. But `main` takes changes only through a pull request, and `seed-branch.sh`
     starts only on a `main` equal to `origin/main`: a series meant a merge between every two runs (2026-10-25: the
     P-10 row was parked in a scratchpad while P-11 ran). Now `scripts/factory/seed-record.sh` (run by `factory-ops`, no
     agent writes the row) appends it to the gitignored `.ai/runs/benchmark/results.md`. An ignored file is not in
     `git status`, so `main` stays clean, and neither `git commit -a` nor `git add -A` takes it onto a seed branch, which
     answers this ADR's reason against "let the clean-tree check ignore `results.md`". After a series its rows reach
     `.ai/audit/benchmark/results.md`, which the audit reads, in one pull request. A run id already in either file is
     refused, and the run counts as recorded only on the script's `appended: true` (#0-171's "Also").
6. **Nothing the panel reads names the run (#0-177, 2026-10-26).** The measure assumes the reviewers judge the branch as
   any change: a seeded defect estimates the panel's catch rate only if it is as hard to find as a real one.
   - **What told them.** The panel's prompt named the branch (`seed/P-10-perf-after-p10`: a test, and its pattern);
     the base, the `mergeBase` they were given, said `seed base: … (/seed-bugs, not for review)`, one `git log` away;
     and the defect was a commit of its own on top of the change, so `git show HEAD` was the planted lines alone.
   - **Now.** The workflow derives the branch name from the pattern and the run id (FNV-1a, `review/<8 hex>`: the same
     on a relaunch, not readable back) and passes it to the scripts, which accept nothing else; the base says
     `Revert "<subject>"`, git's own subject without the sha of the commit on `main`; `seed-fold.sh` folds the
     seeder's commit, once `seed-check.sh` has confirmed it, into the change's: one commit on the base, the seeded
     tree, the change's subject, as a round-1 autopilot branch's commits look. The tree does not change, so neither
     does the ground truth's line.
   - **The reflogs too (review round 1).** git logs every commit and ref move, so after the fold `git log -g` and
     `git show HEAD@{1}`, both open to the reviewers, still showed the seeder's commit alone. `seed-fold.sh` drops the
     branch's reflog (the branch is the run's) and, of HEAD's, only the entries above the run's switch onto the branch,
     so the rest of the owner's reflog stays; a HEAD reflog without that switch, or still naming the seeder's commit
     after it (a seeder that switched away and back), is refused. The commits stay as unreachable objects until git's
     garbage collection, named by no ref or log a reviewer can list.
   - **What stays readable, on purpose (B2).** The catalogue (`.ai/audit/benchmark/patterns.md`, the "Seeded
     defects" section of `.ai/rules/audit.md`, the rows) is in reach of the reviewers' Read, Grep and `git grep`/`git
     show`. Nothing points them at it now, and nothing did then either: the transcripts of the 18 reviewers of P-10,
     P-11 and P-12 (6 dimensions × 3 runs, 2026-10-25, under `~/.claude/projects/<project>/<session>/subagents/
     workflows/wf_*/`) hold no tool call on `.ai/audit/`, `patterns.md`, `audit.md` or anything named `seed`, and no
     text about seeding, planting or a benchmark; their calls were the diff, the rule files from `origin/main`, the
     changed files and `git grep` on the code. Those three results stand. Residual: a reviewer that suspects a test
     can still read the answer key; checked by searching a series' reviewer transcripts for such a read before its
     rows land (a check by hand: the transcripts' format is Claude Code's own, not a contract a script could rest on).
   - **Other residuals (review round 1).**
     - The panel's prompt is not an autopilot item's: no item, title or criteria, "the test gate was not run", and a
       branch outside the `fix/`/`feat/` names. `git log` shows the change's subject, then its `Revert`, then `main`,
       and the change itself is in `main`'s history. None of it names a test, but a reviewer that looks can find the
       review unusual; giving the prompt an autopilot item's shape would mean inventing one.
       More than unusual (#0-185, 2026-10-28): the seeded tree differs from `main` in the defect alone, and the
       `Revert` holds the original lines. In #0-115's `factory.md` measure, every reviewer read one of the two.
       **Checked and decided (#0-185, owner, 2026-10-29).** The scan:
       - What was read: the Bash calls of the 122 reviewers of the 20 `/seed-bugs` runs before F1 (P-10…P-12 and
         the security, architecture, general, migration, docs and k8s series), each a `git diff/show/log/cat-file/
         rev-list/blame` that names `main`, `origin/main`, `HEAD~`/`^`, the base's parent, the base without
         `--stat`, or another commit.
       - 116 read only `<base>...HEAD` and the rule files from `origin/main`.
       - 4 read a commit's `--stat` or message: file names and a subject that the diff shows anyway.
       - 2 reached more, and neither was the expected dimension's:
         - general in `arch-after-p07` read the host's patch of one file (`git show aace4118 -- …`);
         - architecture in `mig-after-p13` listed the files that differ from `main` (`git diff origin/main HEAD
           --stat`), which names the planted file but not its lines.
       - F1 (`/review 27` by hand, the plant uncommitted, an Owner-only diff) went the other way: 4 of 4. Most
         likely because of its path: a manual review of an Owner-only change compares it with `main`.
       Decided:
       - Each series' transcripts are scanned this way by hand before its rows land (no script: as above, their format
         is not a contract). A row whose expected reviewer reached the original is marked in its "Caught by expected"
         cell, and one whose other reviewer did in its "Also caught by" cell, as #0-179 marks a relayed command.
       - `.ai/rules/audit.md` reads a marked row as an upper bound, not a hard fact.
       - A measure by hand commits the plant into the change (as `seed-fold.sh` does) and gives the panel the
         autopilot prompt (`Branch … mergeBase: <the revert>. round: 1.`), not a manual `/review` of uncommitted
         changes. The prompt also says that `factory.md` applies (the diff touches Owner-only paths): an autopilot
         item never does, so a reviewer must not read the prompt's shape as a reason to leave it out.
       - The way to close it: the panel runs in a clone with no history of the change and `origin/main` set to the
         revert, as SWE-bench removed the future commits from its images. It is not built: the Workflow's agents, their
         hooks and settings run in the project directory, so it means changes to `seed-bugs.js` and the hooks. It is
         built once a scan finds an expected reviewer that reached the original.
       - Rejected:
         - refusing those revisions in `readonly-bash.sh`: it leaks (`cat-file`, `rev-list`, the reflog), as #0-182's
           guard did, and the procedure itself reads `origin/main`;
         - planting only into an unmerged change: it cannot be replayed, and the host is not yet reviewed.
     - `.ai/runs/benchmark/results.md` is in reach of the reviewers during a series, and says a measure is running.
     - **The message that started the run (found 2026-10-26, in the `security.md` series of #0-115).** The Workflow
       tool starts every agent's first message with `[Workflow harness — user request]`, which holds the user's
       message that started the run, word for word. Started by the owner's `/seed-bugs P-01 47b95294 sec-after-p01`,
       each reviewer read the command, the pattern and the run id. This happened in P-01, P-03, P-04 and P-05. P-02,
       started by the session's own turn, had no such block, and P-10..P-12 relayed "tak" and "rób". No tool call or
       text of a reviewer acted on it, but their thinking is not in the transcripts. `seed-bugs.js` cannot remove the
       block. #0-179 closes this. The four rows of `.ai/audit/benchmark/results.md` are marked in their "Caught by
       expected" cell (`yes (told the pattern, #0-179)`), which `audit-data.sh` passes on to the audit. Until #0-179
       is done, start a run with a message that does not name it.
     - **The rule files (found 2026-10-26, #0-115).** A reviewer reads its dimension's rule file on every run, so
       that file must not mention the benchmark. `architecture.md`'s header said "a seeded result" from PR #536 on,
       through the P-06…P-09 runs. Their transcripts show no reviewer acting on it, and it is reworded. `factory.md`
       names the seeder, but it is read only for a diff that touches an Owner-only path, which a seed branch never
       does (`seed-branch.sh` refuses one). Amended 2026-10-29 (#0-184, ADR-0046): #0-115's F1 measured `factory.md`'s
       reviewers by hand, on Owner-only hosts, so the reason is now that such a measure is rare and run by hand.
       Factory guards' `check-rule-claims.rb` fails a measure word in every other rule file.
     - That a benchmark exists, and which patterns were measured before, is readable on `main` and in the clone:
       `main`'s history carries the commits that record the measures (#0-115's "seeded-defect measure"), and the
       owner's HEAD reflog keeps the runs before #0-177 (`checkout: moving from main to seed/P-10-…`, `seed base: …`)
       until git expires it (90 days by default; review round 2). None of it names the branch under review or its
       pattern. The old entries can be dropped by hand before a series; `seed-fold.sh` drops only its own run's.
     - `seed-fold.sh` drops the HEAD reflog entries above the run's switch onto its branch, whoever wrote them: a HEAD
       move by another session in the same tree during a run would go too. The shared tree is the run's while it runs
       (decision 1, #0-173), so none should be there (review round 3).
     - The seeder still has no write scope (#0-172) and `seed-check.sh` cannot see a gitignored file, so it could
       write rows into `.ai/runs/benchmark/results.md`, or its own run id, which makes the real row refused
       (`recorded: false`). The owner reads the rows before landing them; #0-172's write scope closes it.

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
  and to the path the audit reads, and a manual copy, for no gain over a commit. **Reversed by #0-177:** the gain is
  the series (no merge between runs, which the ruleset of `main` requires for a commit); a script writes the row, so no
  write scope changes; the audit still reads the tracked file; the manual copy is one per series.
- **#0-177: carry a dirty `results.md` aside in `seed-branch.sh`** (a stash, or a copy). More steps that can stop half
  way, and a stash is a leak: `git show stash@{0}` and `git log --all` are open to the reviewers.
- **#0-177: refuse `.ai/audit/` to the `review-*` agents (B1).** A read hook and new rules in `readonly-bash.sh`. The
  P-10..P-12 transcripts show reviewers searching `git grep … -- .ai README.md CLAUDE.md` and `-- . ':!*.sql'` as
  part of a review, which reaches the catalogue: closing it means refusing such searches, or pathspec rules for every
  git command that names a path or `<rev>:<path>`. Much hook surface for a read nothing prompts.

## Consequences

- A measure needs a recent commit: one whose code still applies to `main`. The owner picks it (#0-173's AC3 uses
  `76379e80`).
- A series takes one pull request for its rows, after its last run (#0-177; before, a commit between each pair of runs).
- Four factory scripts more (`seed-branch.sh`, `seed-check.sh`; `seed-fold.sh`, `seed-record.sh` from #0-177). They are tested in Factory guards
  (`test-seed-branch.sh`), and `factory-ops` runs them only while they are identical to `origin/main`, so they work
  from their merge on.
- The seeder still writes on the shared tree without a write scope (#0-172): `seed-check.sh` catches the result, not the
  attempt.
