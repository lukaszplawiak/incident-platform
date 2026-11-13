#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash) in the frontmatter of `factory-ops`: the only commands it may run are the factory's
# own scripts under scripts/factory/, and only while they are identical to the base branch. The workflow script cannot run a shell itself, so this agent is
# its hands for deterministic steps (preflight, tests, changed paths, state). Fails closed.
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap 'case $? in 0|2) ;; *) echo "BLOCKED by ${0##*/}: the guard failed (exit $?), so the call is refused" >&2; exit 2 ;; esac' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }

# Session-level twin (backlog #0-164): `--agent factory-ops` in the settings files; any other caller passes.
only=""
if [ "${1:-}" = --agent ]; then
    [ "$#" -ge 2 ] && [ -n "$2" ] || block "factory-ops-bash.sh --agent needs agent names"
    only=$2
    shift 2
fi
[ "$#" -eq 0 ] || block "factory-ops-bash.sh takes no argument but --agent, not: $*"
input=$(cat)
if [ -n "$only" ] && ! applies_to_agent "$only" "$input"; then exit 0; fi
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
json_checked "$rc" "factory-ops"

# Fixed (backlog #0-169): the hook let factory-ops run any factory script in each of its turns, so text a script
# printed (changed-paths.sh's file names, run-tests.sh's failures, local-review.sh's review: all the branch's) could
# steer it into `state.sh unlock`, `preflight.sh` and `state.sh branch <another>` in one task (the push guard reads
# that branch, #0-131), or `state.sh record shipped` (the breaker's count). Now one factory-ops run gets exactly one
# allowed call: its first one comes before it has read any script's output, from a prompt the workflow's code
# builds, so every call after it is refused, whatever it read. Keyed on `agent_id`, which Claude Code sets, not the
# model (spike of 2026-11-13, ADR-0053: a new id for every agent() call and Agent-tool spawn, the same id for every
# call of one run, two parallel calls in one message included). A refused call does not use it up (the spike's
# factory-ops sent an absolute path first, then the relative one). This hook runs twice for each call, from the
# agent's frontmatter and as the session-level twin (#0-164), possibly at once ("matching hooks run in parallel",
# hooks.md), so the mark is `ln -s <tool_use_id> <agent_id>`: one atomic step that creates it with its content, and
# the second copy of the same call finds its own tool_use_id there. No agent_id or tool_use_id refuses (fail closed).
one_call_per_agent() {
    local input=$1 agent tool rc root dir mark seen
    agent=$(json_get "$input" '.agent_id'); rc=$?
    json_checked "$rc" "factory-ops"
    tool=$(json_get "$input" '.tool_use_id'); rc=$?
    json_checked "$rc" "factory-ops"
    [[ $agent =~ ^[A-Za-z0-9_-]{1,64}$ ]] || block "no agent_id in the call, so it cannot be counted: factory-ops runs one command per task (backlog #0-169)"
    [[ $tool =~ ^[A-Za-z0-9_-]{1,128}$ ]] || block "no tool_use_id in the call, so it cannot be counted: factory-ops runs one command per task (backlog #0-169)"
    root=$(git rev-parse --show-toplevel 2>/dev/null) || block "cannot find the repository, so the call cannot be counted"
    dir="$root/.ai/runs/ops-calls"
    mkdir -p "$dir" || block "cannot create $dir, so the call cannot be counted"
    # Marks of runs that ended: a day is longer than any run (FACTORY_LOCK_HOURS, 3 by default). A failed prune
    # leaves old marks, which refuse nothing a new agent_id asks.
    find "$dir" -maxdepth 1 -type l -mmin +1440 -delete 2>/dev/null || true
    mark="$dir/$agent"
    # Review round 1: a mark that is a directory, or a link to one, must not be entered: plain `ln -s` creates the
    # link inside it, exits 0 and would pass every call of the run. `-n` stops it following a link to a directory,
    # not a real directory (BSD ln has no GNU `-T`), so anything at the mark's name that is not a symlink refuses
    # before `ln`, and after it the mark itself must be a symlink to this call's tool_use_id: `ln`'s exit status
    # alone is never trusted. `--`: a tool_use_id starting with `-` is not an option.
    if [ -e "$mark" ] && [ ! -L "$mark" ]; then
        block "this factory-ops run's mark ($mark) is not a symlink, so the call is refused"
    fi
    ln -sn -- "$tool" "$mark" 2>/dev/null || true
    [ -L "$mark" ] || block "this factory-ops run's mark ($mark) is not a symlink, so the call is refused"
    seen=$(readlink "$mark" 2>/dev/null) || block "cannot read this factory-ops run's mark ($mark), so the call is refused"
    [ "$seen" = "$tool" ] && return 0
    block "factory-ops runs one command per task and this run has already run its own (backlog #0-169): return the JSON it printed, or {\"error\": …} if it printed none"
}
# '$(' is the literal text of a substitution, matched, not run (backlog #0-149).
# shellcheck disable=SC2016
case "$cmd" in
    *'|'*|*';'*|*'&'*|*'>'*|*'<'*|*'$('*|*'`'*|*'#'*|*$'\n'*) block "one script per call, no chaining, redirects or comments" ;;
esac
set -f
# shellcheck disable=SC2086
set -- $cmd
case "$1" in
    scripts/factory/*.sh|./scripts/factory/*.sh)
        case "$1" in *..*) block "path with '..' refused";; esac
        [ -f "${1#./}" ] || block "no such factory script: $1"
        why=$(factory_scripts_intact) || block "factory scripts were changed on this branch ($why): the gates run only as they are on the base branch"
        one_call_per_agent "$input"
        exit 0 ;;
esac
block "factory-ops may only run scripts/factory/*.sh, not: $1"
