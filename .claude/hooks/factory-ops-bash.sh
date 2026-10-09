#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash) in the frontmatter of `factory-ops`: the only commands it may run are the factory's
# own scripts under scripts/factory/, and only while they are identical to the base branch. The workflow script cannot run a shell itself, so this agent is
# its hands for deterministic steps (preflight, tests, changed paths, state). Fails closed.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

input=$(cat)
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
[ "$rc" -eq 3 ] && block "jq or python3 is required for factory-ops"
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
