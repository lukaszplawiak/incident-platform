# ADR-0042: The owner's review trusts nothing of the branch it reviews: `/review` and `/accept` pre-approve exact commands, and the reviewers read their rules from origin/main (backlog #0-165)

- **Status:** Accepted (2026-10-25)
- **Backlog:** #0-165
- **Date:** 2026-10-25
- **Author:** human (owner chose 1C + 2A of the research and a written session rule, then moved the hook of 1C to
  #0-167; written up by Claude Code in the owner's session)
- **Reversible:** yes

## Context

`/review` is meant for branches the owner did not write too, and the model that runs it reads the branch's diff,
text its author controls. Two ways that text reached further than reading:

1. The skills pre-approved git wildcards (`/review` and `/accept`: `git diff *`), and `git diff`, `git show` and
   `git log` take `--output=<file>`, which writes anywhere (checked with git 2.54, #0-161), and
   `--ext-diff`/`--textconv`, which run programs git's configuration names. The owner's main session has no Bash
   hook: a steered `git diff` with `--output` would have written a file the owner's shell runs, without a prompt.
   The reviewers' `readonly-bash.sh` already refused those options, for the agents only.
2. The reviewers read `.ai/rules/review/*.md` with the Read tool, from the working tree, so a branch could rewrite
   the rules it is judged by. Only the Owner-only list (the trigger of `factory.md`) was read from `origin/main`.

And the agent definitions and skills the review runs come from the working tree too. Claude Code's docs say it
watches `.claude/skills/` and `.claude/agents/` and picks up a change within the running session (skills: "Edit a
skill during a session"; subagents: "Write subagent files"); they do not say how a `git switch` is treated, but
nothing promises that a session started on `main` keeps main's definitions after it.

## Decision

1. **`/review` and `/accept` pre-approve exact commands only**, no wildcard; `/review` fetches `origin/main` first.
   An exact command cannot carry an option, so none of them writes a file or runs a program the branch can name
   (`git diff` and `git status` still run what the owner's own git configuration names); anything else asks the
   owner.
2. **Reviewers read `.ai/rules/review/` from main** (`git show refs/remotes/origin/main:.ai/rules/review/<file>`,
   `review-procedure` and `_common.md`): a change to a rule file is judged by the rules it changes. The full ref
   name, here and for `/review`'s Owner-only list: git resolves the short `origin/main` to a local branch or tag of
   that name first, with only a warning (checked with git 2.54, 2026-10-25), so a branch checked out as
   `origin/main` would set the rules again; `_lib.sh` reads the base the same way. A reviewer that cannot read a
   rule file main should have answers `NEEDS_HUMAN`, not `APPROVE` (it has no rule to cite).
3. **A branch someone else wrote: the owner reads its changes to what steers the session before `/review`**
   (`.claude/`, `CLAUDE.md`, `.ai/context/`: `git diff --stat refs/remotes/origin/main...<branch> -- …`;
   `/review`, Step 2). Its skills, agents and `CLAUDE.md` would be the ones that review it, and no start on `main`
   keeps them out (Context).

The session-level hook that refuses those git options in every session (the research's 1C) is moved to #0-167:
written here, its first version could be passed by quoting, line continuations and words built at run time
(round 1 of this review), and a parser that reads a command as bash does is an item of its own.

## Alternatives considered

- **The hook in this change.** Its first version was not a boundary (above); the exact commands close this skill's
  exposure without it, and #0-167 covers the skills that keep wildcards (`/research`, `/ship`).
- **Start the review session on `main`, then switch to the branch.** Written as the rule first; the docs' file
  watching makes it no protection (Context).
- **Refuse `/review` on a branch that changes `.ai/rules/` or `.claude/` unless the owner confirms.** Nearly every
  factory change of the owner's touches them: a confirmation on every review.
- **A Read hook keeping reviewers off `.ai/rules/review/`.** Would enforce decision 2; left as instruction for now
  (the README names it), since a review whose rules were rewritten also needs the owner's code-owner approval of
  `.ai/rules/` to merge.

## Consequences

- A new command `/review` or `/accept` needs is added to `allowed-tools` exactly, or it prompts.
- Not covered: `/research` (`git log/show/diff *`) and `/ship` (`git diff *`) keep their wildcards until #0-167;
  a reviewer that ignores the instruction and reads a rule file from the branch (seen in this change's own round 2:
  one reviewer read `_common.md` with the Read tool); `scripts/factory/local-review.sh`, the advisory second
  opinion, which still reads `_common.md` and `general.md` from the working tree; a branch's session-steering
  changes the owner does not read before `/review`.
