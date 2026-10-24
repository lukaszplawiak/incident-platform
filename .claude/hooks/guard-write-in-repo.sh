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
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap 'case $? in 0|2) ;; *) echo "BLOCKED by ${0##*/}: the guard failed (exit $?), so the call is refused" >&2; exit 2 ;; esac' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }

input=$(cat)
file=$(json_get "$input" '.tool_input.file_path'); rc=$?
json_checked "$rc" "the autopilot's hooks"
if [ -z "$file" ]; then
    file=$(json_get "$input" '.tool_input.notebook_path'); rc=$?
    json_checked "$rc" "the autopilot's hooks"
fi
# The same check as write-scope.sh, from one helper: no `..`, no symlink as the final component, inside the
# repository (_lib.sh, require_in_repo).
require_in_repo "$file"
exit 0
