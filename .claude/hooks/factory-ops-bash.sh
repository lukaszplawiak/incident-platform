#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash) in the frontmatter of `factory-ops`: the only commands it may run are the factory's
# own scripts under scripts/factory/, and only while they are identical to the base branch. The workflow script cannot run a shell itself, so this agent is
# its hands for deterministic steps (preflight, tests, changed paths, state). Fails closed.
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap '_rc=$?; [ "$_rc" -eq 0 ] || [ "$_rc" -eq 2 ] || { echo "BLOCKED by ${0##*/}: the guard failed (exit $_rc), so the call is refused" >&2; exit 2; }' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }

input=$(cat)
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
json_checked "$rc" "factory-ops"
# '$(' is the literal text of a substitution, matched, not run (backlog #0-149).
# shellcheck disable=SC2016
case "$cmd" in
    *'|'*|*';'*|*'&'*|*'>'*|*'<'*|*'$('*|*'`'*|*'#'*|*$'\n'*) block "one script per call, no chaining, redirects or comments" ;;
esac
set -f
# shellcheck disable=SC2086
set -- $cmd
case "$1" in
    scripts/factory/*.sh|./scripts/factory/*.sh)
        case "$1" in *..*) block "path with '..' refused";; esac
        [ -f "${1#./}" ] || block "no such factory script: $1"
        why=$(factory_scripts_intact) || block "factory scripts were changed on this branch ($why): the gates run only as they are on the base branch"
        exit 0 ;;
esac
block "factory-ops may only run scripts/factory/*.sh, not: $1"
