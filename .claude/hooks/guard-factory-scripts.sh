#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash), autopilot sessions only: a command that runs anything under scripts/factory/ runs
# only while those scripts are identical to the base branch (any agent, not only factory-ops). An agent
# that could rewrite the test gate or the path gate on its branch, and then run it, would grade itself.
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap '_rc=$?; [ "$_rc" -eq 0 ] || [ "$_rc" -eq 2 ] || { echo "BLOCKED by ${0##*/}: the guard failed (exit $_rc), so the call is refused" >&2; exit 2; }' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }

input=$(cat)
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
json_checked "$rc" "the autopilot's hooks"
grep -q 'scripts/factory' <<<"$cmd" || exit 0
why=$(factory_scripts_intact) || block "factory scripts were changed on this branch ($why): the gates run only as they are on the base branch"
exit 0
