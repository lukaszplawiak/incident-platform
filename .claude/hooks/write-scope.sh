#!/usr/bin/env bash
# ============================================================
# PreToolUse (Edit|Write|MultiEdit) in the frontmatter of agents that may write only under some paths:
#   write-scope.sh .ai/audit/                          (audit agents)
#   write-scope.sh .ai/decisions/ .ai/work/            (architect)
# A path outside every given prefix (relative to the repository root) is refused. Fails closed.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

[ "$#" -gt 0 ] || block "write-scope.sh needs at least one allowed path prefix"
input=$(cat)
file=$(json_get "$input" '.tool_input.file_path'); rc=$?
[ "$rc" -eq 3 ] && block "jq or python3 is required for this agent's write guard"
[ -n "$file" ] || block "no file_path in the tool call"

case "$file" in
    *..*) block "path with '..' refused: $file" ;;
esac
rel=$(repo_rel "$file") || block "not in a git repository"
case "$rel" in
    /*) block "path outside the repository refused: $rel" ;;
esac
for prefix in "$@"; do
    case "$rel" in "$prefix"*) exit 0 ;; esac
done
block "this agent may write only under: $* (tried $rel)"
