#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash), autopilot sessions only: the one push an agent may make is
#     git push -u origin <type>/<name>
# of a feature branch. No refspec (`a:main`, `HEAD:refs/heads/main`), no `+`, no force flag, no other
# remote, never main. Since backlog #0-113 the ruleset of main refuses a direct push from any account
# (ADR-0034), so this hook is the second line, and still the only one that keeps the push to the session's
# own feature branch (#0-131). Fails closed.
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap '_rc=$?; [ "$_rc" -eq 0 ] || [ "$_rc" -eq 2 ] || { echo "BLOCKED by ${0##*/}: the guard failed (exit $_rc), so the call is refused" >&2; exit 2; }' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }

input=$(cat)
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
json_checked "$rc" "the autopilot's hooks"
# A fetch may only update origin/main from origin, with no refspec: `git fetch . HEAD:refs/remotes/origin/main`
# would move the base the gates compare against.
if printf '%s' "$cmd" | grep -Eq '(^|[[:space:];&|(])git[[:space:]]+fetch([[:space:]]|$)'; then
    printf '%s' "$cmd" | grep -Eq '^git fetch( --quiet| -q)? origin( main)?$' && exit 0
    block "the only fetch allowed is 'git fetch origin main' (no refspec, no other remote)"
fi
printf '%s' "$cmd" | grep -Eq '(^|[[:space:];&|(])git[[:space:]]+push([[:space:]]|$)' || exit 0

if printf '%s' "$cmd" | grep -Eq '^git push -u origin [a-z]+/[A-Za-z0-9._-]+$'; then
    branch=${cmd##* }
    case "$branch" in
        main|*/main|HEAD|*:*|+*) ;;
        *) exit 0 ;;
    esac
fi
block "the only push allowed is 'git push -u origin <type>/<branch>' of a feature branch (no refspec, no force, never main)"
