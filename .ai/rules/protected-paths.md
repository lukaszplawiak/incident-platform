# Protected paths

Owned by the maintainer; agents never edit it. The paths no agent of the AI factory writes: the rules and
configuration the agents are judged and limited by, the factory's own gates, and the build files every
verification runs. A change to any of them is the owner's, so an item that must write one is not autopilot
work (`ready.md` point 3): it is `human-only`, or split so the owner does that part first.

**This file is the one list.** Who uses it:

- **Code** reads the lists between the markers below, from the base commit: `scripts/factory/changed-paths.sh`
  (a diff that touches them needs the owner), `next-item.sh` / `check-queue.sh` (an item whose
  `**Touches:**` names one never starts and cannot be queued) and `.github/scripts/check-factory-guards.sh`
  rule 6 (a PR changing the build configuration needs the owner; from the merge base).
- **Agents** read this file: `ready-checker` and `/ready` (the item is not autopilot work), the planner
  (not queued), the architect (stop before the implementer), the implementer (out of scope, stop), every
  reviewer (`NEEDS_HUMAN`, `.ai/rules/review/_common.md`).
- **Three copies stay**, because the last line of defence must not depend on parsing this file: the
  Edit/Write deny rules of `.claude/settings.autopilot.json`, the shell-write check of
  `.claude/hooks/guard-protected-bash.sh`, and `.github/CODEOWNERS` (the owner's review on a PR, for the
  owner-only list; the build-config list is guarded on PRs by rule 6 above, which reads this file).
  `.github/scripts/check-protected-paths.sh` fails CI when one of them misses an entry here — for
  CODEOWNERS, when the line GitHub applies (the last match) names no owner.

Fixed (backlog #0-122, found by #0-42's run, draft PR #477): the list existed in about ten places that had
drifted apart (the hook did not cover `.ai/audit/decisions.md`; a copy treated all of `CLAUDE.md` as
protected), and `/ready` marked an item `ready` whose criteria needed `.github/`, which only the
implementer then refused.

Format: one entry per line between the markers, `` - `<path>` — <why> ``. A path ending in `/` is a
directory and everything under it; any other path is one file.

## Owner-only

<!-- protected:start -->
- `.ai/rules/` — the rules every agent is judged by
- `.ai/plan/` — the execution queue the owner approves
- `.ai/audit/decisions.md` — the owner's decisions on audit recommendations
- `.claude/` — agent definitions, skills, workflows, hooks and the autopilot's permissions
- `.github/` — CI, the checks that guard `main`, CODEOWNERS
- `architecture-tests/` — the ArchUnit rules and their frozen store
- `AGENTS.md` — the instructions every assistant reads first
- `scripts/factory/` — the autopilot's deterministic gates
- `scripts/factory-admin/` — the owner's tools (breaker and audit counter reset)
- `.devcontainer/` — the sandbox and its firewall
<!-- protected:end -->

## Build configuration

What every verification runs; a change could make a failing build pass.

<!-- build-config:start -->
- `.mvn/` — the Maven wrapper's configuration
- `mvnw` — the Maven wrapper
- `mvnw.cmd` — the Maven wrapper (Windows)
<!-- build-config:end -->

## Partly protected

- `CLAUDE.md` — only its `agent-editable` blocks are the implementer's (today: "Commands"). Not in the
  lists above, because an item may legitimately change a block. `changed-paths.sh` compares the text
  outside the blocks; an item that must change that text is not autopilot work, and the architect stops it.
