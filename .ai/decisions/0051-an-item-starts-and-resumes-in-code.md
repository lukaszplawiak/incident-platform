# ADR-0051: An autopilot item starts and resumes in code; the picker only reads

- **Status:** Proposed
- **Backlog:** #0-215 (records #0-209's decision too, which had no ADR)
- **Date:** 2026-11-10
- **Author:** human (owner's decision on the options of #0-215, option B, drafted in an interactive session)
- **Reversible:** yes — the two scripts and the `--picker` mode; the picker's old commands would come back with their
  allow rules

## Context

The picker checks the item the workflow chose and names its branch. Until #0-209 it also created the branch, copied
the work template and set the item's status with commands of its own; each refusal in `dontAsk` cost a turn, and one
after `git switch -c` left the branch that locks the item (#0-206, #0-207). #0-209 moved the start into
`scripts/factory/start-item.sh`, run by the workflow through factory-ops. On resume the picker still ran
`git switch <branch>` itself, so it stayed in `check-hook-entries.rb`'s `UNGUARDED` and the autopilot's git write
rules (`git switch *`, which matches `-c`, `git commit *`, `git push -u origin *`, `gh pr create *`) held for it,
while it reads PR titles an outsider can write (`gh pr list`). A guard for that one command could check only the
branch's shape: the hook does not know `args.branch`, so another item's branch would pass.

## Decision

1. Every step of a run that has one right result and changes the tree is a factory script the workflow runs, never
   an agent's command: the start (`start-item.sh <base> <item> <branch> <run>`, #0-209) and the switch of a resumed
   run (`resume-item.sh <base> <branch>`, #0-215: a clean `main` at the base, a branch named `<type>/0-<n>-<slug>`
   that exists locally and holds `.ai/work/0-<n>/progress.md`; the item is the one the branch names). A refusal
   changes nothing, or undoes what it made, and the workflow stops the item at `pick`, recorded and notified.
2. The workflow checks a resume's arguments before anything moves: a `branch` that is not an item's, or an `item`
   that is not the branch's, is `NOT_STARTED`.
3. The picker writes nothing: its Bash is `readonly-bash.sh --picker` (read-only git, `gh pr list` in its one form
   matched whole, `ls .ai/work/0-<n>` that resolves to itself, through no symlink), in its frontmatter and as the `--agent picker --picker` twin of both settings files, and it is
   no longer in `UNGUARDED`. Its command list stays and `check-agent-commands.rb` runs it through that guard.

## Alternatives considered

- **A `--picker` mode that also allows `git switch <type>/0-<n>-<slug>`** (the item's first proposal): fewer changes,
  but the hook cannot bind the switch to `args.branch`, and the workflow checks only the branch the picker answers,
  not where HEAD is; a picker misled by a PR title could resume on another item's branch.
- **Leave the picker in `UNGUARDED`**: the residual #0-215 was opened for.

## Consequences

- An agent definition that needs a tree-changing step with one right result gets a factory script, not an allow rule.
- A reviewer recognises a violation by an agent command that creates, switches or commits a branch outside the
  implementer, the shipper and the architect's one commit form, or by a picker list line that `readonly-bash.sh
  --picker` refuses (Factory guards fails it).
- The row match of the picker's dependency check is `'^[[:punct:]] 0-<n> [[:punct:]] '`: its guard refuses a `|`
  even inside quotes.
- Known, left open: `start-item.sh` copies the template with `cp -R` without checking it for symlinks (#0-217), from
  the review of #0-209. The two scripts share their precondition, a clean `main` at the base (`clean_main_at`,
  `_common.sh`).
- The rule of an item's branch, once written four times with nothing comparing them (#0-216, from the reviews of
  #0-209 and #0-215), is `ITEM_BRANCH_RE` in `_common.sh` for both scripts and `ITEM_BRANCH` in the workflow, which
  cannot read a file; `test-autopilot-workflow.mjs` fails when the two differ in text or in what bash and JavaScript
  accept, as it does for `SEED_BRANCH_RE` (#0-177). The workflow asking a script instead was rejected: an agent call per
  run, and one more factory-ops answer to type (#0-169, #0-175), for what CI can check before the merge.
- The branch's prefix is code's too (#0-195): `BRANCH_PREFIX_OF` in `_backlog.sh` maps the item's `**Type:**` on the
  base commit to it, `next-item.sh` hands it to the workflow, which puts it in front of the picker's slug, and
  `start-item.sh` refuses another; a type with no prefix (design, a misspelt one) is not chosen. The picker names only
  the slug, the part that needs judgement. Checking the picker's own prefix and stopping on a wrong one was not taken:
  four runs got it wrong, and each stop would have cost a run.
