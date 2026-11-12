#!/usr/bin/env bash
# ============================================================
# Switches an autopilot run to the existing branch it resumes (backlog #0-215). Prints one JSON object:
#   {"ok": true, "branch", "item", "head"}   or   {"ok": false, "reasons": [...]}
#
#   resume-item.sh <base sha> <branch>      e.g. resume-item.sh <40 hex> fix/0-25-queue-version
#
# On a clean main at <base sha> (the commit the preflight recorded), and only for a branch named as an item's branch
# (`<type>/0-<n>-<slug>`, the item `0-<n>` read from it) that exists locally and holds that item's
# .ai/work/0-<n>/progress.md (a branch an earlier run started): `git switch <branch>`, nothing else. A refusal changes
# nothing: the tree stays on main.
#
# Fixed (backlog #0-215): on resume the picker ran this `git switch` itself, so its Bash had to hold the autopilot's
# git write rules (`git switch *` matches `git switch -c` too, and `git commit *`, `git push -u origin *` came with it)
# while it reads text an outsider can write (PR titles); a guard of its own could only check the branch's shape, never
# that it is the branch the owner gave (args.branch). The switch has one right result, so it is code, as the start of
# an item is (start-item.sh, #0-209), and the picker's Bash is read-only (readonly-bash.sh --picker).
# Run by the backlog-autopilot workflow through factory-ops, before the picker, in resume mode only.
# Tested by .github/scripts/test-resume-item.sh.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"

refuse() { jq -n --arg r "$1" '{ok: false, reasons: [$r]}'; exit 0; }

[ $# -eq 2 ] || refuse "usage: resume-item.sh <base sha> <branch>"
base=$1 branch=$2
[[ $base =~ ^[0-9a-f]{40}$ ]] || refuse "not a commit sha (40 hex): ${base:0:48}"
# The rule of an item's branch (_common.sh's ITEM_BRANCH_RE, backlog #0-216), with the item read from the name.
[[ $branch =~ $ITEM_BRANCH_RE ]] || refuse "not an item's branch (<type>/0-<n>-<slug>): ${branch:0:80}"
item=${BASH_REMATCH[1]}

why=$(clean_main_at "$base") || refuse "$why"
git show-ref --verify --quiet "refs/heads/$branch" || refuse "no local branch $branch to resume"
git cat-file -e "refs/heads/$branch:.ai/work/$item/progress.md" 2>/dev/null \
    || refuse "the branch $branch holds no .ai/work/$item/progress.md: not a branch an autopilot run started"

git switch --quiet "$branch" 2>/dev/null || refuse "cannot switch to the branch $branch"
head=$(git rev-parse HEAD) || refuse "cannot read the head of $branch"
jq -n --arg b "$branch" --arg i "$item" --arg h "$head" '{ok: true, branch: $b, item: $i, head: $h}'
