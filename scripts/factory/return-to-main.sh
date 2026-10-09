#!/usr/bin/env bash
# ============================================================
# Ends a run on main, so the next preflight can start. Never discards work: with a dirty tree it only
# reports, and the owner looks.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"
branch=$(git rev-parse --abbrev-ref HEAD)
if [ "$branch" = main ]; then echo '{"onMain":true}'; exit 0; fi
if [ -n "$(git status --porcelain)" ]; then
    jq -n --arg b "$branch" '{onMain:false, branch:$b, reason:"working tree not clean: left as it is for the owner"}'
    exit 0
fi
git switch --quiet main && jq -n --arg b "$branch" '{onMain:true, left:$b}'
