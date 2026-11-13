# ADR-0054: No allow rule of an autopilot session runs a program that can remove a path it does not name

- **Status:** Proposed
- **Backlog:** #0-235
- **Date:** 2026-11-13
- **Author:** human (owner's decision on the options of `/research 0-235`, drafted in an interactive session)
- **Reversible:** yes — rule 4 of `.github/scripts/check-agent-commands.rb`, `git_runtime_unsafe` in
  `.claude/hooks/_lib.sh`, and their tests; #0-236 changes where the state lives, not this rule

## Context

The runtime state the factory trusts is gitignored: `.ai/runs/LOCK` (the run's base commit and branch, read by
`base_ref()` in `_lib.sh` and by `guard-git-push.sh`), `.ai/runs/state.json` (the breaker's counts),
`.ai/runs/ops-calls/` (one call per factory-ops run, ADR-0053) and `.ai/STOP`. No diff shows its removal, so
`changed-paths.sh` cannot catch it. Without the lock, `base_ref()` falls back to `refs/remotes/origin/main`, a ref,
and the factory-ops marks and the breaker's count reset.

`guard-protected-bash.sh` refuses a shell write that names one of these paths, and since #0-233 the marks too. It
matches text, so `rm -rf .ai/runs`, a glob, `find .ai/runs -delete` or `cd .ai/runs && rm …` pass it. What keeps
them out today is the allow list of `settings.autopilot.json` in `dontAsk`: no rule runs `rm`, `find`, `mv`, `cd`, an
interpreter or `git clean`. Nothing checked that this stays so. `check-agent-commands.rb` (ADR-0050) asked whether
the agents' commands are allowed, not what the allow rules allow.

Claude Code's documentation (code.claude.com/docs/en/permissions, 2026-11-13) calls Bash rules that constrain
arguments fragile, notes that a wrapper rule (`Bash(devbox run *)`) also allows the command it wraps, and points to
the sandbox as the stronger layer.

## Decision

Rule 4 of `check-agent-commands.rb`: every `Bash(...)` allow rule of both settings files, and of the
`allowed-tools` of every skill an autopilot agent preloads (`skills:`), starts with a word prefix of
`SAFE_PREFIXES`, or is in `EXEMPT_RULES` with its reason. Each prefix names a program that cannot remove or move a
path it does not name, with why, and is as narrow as the rules it was judged on (`gh pr create`, not `gh pr`;
`git fetch origin main`, not `git fetch`). A rule's words up to its first `*` are compared, so `Bash(git *)`,
`Bash(*)` and a bare `Bash` fail, and so does a rule whose text holds a shell operator (`;`, `&`, `|`, `<`, `>`, a
backtick, `$(`, a newline). `EXEMPT_RULES`: `./mvnw *` (#0-116), `scripts/factory/*` (factory-ops only, as on the
base, one call), and git's commands on the working tree (`git add`, `rm`, `mv`, `switch`, `checkout -b`, #0-236). An
exemption whose rule is no longer allowed fails. A skill an agent names that does not exist fails.

git's commands on the working tree reach the state, which lives in it, without naming it (review rounds 1 and 2,
sec-4a1d, each form checked in a scratch repository): `git add -f .ai/runs` then `git rm -r -f .ai/runs` deletes it;
an edited `.gitignore` makes a plain `git add .ai/runs` stage it; `git mv .//.ai x` moves it; `git mv -f f
.ai/runs//LOCK` overwrites the lock; `git switch` overwrites it from a commit that tracks it. They are therefore
exemptions, not safe prefixes. `git_runtime_unsafe` (`_lib.sh`, from `guard-protected-bash.sh`) refuses their plain
forms: `git add -f` in every spelling, a `git add`/`rm`/`mv` path that resolves (`//`, `.`, `..`, from the
repository's root: `git -C` and `--work-tree` match no allow rule and are forms Claude Code refuses) into the state
or, for `mv`, above it, an unresolvable path, a glob that can reach a dot directory, part by part in a compound command
and on the unquoted text of a part it cannot split. No form of `git switch` is refused. The shell guard's protected
match resolves `//` and `/./` first (review round 3: `git log --output=.ai/runs//LOCK` overwrote the lock). It is a heuristic: each review round found another form, and the
`.gitignore` route needs no option at all. The fix is the state out of the working tree (#0-236).

## Alternatives considered

- **The guard recognises parents, globs, `find -delete` and `cd`** (#0-235's first proposal): a deny list on text,
  with no end of forms (`.a?/runs`, `d=.ai; rm -rf $d/runs`, `rm -rf *` from the root, `perl -e unlink`). It would not
  bound `./mvnw`, the one path that reaches the state today.
- **One protected directory for the runtime state** (its second proposal): its parent (`.ai`) can still be removed,
  and it moves the lock under every hook, script and ADR that names it (0034, 0044, 0047, 0052, 0053).
- **A list of dangerous commands** instead of a list of safe prefixes: a program nobody judged (`rsync --delete`,
  `env rm`) would pass. The closed list fails it until someone does.
- **Detecting the removal** (the lock records the marks directory's inode): code run through `./mvnw` can rewrite
  the lock and the marks consistently, so it guards only against what this rule already keeps out.
- **Claude Code's sandbox** (`sandbox.filesystem.denyWrite`, which binds child processes, Maven's included): it is
  the boundary for `./mvnw`, so #0-116's, with what has to be measured first (Testcontainers needs the Docker socket,
  which bypasses it; bubblewrap inside the devcontainer needs the weaker nested mode).

## Consequences

- A new allow rule, or an `allowed-tools` entry of a preloaded skill, either starts with a judged prefix or is an
  exemption with its reason: widening the autopilot's Bash is an edit of this check, so the owner's (CODEOWNERS).
- `guard-protected-bash.sh` stays a heuristic second line; the allow list is the control, for every program but
  the exemptions.
- Left open: git's commands on the working tree reach the state, `git_runtime_unsafe` refusing their plain forms only,
  until the state leaves the working tree (#0-236); code run through `./mvnw` reaches every path (#0-116); the rule
  reads the rules' text, not Claude Code's own decision (#0-214); a skill the owner's session runs (`/ship`) is not
  read, it is no autopilot agent's.
- A reviewer recognises a violation as a new `SAFE_PREFIXES` entry whose program can remove, move or overwrite a path
  it is not given (one acting on the working tree is an exemption), or that is wider than the allow rules it covers
  (FAC-S03); or an `EXEMPT_RULES` entry without a concrete reason.
