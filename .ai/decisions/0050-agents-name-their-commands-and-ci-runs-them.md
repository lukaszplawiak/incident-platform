# ADR-0050: An autopilot agent with Bash names its command forms, and Factory guards runs each one

- **Status:** Proposed
- **Backlog:** #0-207 (AC4; the live check is #0-214)
- **Date:** 2026-11-08
- **Author:** human (owner's decision on the options of #0-207, drafted in an interactive session; asked for by review-architecture, ARC-07)
- **Reversible:** yes — one Factory guards step and the marked lists; removing them brings back the check by hand

## Context

An autopilot session runs in `dontAsk` (`.claude/settings.autopilot.json` on top of `.claude/settings.json`): a Bash
command that matches no allow rule, or that a hook refuses, is refused, costs the agent a turn and is not retried.
Agents composed their own forms (a pipe to cut output, `cat`, `sed -i`, `/workspace/…`, `git -C`, chains): two
pickers lost six and seven calls on 2026-11-04, one of them its item; the implementer of run 20261106-214534 lost
ten and its shipper four. `picker.md` then named its forms (#0-207 AC1), and the first form it named, `cp -r`, was
refused although `Bash(cp -r .ai/work/_template *)` matched it as text: Claude Code refuses some families whatever a
rule says (#0-207's third case). The prose of a definition and the allow-list can drift apart with nothing to show it.
The precedent for a check that keeps the agents' definitions and the settings consistent is `check-hook-entries.rb`
(ADR-0039, ADR-0041), and for a named exception list with a reason per entry its `UNGUARDED` (#0-186).

## Decision

1. Every agent whose `tools:` holds Bash (or that names no tools) names every command form its definition prescribes
   in a marked list (`<!-- commands:start -->` … `<!-- commands:end -->`, one ``- `<command>` `` per line, example
   values in place of placeholders). Today: `picker.md`, `implementer.md`, `shipper.md`, `defect-seeder.md`.
2. Or it follows another's list (`ALSO_FOR`: `implementer-light` follows `implementer`), or it is in `EXEMPT` with its
   reason: the agents whose own guard already fixes the forms they may run (`readonly-bash.sh` in its modes, tested
   by `test-hooks.sh`; `factory-ops-bash.sh`). A stale exemption (no such agent with Bash, or one with a list) fails.
3. `.github/scripts/check-agent-commands.rb` (Factory guards, tested by `test-agent-commands.sh`) runs every listed
   form, for each agent it is for, through: the families Claude Code or the hooks refuse whatever a rule says (a
   recursive `cp`, an absolute or `~` path, a separator, pipe, redirect or substitution outside quotes, an unquoted
   glob, `..`, `git -C`, `cd`); the allow and deny rules of both settings files, merged as the session merges them;
   and every PreToolUse Bash hook of both settings files plus that agent's own frontmatter, in a throwaway repository
   with a run lock.
4. Extended by ADR-0054 (#0-235): the same check holds every `Bash(...)` allow rule, and a preloaded skill's
   `allowed-tools`, to a list of safe prefixes, so no rule lets an agent remove a path it does not name.
5. The families are a model learnt from refusals, not Claude Code's own check. Running the lists in a real `dontAsk`
   session is the owner's tool, after a Claude Code update (#0-214), not a CI step: CI holds no Claude credentials.

## Alternatives considered

- **Prose only, checked by hand** (#0-207 AC1 as first done): it held until the third case, where a form shown by
  hand against the rules' text was refused at run time.
- **A shared skill with the rules, preloaded by every agent** (owner, 2026-11-08): fewer repeated sentences, but each
  definition still needs its own list for the check, and a definition stops being complete on its own.
- **A fixed `REQUIRED` list of agents** (the first draft): a second, unchecked list of who runs Bash; a new agent
  with Bash and no list would pass in silence (review-architecture, FAC-A01).
- **A live `claude -p` run in CI**: the real check, but it needs Claude credentials in CI and costs tokens per PR.
- **One frontmatter parser shared with `check-hook-entries.rb`**: the parse is five lines; a shared library for two
  checks is more than this change. Kept as two, knowingly.

## Consequences

- A definition that adds a command form adds it to its list in the same change; a form no rule allows needs an
  owner's change to `settings.autopilot.json` (FAC-S03 judges it). A form denied for now (the shipper's
  `gh pr merge … --squash --auto`, until phase 3) is named in the definition as the exception.
- A new agent with Bash needs a list or an `EXEMPT` entry with its reason before Factory guards passes.
- Left open: what Claude Code itself refuses beyond the modelled families (#0-214); whether a definition's prose asks
  for a form its list leaves out (a reviewer's to judge).
- A reviewer recognises a violation as a command form in a definition's text with no line in its list, an `EXEMPT`
  entry without a reason, or a refused family in a list.
- **Amended (backlog #0-241, 2026-11-14): the check's tests run only when their inputs change.** The check itself
  runs on every Factory guards event; its tests (`test-agent-commands.sh`, 265 of the job's ~7 minutes) run through
  `run-agent-command-tests.sh`, which skips them on a pull request whose diff touches none of their inputs, and runs
  them on a push, outside GitHub Actions and whenever it cannot read the event or the diff. The decision is in that
  script, not an `if:` or a `${{ }}` in the step: `ci-local.sh` skips such a step, and the owner's local run would
  lose the tests. That is also why not `dorny/paths-filter` (already pinned for ci.yml's `detect-changes`, ADR-0020):
  its answer reaches a step only through such an `if:` or `${{ }}`, while `git diff` of the event's two commits needs
  neither. Its input list (`INPUTS`) is kept by hand: a new input of the check or its tests goes on it in the
  same change, which a reviewer checks. Accepted risk: nothing verifies the list is complete; a change that adds an
  input almost always edits the check or its tests, which are inputs themselves, and the push to `main` runs the
  tests whatever changed, so a miss shows within one merge. If a second Factory guards step needs the same, the
  selector becomes one shared helper, not a copy.
