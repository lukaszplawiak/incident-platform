#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash), autopilot sessions only: a command that runs anything under scripts/factory/ runs
# only while those scripts are identical to the base branch (any agent, not only factory-ops). An agent
# that could rewrite the test gate or the path gate on its branch, and then run it, would grade itself.
# And only the factory-ops agent runs them (backlog #0-131, review round 1): the workflow runs every factory
# script through it, while the session's allow rule `Bash(scripts/factory/*)` holds for every agent, so an
# implementer or shipper steered by the text it reads could `state.sh unlock`, `preflight.sh` and
# `state.sh branch <another>`, and move the one branch guard-git-push.sh lets it push. Keyed on agent_type (the
# session-level hook sees every agent's call, ADR-0041): no agent_type, the main session included, is refused
# too. Any command that names scripts/factory/ is refused to them, but one: a single read-only git command
# (`git grep|diff|log|show|ls-files|blame …`, no separator, substitution or redirect) that only names a path.
# Not a match on how a script is run (review round 2: quoting or grouping the command word would need its own
# case each); what is not that one shape is refused. Code an agent runs through ./mvnw is not seen here (#0-116).
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap 'case $? in 0|2) ;; *) echo "BLOCKED by ${0##*/}: the guard failed (exit $?), so the call is refused" >&2; exit 2 ;; esac' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }

input=$(cat)
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
json_checked "$rc" "the autopilot's hooks"
# bash's own match, not grep: a grep that fails would read as "no match" and let the call through.
[[ $cmd == *scripts/factory* ]] || exit 0
if ! applies_to_agent factory-ops "$input"; then
    case "$cmd" in
        *$'\n'*) block "only the factory-ops agent runs scripts/factory/ in an autopilot session; the workflow runs every factory script through it (backlog #0-131)" ;;
    esac
    case "$cmd" in *--output*|*--ext-diff*|*--textconv*)
        block "git's writing options are not allowed next to scripts/factory/ (backlog #0-131)" ;;
    esac
    # The backtick and $ are the characters refused (a substitution), matched literally (backlog #0-149).
    # shellcheck disable=SC2016
    finds "$cmd" '^git[[:space:]]+(grep|diff|log|show|ls-files|blame)([[:space:]][^;&|<>`$(){}]*)?$' \
        || block "only the factory-ops agent runs scripts/factory/ in an autopilot session; the workflow runs every factory script through it (backlog #0-131). Naming the path is fine in one read-only git command (git grep|diff|log|show|ls-files|blame), nothing else"
fi
why=$(factory_scripts_intact) || block "factory scripts were changed on this branch ($why): the gates run only as they are on the base branch"
exit 0
