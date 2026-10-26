#!/usr/bin/env bash
# ============================================================
# Folds the seeder's commit into the change's, after seed-check.sh and before the panel (backlog #0-177). Prints one
# JSON object:
#   {"ok": true, "head"}   or   {"ok": false, "reason"}
#
#   seed-fold.sh <branch> <base> <head> <seeded>
#
# seed-branch.sh leaves the change as commit <head> on <base>, and the seeder adds its defect as one commit <seeded> on
# top (seed-check.sh confirmed it). Left so, `git show HEAD` is the planted lines alone: a reviewer who reads the
# branch commit by commit is pointed at the defect, and the panel is measured on an easier task than a review. So the
# two become one commit on <base>, with <seeded>'s tree and the change's subject, as a round-1 autopilot branch's do;
# the tree, and so the ground truth's file and line, do not change.
# The reflogs go too (review round 1 of #0-177): git logs every commit and ref move of the branch, and of HEAD, so
# `git log -g` or `git show HEAD@{1}` (both open to the reviewers) still showed the seeder's commit alone, and the
# diff of two entries the planted lines. The branch's own reflog is dropped whole (the branch is the run's, thrown away
# after it); of HEAD's, only the entries above the run's switch onto <branch> ("checkout: moving from main to
# <branch>", written by seed-branch.sh), so the rest of the owner's HEAD reflog stays. Refused, with nothing changed: a
# tree that is not on <branch> at <seeded> and clean, a <seeded> that is not the one commit on <head> on <base>, and a
# HEAD reflog without that switch. Refused after the fold, the branch moved and the reflogs trimmed (review round 2): a
# HEAD reflog that still names <seeded> (a seeder that switched away and back); the run fails and its branch is deleted.
# ADR-0044.
# Run by the seed-bugs workflow through factory-ops. Tested by .github/scripts/test-seed-branch.sh.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"

refuse() { jq -n --arg r "$1" '{ok: false, reason: $r}'; exit 0; }

[ $# -eq 4 ] || refuse "usage: seed-fold.sh <branch> <base> <head> <seeded>"
branch=$1 base=$2 head=$3 seeded=$4
[[ $branch =~ $SEED_BRANCH_RE ]] || refuse "not a seed branch name (review/<8 hex>): ${branch:0:40}"
for c in "$base" "$head" "$seeded"; do [[ $c =~ ^[0-9a-f]{40}$ ]] || refuse "not a commit: ${c:0:40}"; done

on=$(git rev-parse --abbrev-ref HEAD 2>/dev/null) || refuse "cannot read HEAD"
[ "$on" = "$branch" ] || refuse "the tree is on $on, not on $branch"
[ "$(git rev-parse HEAD)" = "$seeded" ] || refuse "HEAD is not the checked commit $seeded"
dirty=$(git status --porcelain 2>/dev/null) || refuse "cannot read the working tree's status"
[ -z "$dirty" ] || refuse "the working tree is not clean"
[ "$(git rev-parse --verify --quiet "$seeded^1")" = "$head" ] || refuse "$seeded is not the one commit on top of $head"
[ "$(git rev-parse --verify --quiet "$head^1")" = "$base" ] || refuse "$head is not the commit on top of $base"
SWITCH="checkout: moving from main to $branch"
# above <reflog subjects>: how many HEAD reflog entries sit above the run's switch onto the branch (newest first)
above() { awk -v s="$SWITCH" '$0 == s { print NR - 1; found = 1; exit } END { if (!found) exit 1 }' <<<"$1"; }
subjects=$(git reflog show --format=%gs HEAD) || refuse "cannot read the HEAD reflog"
above "$subjects" >/dev/null || refuse "the HEAD reflog does not show the switch onto $branch"

tree=$(git rev-parse "$seeded^{tree}") || refuse "cannot read the seeded tree"
subject=$(git log -1 --format=%s "$head") || refuse "cannot read the change's subject"
folded=$(git commit-tree "$tree" -p "$base" -m "$subject") || refuse "cannot make the folded commit"
# Moves the branch only: the index and the files already hold this tree.
git update-ref "refs/heads/$branch" "$folded" "$seeded" || refuse "cannot move $branch to the folded commit"
[ "$(git rev-parse "HEAD^{tree}")" = "$tree" ] && [ -z "$(git status --porcelain)" ] \
    || refuse "the folded branch does not hold the seeded tree"
git reflog expire --expire=now --expire-unreachable=now "refs/heads/$branch" || refuse "cannot drop the reflog of $branch"
subjects=$(git reflog show --format=%gs HEAD) || refuse "cannot read the HEAD reflog"
n=$(above "$subjects") || refuse "the HEAD reflog does not show the switch onto $branch"
# The newest entry each time: deleting HEAD@{0} makes the next one HEAD@{0}.
while [ "$n" -gt 0 ]; do
    git reflog delete "HEAD@{0}" || refuse "cannot drop the HEAD reflog's entries of this run"
    n=$((n - 1))
done
[ "$(git reflog show --format=%gs -n 1 HEAD)" = "$SWITCH" ] || refuse "the HEAD reflog still holds this run's entries"
# The newest switch is the run's own; a seeder that switched away and back would leave its commit below it: refused.
hashes=$(git reflog show --format=%H HEAD) || refuse "cannot read the HEAD reflog"
! grep -qxF -e "$seeded" <<<"$hashes" || refuse "the HEAD reflog still names the seeder's commit"
jq -n --arg h "$folded" '{ok: true, head: $h}'
