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
. "$(dirname "$0")/_lib.sh"

input=$(cat)
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
[ "$rc" -eq 3 ] && block "jq or python3 is required for the autopilot's hooks"
[ -n "$cmd" ] || exit 0

for pattern in \
    '-DskipTests' '-Dmaven\.test\.skip' '-DskipITs' '-Dsurefire\.skip' '-Dfailsafe\.skip' \
    '-Djacoco\.skip' '-Dcheckstyle\.skip' '-Denforcer\.skip' '-Dmaven\.test\.failure\.ignore' \
    '--no-verify' '--fail-never' '(^|[[:space:]])-fn([[:space:]]|$)' \
    'git[[:space:]]+commit[^|;&]*[[:space:]]-n([[:space:]]|$)' \
    'mvnw[^|;&]*[[:space:]](exec|antrun|groovy):' '-Dexec\.' '-Dmaven\.ext\.class\.path' \
    'mvnw[^|;&]*[[:space:]](-s|--settings|-gs|--global-settings)[[:space:]=]'; do
    if printf '%s' "$cmd" | grep -Eq -- "$pattern"; then
        block "the command skips or bypasses verification ($pattern). Fix the failing check instead; if a test is wrong because the requirement changed, change it and say why in handoff.md (rule GEN-08)."
    fi
done
exit 0
