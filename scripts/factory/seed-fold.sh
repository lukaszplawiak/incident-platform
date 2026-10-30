#!/usr/bin/env bash
# ============================================================
# Makes the change and the seeder's edit one commit, after seed-check.sh and before the panel (backlog #0-177, #0-172).
# Prints one JSON object:
#   {"ok": true, "head"}   or   {"ok": false, "reason"}
#
#   seed-fold.sh <branch> <base> <head>
#
# seed-branch.sh leaves the change as commit <head> on <base>, and the seeder's defect is left uncommitted on top
# (seed-check.sh confirmed it; since #0-172 the seeder commits nothing, ADR-0047). The defect must not become a commit
# of its own: `git show HEAD` would be the planted lines alone, a reviewer who reads the branch commit by commit would
# be pointed at the defect, and the panel measured on an easier task than a review. So one commit on <base>, with the
# working tree's tree and the change's subject, as a round-1 autopilot branch's; the files, and so the ground truth's
# file and line, do not change. The tree is written through an index of its own, so nothing the seeder staged counts.
# The reflogs go too (review round 1 of #0-177): git logs every commit and ref move of the branch, and of HEAD, so
# `git log -g` or `git show HEAD@{1}` (both open to the reviewers) showed the change without the defect, and the diff
# of two entries the planted lines. The branch's own reflog is dropped whole (the branch is the run's, thrown away
# after it); of HEAD's, only the entries above the run's switch onto <branch> ("checkout: moving from main to
# <branch>", written by seed-branch.sh), so the rest of the owner's HEAD reflog stays. Refused, with nothing changed: a
# tree that is not on <branch> at <head>, a <head> that is not the one commit on <base>, a file git does not track, no
# change, a HEAD reflog without that switch, and one with any entry above it but seed-branch.sh's two commits (a git
# that ran on the branch since, which the seeder's read-only Bash cannot; before #0-172, a seeder that switched away
# and back).
# ADR-0044.
# Run by the seed-bugs workflow through factory-ops. Tested by .github/scripts/test-seed-branch.sh.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"

refuse() { jq -n --arg r "$1" '{ok: false, reason: $r}'; exit 0; }

[ $# -eq 3 ] || refuse "usage: seed-fold.sh <branch> <base> <head>"
branch=$1 base=$2 head=$3
[[ $branch =~ $SEED_BRANCH_RE ]] || refuse "not a seed branch name (review/<8 hex>): ${branch:0:40}"
for c in "$base" "$head"; do [[ $c =~ ^[0-9a-f]{40}$ ]] || refuse "not a commit: ${c:0:40}"; done

on=$(git rev-parse --abbrev-ref HEAD 2>/dev/null) || refuse "cannot read HEAD"
[ "$on" = "$branch" ] || refuse "the tree is on $on, not on $branch"
[ "$(git rev-parse HEAD)" = "$head" ] || refuse "HEAD is not the change's commit $head"
[ "$(git rev-parse --verify --quiet "$head^1")" = "$base" ] || refuse "$head is not the commit on top of $base"
untracked=$(git ls-files --others --exclude-standard 2>/dev/null) || refuse "cannot list the untracked files"
[ -z "$untracked" ] || refuse "files git does not track: $(tr '\n' ' ' <<<"$untracked" | cut -c1-200)"
SWITCH="checkout: moving from main to $branch"
# above <reflog subjects>: how many HEAD reflog entries sit above the run's switch onto the branch (newest first)
above() { awk -v s="$SWITCH" '$0 == s { print NR - 1; found = 1; exit } END { if (!found) exit 1 }' <<<"$1"; }
subjects=$(git reflog show --format=%gs HEAD) || refuse "cannot read the HEAD reflog"
n=$(above "$subjects") || refuse "the HEAD reflog does not show the switch onto $branch"
[ "$n" -eq 2 ] || refuse "the HEAD reflog has $n entries since the switch onto $branch, not seed-branch.sh's two commits: git ran on the branch"

index_dir=$(mktemp -d) || refuse "cannot make a temporary directory"
trap 'rm -rf "$index_dir"' EXIT
export GIT_INDEX_FILE="$index_dir/index"
git read-tree "$head" || refuse "cannot read the change's tree"
git add -u -- :/ || refuse "cannot stage the seeder's edit"
tree=$(git write-tree) || refuse "cannot write the seeded tree"
unset GIT_INDEX_FILE
[ "$tree" != "$(git rev-parse "$head^{tree}")" ] || refuse "the seeder changed nothing"
subject=$(git log -1 --format=%s "$head") || refuse "cannot read the change's subject"
folded=$(git commit-tree "$tree" -p "$base" -m "$subject") || refuse "cannot make the folded commit"
git update-ref "refs/heads/$branch" "$folded" "$head" || refuse "cannot move $branch to the folded commit"
# The files already hold this tree: only the index is set to it, and its stat data refreshed.
git read-tree "$folded" && git update-index -q --refresh >/dev/null
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
jq -n --arg h "$folded" '{ok: true, head: $h}'
