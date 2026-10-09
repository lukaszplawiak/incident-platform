#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash), autopilot sessions only: a command that runs anything under scripts/factory/ runs
# only while those scripts are identical to the base branch (any agent, not only factory-ops). An agent
# that could rewrite the test gate or the path gate on its branch, and then run it, would grade itself.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

input=$(cat)
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
[ "$rc" -eq 3 ] && block "jq or python3 is required for the autopilot's hooks"
grep -q 'scripts/factory' <<<"$cmd" || exit 0
why=$(factory_scripts_intact) || block "factory scripts were changed on this branch ($why): the gates run only as they are on the base branch"
exit 0
