# ADR-0041: An agent's guard runs in two layers, its frontmatter and a session-level twin keyed on its agent_type, and Factory guards keeps them equal (backlog #0-164)

- **Status:** Accepted (2026-10-25)
- **Backlog:** #0-164
- **Date:** 2026-10-25
- **Author:** human (owner chose A1 + B1 + C1 of the research, a spike first, a new ADR; written up by Claude Code in the owner's session)
- **Reversible:** yes

## Context

The agents that may only read, or write under some paths, are limited by hooks in their own frontmatter:
`readonly-bash.sh` (the reviewers, acceptance-reviewer, ready-checker, planner, the architect, the audit analysts),
`write-scope.sh` (architect, planner, audit-synthesis), `factory-ops-bash.sh` (factory-ops) and `read-scope.sh`
(the verifier). Claude Code documents frontmatter hooks for agents of the Agent tool, an @-mention or `--agent`, in
a trusted workspace, never in `claude -p`; the autopilot spawns its agents through the `Workflow` runtime, for
which nothing is promised. They ran there in #0-160's spike, but a release that stops running them would give a
reviewer the session's Bash and every scoped agent the session's writes, and nothing would say so. #0-160
(ADR-0037) already ran the verifier's read scope again at session level, keyed on `agent_type`.

Spikes on Claude Code 2.1.310 (2026-10-25):

| Spike | What | Result |
|---|---|---|
| 1 | `claude -p --settings extra.json` in a project with its own `.claude/settings.json` | both files' PreToolUse hooks ran: `--settings` adds to the project's settings, it does not replace them |
| 2a | an agent of the Agent tool (`--agents`), in `-p` | both files' hooks got `agent_type: spike-runner` |
| 2b | a `Workflow` agent with `agentType: 'review-docs'`, and one without, in this interactive session | `agent_type: review-docs`; without agentType `workflow-subagent`; the main session none |
| 3 | the same hook command in `.claude/settings.json` and in the `--settings` file | it ran once: identical commands are deduplicated (review round 1) |

A twin keyed on `agent_type` fails open in one case: a Claude Code that stops sending `agent_type` makes every call
look like the main session's, which a guard must let through. The frontmatter hook needs no `agent_type`.

## Decision

1. **Both layers.** Every guard in an agent's frontmatter stays, and runs again at session level as
   `<hook> --agent <names> <the same arguments>`, on the same matcher, in `.claude/settings.json` and in
   `.claude/settings.autopilot.json`, with `onFailure: "block"` (ADR-0039). Each layer covers the other's failure:
   the frontmatter one the loss of `agent_type`, the twin the loss of frontmatter hooks for a Workflow's agents.
2. **One twin per guard and arguments**, its `--agent` a comma-separated list of exactly the agents whose
   frontmatter runs that hook with those arguments (nine reviewers and checkers share the plain `readonly-bash.sh`; ten since #0-182, ADR-0045, added `review-k8s`).
   So every hook an agent's frontmatter uses as a guard must accept `--agent` (today `readonly-bash.sh`,
   `write-scope.sh`, `factory-ops-bash.sh`, `read-scope.sh`); a new one gets it before it guards an agent.
   `applies_to_agent` in `.claude/hooks/_lib.sh` decides it for every hook: another agent and the main session pass;
   a list that is not names, and an `agent_type` that does not parse, refuse.
3. **The frontmatter is the one list (FAC-A01); the settings files are checked against it.**
   `check-hook-entries.rb` (Factory guards) fails a guard without its twin in either file, a twin whose names are
   not exactly its agents, a twin no frontmatter has (stale), and a frontmatter hook with `--agent`.
4. **The session-level guards that must be wired** are a table in `check-hook-entries.rb` (`REQUIRED`: the
   autopilot's `guard-tests`, `guard-protected-bash`, `guard-git-push`, `guard-factory-scripts`,
   `guard-write-in-repo`, `guard-migrations`; the owner's `guard-migrations`): a deleted entry, one with other
   arguments, or one moved to a matcher that does not cover its tools, fails (#0-164 AC4). `webfetch-allowlist.sh`
   is not in it: `check-web-access.sh` (ADR-0037) already holds its wiring, in both files and in its modes, and a
   second list of it would have to change with the first (FAC-A01, review round 1).

## Alternatives considered

- **Only the frontmatter (today).** Rests on behaviour Claude Code does not document for a Workflow's agents.
- **Only the session level (move the guards out of the frontmatter).** One place, but a Claude Code that stops
  sending `agent_type` would leave no guard at all.
- **A dispatcher hook that reads the agent's frontmatter at run time.** No copy, but a YAML parser in a bash hook
  (the devcontainer has no Ruby), and `agent_type` turned into a file path; every parser bug a security risk.
- **A generator writing the twins into the settings files.** No hand edits, but a tool editing settings files that
  carry long `$comment`s, for about eight entries; the check catches every drift a generator would prevent.
- **Twins in `settings.json` only (spike 1 shows it runs in the autopilot too).** The owner kept both files (AC1): it
  costs nothing at run time, since an identical command in both is run once (spike 3), and it holds if a later
  Claude Code stops merging `--settings` with the project's settings.

## Consequences

- A new agent with a frontmatter guard, or a new guard, needs its twin in both settings files, or Factory guards
  fails and names the line to add. A twin changes with its frontmatter.
- Every Bash call of any agent runs five more hooks and every write three more (once each: the two files' identical
  commands are deduplicated, spike 3), each ending at `agent_type` for an agent it does not guard.
- The `factory-ops-bash.sh` twin was added too: the backlog item named `readonly-bash.sh` and `write-scope.sh`, but
  rule 3 covers every frontmatter guard, and factory-ops' was frontmatter-only as well.
- Not covered: an agent that should have a guard and has none in its frontmatter (a new agent with Bash and no
  `readonly-bash.sh`) is the reviewer's to catch; and a Claude Code that drops both frontmatter hooks and
  `agent_type` at once would leave the session-wide guards only.
