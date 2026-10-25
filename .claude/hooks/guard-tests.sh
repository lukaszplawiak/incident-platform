#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash), autopilot sessions only (.claude/settings.autopilot.json): refuse commands that skip
# or bypass verification, or that make Maven run code a POM did not declare (exec:, antrun:, a
# different settings.xml). An unattended agent under a failing test is tempted to make it pass by not
# running it; this hook removes the option (rule GEN-08, .ai/rules/implementation.md).
#
# Not in .claude/settings.json: CLAUDE.md's own full-build command uses -DskipTests, which is fine when
# the owner runs it and verification follows.
# Fails closed: without jq or python3 every Bash call is refused.
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap 'case $? in 0|2) ;; *) echo "BLOCKED by ${0##*/}: the guard failed (exit $?), so the call is refused" >&2; exit 2 ;; esac' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }

input=$(cat)
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
json_checked "$rc" "the autopilot's hooks"
[ -n "$cmd" ] || exit 0

for pattern in \
    '-DskipTests' '-Dmaven\.test\.skip' '-DskipITs' '-Dsurefire\.skip' '-Dfailsafe\.skip' \
    '-Djacoco\.skip' '-Dcheckstyle\.skip' '-Denforcer\.skip' '-Dmaven\.test\.failure\.ignore' \
    '--no-verify' '--fail-never' '(^|[[:space:]])-fn([[:space:]]|$)' \
    'git[[:space:]]+commit[^|;&]*[[:space:]]-n([[:space:]]|$)' \
    'mvnw[^|;&]*[[:space:]](exec|antrun|groovy):' '-Dexec\.' '-Dmaven\.ext\.class\.path' \
    'mvnw[^|;&]*[[:space:]](-s|--settings|-gs|--global-settings)[[:space:]=]'; do
    if finds "$cmd" "$pattern"; then
        block "the command skips or bypasses verification ($pattern). Fix the failing check instead; if a test is wrong because the requirement changed, change it and say why in handoff.md (rule GEN-08)."
    fi
done
exit 0
