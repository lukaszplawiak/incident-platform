#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash), autopilot sessions only: the one push an agent may make is
#     git push -u origin <type>/<name>
# of the branch the workflow recorded for this run's item. No refspec (`a:main`, `HEAD:refs/heads/main`), no `+`,
# no force flag, no other remote, never main. Since backlog #0-113 the ruleset of main refuses a direct push from
# any account (ADR-0034), so this hook is the second line there, and the only one that keeps the push to the
# run's own branch: a ruleset on a personal repository cannot say "this account, its own branches only".
# Fixed (backlog #0-131): any feature branch was allowed, the owner's open PR included, so a commit the bot
# pushed there was in the owner's PR (and an escape marker in it counted as the owner's, #0-129). The branch is
# the third field of .ai/runs/LOCK, written by scripts/factory/state.sh when the workflow has validated the
# picker's answer (run_branch in _lib.sh); no lock, or no branch in it, refuses every push. Fails closed.
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap 'case $? in 0|2) ;; *) echo "BLOCKED by ${0##*/}: the guard failed (exit $?), so the call is refused" >&2; exit 2 ;; esac' EXIT
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
        *)
            recorded=$(run_branch) || block "cannot read the run's lock (.ai/runs/LOCK), so the push is refused"
            [ -n "$recorded" ] || block "no branch is recorded for this run (.ai/runs/LOCK): the autopilot pushes only the branch the workflow recorded for its item (backlog #0-131)"
            [ "$branch" = "$recorded" ] && exit 0
            block "the autopilot pushes only its own item's branch ($recorded), not $branch (backlog #0-131)" ;;
    esac
fi
block "the only push allowed is 'git push -u origin <type>/<branch>' of the run's own branch (no refspec, no force, never main)"
