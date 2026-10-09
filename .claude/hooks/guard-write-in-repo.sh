#!/usr/bin/env bash
# ============================================================
# PreToolUse (Edit|Write|MultiEdit|NotebookEdit), autopilot sessions only: the file tools may write inside
# this repository and nowhere else. settings.autopilot.json allows Edit and Write without a path (its deny
# rules name repository paths only), so without this hook an agent could write ~/.claude/settings.json
# (permissions of later sessions), ~/.m2/settings.xml or ~/.mavenrc (what the test gate's Maven runs);
# ~/.claude and ~/.m2 are volumes, so such a change would outlive the run and a container rebuild.
# Code the agent runs (its tests) can still write anywhere the container lets it: this closes the file
# tools, and the test gate no longer reads the home directory (scripts/factory/run-tests.sh). The PR's
# CI is the check that nothing here can forge. Fails closed.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

input=$(cat)
file=$(json_get "$input" '.tool_input.file_path'); rc=$?
[ "$rc" -eq 3 ] && block "jq or python3 is required for the autopilot's hooks"
[ -n "$file" ] || file=$(json_get "$input" '.tool_input.notebook_path')
# The same check as write-scope.sh, from one helper: no `..`, no symlink as the final component, inside the
# repository (_lib.sh, require_in_repo).
require_in_repo "$file"
exit 0
