#!/usr/bin/env bash
# ============================================================
# PreToolUse (Edit|Write|MultiEdit) in the frontmatter of agents that may write only under some paths:
#   write-scope.sh .ai/audit/                          (audit agents)
#   write-scope.sh .ai/decisions/ .ai/work/            (architect)
#   write-scope.sh --agent <names> <prefix>...         the same, at session level, for those agents' calls only
# A path outside every given prefix (relative to the repository root) is refused. Fails closed. The settings files
# run it again with `--agent` (backlog #0-164, ADR-0041): frontmatter hooks are not promised to a Workflow's agents.
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap 'case $? in 0|2) ;; *) echo "BLOCKED by ${0##*/}: the guard failed (exit $?), so the call is refused" >&2; exit 2 ;; esac' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }

only=""
if [ "${1:-}" = --agent ]; then
    [ "$#" -ge 2 ] && [ -n "$2" ] || block "write-scope.sh --agent needs agent names"
    only=$2
    shift 2
fi
[ "$#" -gt 0 ] || block "write-scope.sh needs at least one allowed path prefix"
input=$(cat)
if [ -n "$only" ] && ! applies_to_agent "$only" "$input"; then exit 0; fi
file=$(json_get "$input" '.tool_input.file_path'); rc=$?
json_checked "$rc" "this agent's write guard"
if [ -z "$file" ]; then
    file=$(json_get "$input" '.tool_input.notebook_path'); rc=$?
    json_checked "$rc" "this agent's write guard"
fi
require_in_repo "$file" "$@"
exit 0
