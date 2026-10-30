#!/usr/bin/env bash
# ============================================================
# Ends a run on main, so the next preflight can start. Never discards work: with a dirty tree it only
# reports, and the owner looks.
# Back on main, a /seed-bugs run's record (.ai/runs/SEED, seed-branch.sh) goes: the run is over (backlog #0-172). Left
# on its branch, the record stays, so the hooks still hold the seeder's scope there while the owner looks.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"
branch=$(git rev-parse --abbrev-ref HEAD)
if [ "$branch" = main ]; then rm -f "$RUNS_DIR/SEED"; echo '{"onMain":true}'; exit 0; fi
if [ -n "$(git status --porcelain)" ]; then
    jq -n --arg b "$branch" '{onMain:false, branch:$b, reason:"working tree not clean: left as it is for the owner"}'
    exit 0
fi
git switch --quiet main && rm -f "$RUNS_DIR/SEED" && jq -n --arg b "$branch" '{onMain:true, left:$b}'
