# ADR-0053: A factory-ops run gets one call, keyed on its agent_id

- **Status:** Proposed
- **Backlog:** #0-169
- **Date:** 2026-11-13
- **Author:** human (owner's decision on the options of `/research 0-169`, drafted in an interactive session)
- **Reversible:** yes — one function of `.claude/hooks/factory-ops-bash.sh` and its tests

## Context

The workflows (`backlog-autopilot.js`, `audit.js`, `seed-bugs.js`) have no shell. Each of their deterministic steps is
one `factory-ops` call, `Run exactly: scripts/factory/<script> '<arg>' …`, which the workflow builds from fixed text
and arguments matching `SAFE`. `factory-ops-bash.sh` let factory-ops run any intact factory script with plain
arguments, in each of its `maxTurns: 4` turns. What a script prints is in the agent's context before it answers, and
some of it is the branch's: `changed-paths.sh` lists file names, `run-tests.sh` returns the tests' failures and
`local-review.sh` a model's review of the diff. Steered by that text, one factory-ops task could run:
- `state.sh unlock`, `preflight.sh` and `state.sh branch <another>`. `guard-git-push.sh` lets the autopilot push the
  branch in the lock, so its next push would go to that branch, the owner's open PR included (#0-131);
- `state.sh record shipped …`, which resets the breaker's count of BLOCKED items.

A spike on 2026-11-13 (run `wf_913b1a7d-375` and one factory-ops of the Agent tool, a session-level hook logging its
input, Claude Code on the owner's host) showed:
1. `agent_id` is new for every `agent()` call of a Workflow and for every Agent-tool spawn.
2. `agent_id` is the same for every call of one run: two commands in a row, two Bash calls in one assistant message,
   and a retry after a refusal.
3. `transcript_path` inside a subagent is the main session's transcript, so a hook cannot read the agent's prompt
   from it.
4. factory-ops (Haiku) sends a refused form first (an absolute path, which the session twin refused, then the relative
   one), and retries after a successful call too (`; echo "EXIT_CODE=$?"`, `2>&1`).
5. `maxTurns` counts assistant messages: two Bash calls in one message are one turn, so it does not bound the calls.

The documentation (code.claude.com/docs/en/hooks.md, 2026-11-13): `agent_id` is "Unique identifier for the subagent
or in-process teammate the hook fires in"; a PreToolUse input carries `tool_use_id`; "When an event fires, its
matching hooks run in parallel"; a handler defined in more than one settings file runs once.

## Decision

`factory-ops-bash.sh` allows one call per factory-ops run: the first call that passes every other rule of the hook.
Every call after it, with the same `agent_id`, is refused, whatever its command.
- **The first call is the task's.** factory-ops makes it before it has read any script's output, from a prompt that
  the workflow's code builds. So "every call after the first is refused" stops every command that text from the
  branch could ask for. #0-169's AC1 says "a call other than the one its task named"; this meets it by refusing
  every call that could come from what the agent read, not by comparing the command with the prompt, which no hook
  input carries (fact 3).
- **A refused call does not use it up** (fact 4). The mark is written only for a call the other rules let through.
- **The mark is a symlink `.ai/runs/ops-calls/<agent_id>` → `<tool_use_id>`, made by `ln -sn --`.** The hook runs
  twice for each call, from the agent's frontmatter and as the session-level twin (#0-164, ADR-0041), possibly at once.
  Creating a symlink makes the mark and its content in one atomic step, so the hook does not read `ln`'s exit status:
  after `ln`, the mark must be a symlink whose target is this call's `tool_use_id`. The first copy creates it, the
  second copy of the same call finds its own `tool_use_id` there and passes, a second call finds another and is
  refused. Two calls of one run at once: one passes. `-n` keeps `ln` from following a mark that links to a directory,
  `--` keeps a `tool_use_id` that starts with `-` from being an option.
- **Fail closed.** No `agent_id` or `tool_use_id`, either one not `[A-Za-z0-9_-]`, or a mark that cannot be made or
  read refuses the call. So does anything at the mark's name that is not a symlink (review round 1): `ln -s` into an
  existing directory creates the link inside it and exits 0, so `ln`'s status is never trusted; after it, the mark
  must be a symlink to this call's `tool_use_id`.
- **Marks older than a day are pruned** by the hook. A run lasts at most `FACTORY_LOCK_HOURS` (3 by default), and an
  old mark refuses nothing a new `agent_id` asks.

## Alternatives considered

- **Compare the command with the prompt** (read from the transcript): fact 3. The agent's own transcript
  (`subagents/workflows/<run>/agent-<agent_id>.jsonl`) is not named in the hook input, it is written asynchronously,
  and its format is Claude Code's to change.
- **A one-time token in the command:** the hook would need to know which tokens the workflow issued, and the workflow
  has no file system and no secret the agent cannot read.
- **A lower `maxTurns`:** fact 5, and a limit of 2 still lets the second turn run a command before the agent fails.
- **Hardening `state.sh`** (`unlock` with the run's id, a `preflight` that refuses a second lock): the run id is in the
  prompt, so not a secret, and `record`, `notify.sh`, `return-to-main.sh` and the seed scripts stay open. Every new
  factory script would need its own rule.

## Consequences

- One factory-ops task is one command. A workflow that needs two commands makes two `ops()` calls, as every call does
  today.
- A factory-ops that retries after its command ran is refused, and answers from the output it already has (its
  definition already said "never retry").
- `.ai/runs/ops-calls/` holds one symlink per factory-ops run of the last day (`.ai/runs/` is gitignored).
- The mark is written when the hook lets the call through. If Claude Code then refuses the call for another reason
  (a permission rule), the run's one call is used up, and its next call is refused: it fails closed, and factory-ops
  answers with the error.
- What the hook does not cover (review round 1, backlog #0-233 and #0-234): another agent with Bash is not refused
  deleting a mark (`.ai/runs/ops-calls/` is not on the protected list, as `.ai/runs/LOCK` is), which would give a
  running factory-ops a second call; and a refused second call is recorded only in the agent's transcript, so the
  audit cannot count how often a factory-ops was steered.
- The control rests on Claude Code sending `agent_id` and `tool_use_id` in a subagent's PreToolUse input. If it
  stops, every factory-ops call is refused and the run stops at its preflight: visible, never silent. Checked live on
  2026-11-13 with this hook in the working tree: a factory-ops of the Agent tool ran `check-queue.sh` through both
  copies of the hook (its frontmatter one needs both fields, it has no `--agent` to pass on), left the mark
  `<agent_id> -> toolu_…`, and was refused its second command when sent one. A Workflow's factory-ops was not run on
  this hook; the spike logged both fields for one at session level, and its first autopilot run shows the rest.
- Like the hook's other rules, it binds an agent that was steered, not someone with the owner's shell.
