#!/usr/bin/env bash
# ============================================================
# PreToolUse (Read) for an agent that may read only under some paths — the read twin of write-scope.sh, with the
# same checks (require_in_repo: no `..`, no symlink as the last component, nothing outside the repository):
#   read-scope.sh [--agent <name>] [--name <glob>]... <prefix>...
#   --agent verifier   session-level (settings.json, settings.autopilot.json): checks only the calls whose
#                      `agent_type` is that agent, passes every other one; without it (the agent's frontmatter,
#                      verifier.md) every call is checked
#   --name <glob>      the file's name must also match one of them (a shell glob, quoted in the command)
# The verifier can fetch from the web (webfetch-allowlist.sh): what it can read is what it could send, so it
# reads only the files the gate writes (`--name 'gate-r*.json' --name 'dependency-tree-r*.txt' .ai/runs/`,
# backlog #0-160, ADR-0037). Not the rest of .ai/runs/: the test logs hold what the branch's own tests print,
# and the branch's code runs during the gate and can write there, so a prefix alone would let the change it
# checks put text in front of it (review round 3 of #0-160). For the same reason a file with more than one
# hard link is refused: written there by that code, it can be another name of any file in the repository
# (docker/.env), and a check of the path does not see it (review round 3).
# Both, on purpose: Claude Code documents frontmatter hooks for an agent spawned through the Agent tool, an
# @-mention or --agent, only in a trusted workspace and never in `claude -p`; for an agent the Workflow runtime
# spawns they ran in the spike of 2026-10-23, but nothing promises it. Session-level hooks, with `agent_type` in
# their input, are documented for every subagent (review round 2 of #0-160). Fails closed.
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap 'case $? in 0|2) ;; *) echo "BLOCKED by ${0##*/}: the guard failed (exit $?), so the call is refused" >&2; exit 2 ;; esac' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }

only=""
names=()
if [ "${1:-}" = --agent ]; then
    only=${2:-}
    [ -n "$only" ] || block "read-scope.sh --agent needs an agent name"
    shift 2
fi
while [ "${1:-}" = --name ]; do
    [ -n "${2:-}" ] || block "read-scope.sh --name needs a file name pattern"
    names+=("$2")
    shift 2
done
[ "$#" -gt 0 ] || block "read-scope.sh needs at least one allowed path prefix"
input=$(cat)
if [ -n "$only" ]; then
    agent=$(json_get "$input" '.agent_type'); rc=$?
    json_checked "$rc" "the read guard"
    [ "$agent" = "$only" ] || exit 0
fi
file=$(json_get "$input" '.tool_input.file_path'); rc=$?
json_checked "$rc" "this agent's read guard"
SCOPE_VERB='read' require_in_repo "$file" "$@"
if [ "${#names[@]}" -gt 0 ]; then
    base=${file##*/} ok=0
    for glob in "${names[@]}"; do
        # The pattern is a glob on purpose, matched against the name only (a `/` in it never matches).
        # shellcheck disable=SC2254
        case "$base" in $glob) ok=1 ;; esac
    done
    [ "$ok" = 1 ] || block "this agent may read only files named: ${names[*]} (tried $base)"
fi
# A file with a second name may be any file of the repository under the allowed path (see above).
if [ -e "$file" ]; then
    linked=$(find "$file" -maxdepth 0 -links +1 2>/dev/null) || block "cannot count the hard links of $file"
    [ -z "$linked" ] || block "the file has more than one hard link, a read could reach another file: $file"
fi
exit 0
