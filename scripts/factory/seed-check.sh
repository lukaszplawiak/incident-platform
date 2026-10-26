#!/usr/bin/env bash
# ============================================================
# Checks the seed branch after the defect-seeder, before the panel reviews it (backlog #0-173, review round 1). Prints
# one JSON object:
#   {"ok": true, "commit", "files": [...]}   or   {"ok": false, "reason"}
#
#   seed-check.sh <branch> <base> <head>
#
# The seeder's answer names its branch, but its word is not what the panel runs on: the checked-out tree is. A seeder
# that switched (`git switch` is allowed to it) or committed outside the change would have the six reviewers run on
# another tree's hooks and agents, the failure #0-173 fixes. So the tree must be on <branch> (`review/<8 hex>`, #0-177),
# clean, with exactly one commit on top of seed-branch.sh's <head>, and that commit may touch only files the change
# brought back and kept (the diff of <base> to <head>, without the files it deleted: seed-branch.sh's `files`). `files`
# are the files the seeder's commit touched: the workflow checks the ground truth's file among them. Changes nothing:
# seed-fold.sh then folds the `commit` it names into the change's. ADR-0044.
# Run by the seed-bugs workflow through factory-ops. Tested by .github/scripts/test-seed-branch.sh.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"

refuse() { jq -n --arg r "$1" '{ok: false, reason: $r}'; exit 0; }

[ $# -eq 3 ] || refuse "usage: seed-check.sh <branch> <base> <head>"
branch=$1 base=$2 head=$3
[[ $branch =~ $SEED_BRANCH_RE ]] || refuse "not a seed branch name (review/<8 hex>): ${branch:0:40}"
[[ $base =~ ^[0-9a-f]{40}$ ]] || refuse "not a base commit: ${base:0:40}"
[[ $head =~ ^[0-9a-f]{40}$ ]] || refuse "not a head commit: ${head:0:40}"

on=$(git rev-parse --abbrev-ref HEAD 2>/dev/null) || refuse "cannot read HEAD"
[ "$on" = "$branch" ] || refuse "the tree is on $on, not on $branch"
dirty=$(git status --porcelain 2>/dev/null) || refuse "cannot read the working tree's status"
[ -z "$dirty" ] || refuse "the working tree is not clean: the seeder left changes uncommitted"
commit=$(git rev-parse HEAD) || refuse "cannot read HEAD"
parent=$(git rev-parse --verify --quiet "HEAD^1") || refuse "HEAD has no parent"
[ "$parent" = "$head" ] || refuse "the seeder's commit is not the one commit on top of $head"
[ "$(git rev-parse --verify --quiet "HEAD^2")" = "" ] || refuse "the seeder's commit is a merge"
[ "$(git rev-parse --verify --quiet "$head^1")" = "$base" ] || refuse "$head is not the commit on top of $base"

allowed=$(git diff --name-only --no-renames --diff-filter=d "$base" "$head") || refuse "cannot list the change's files"
planted=$(git diff --name-only --no-renames "$head" HEAD) || refuse "cannot list the seeder's files"
[ -n "$planted" ] || refuse "the seeder's commit changes nothing"
# Every planted path must be one of the change's: compared line by line, never as a pattern.
outside=$(grep -vxFf <(printf '%s\n' "$allowed") <<<"$planted"); rc=$?
[ "$rc" -le 1 ] || refuse "cannot compare the seeder's files with the change's"
[ -z "$outside" ] || refuse "the seeder's commit touches files outside the change: $(tr '\n' ' ' <<<"$outside" | cut -c1-200)"
jq -n --arg c "$commit" --arg f "$planted" '{ok: true, commit: $c, files: ($f | split("\n") | map(select(. != "")))}'
