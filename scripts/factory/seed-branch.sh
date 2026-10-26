#!/usr/bin/env bash
# ============================================================
# The branch a /seed-bugs run plants its defect on (backlog #0-173). Prints one JSON object:
#   {"ok": true, "branch", "base", "head", "files": [...]}   or   {"ok": false, "reason"}
#
#   seed-branch.sh <branch> <commit>
#
# The branch starts from the current main, never from the merged change itself: the hooks, settings, agents and rules
# the session runs on live in this working tree (and Claude Code reads them from disk), so a switch to an old commit
# ran the whole run on that commit's guards and agents (2026-10-25: 253e5b2a's guard refused the seeder, its tree had
# no read-scope.sh, the owner's session lost its verifier). Instead, on <branch> from main:
#   commit A (the base the panel diffs against): the change's code reverted, as git's own `Revert "<subject>"`;
#   commit B: the change's code back, under the change's own subject;
# and the seeder then plants the defect in one of `files` and commits it, and seed-fold.sh folds that commit into B. The
# panel reviews B and the defect as one change, as the benchmark means, under main's hooks, agents and rules.
# Nothing the panel reads names the run (backlog #0-177): <branch> is `review/<8 hex>`, a name the workflow derives from
# the pattern and the run id and the reviewers cannot read back, and A's message is the one `git revert` would write.
# The P-10..P-12 runs had `seed/<pattern>-<run id>` in the panel's prompt and "seed base … (/seed-bugs, not for
# review)" one `git log` away; their reviewers' transcripts show no read of the catalogue, but the measure must not rest
# on that.
# Only the change's code is replayed: Markdown and the backlog files would clash with today's main. A rename is a
# deletion and an addition (--no-renames), and `files` (where the seeder may plant) leaves out the files the change
# deleted. A merge commit is replayed as its whole pull request (the diff from its first parent), under the subject of
# its second parent. Refused: a tree that is not a clean main at origin/main (a run's row goes to the gitignored
# .ai/runs/benchmark/results.md, seed-record.sh, so it never dirties main: #0-177), an autopilot run in progress
# (.ai/runs/LOCK), a branch that exists, a change that touches a path of .ai/rules/protected-paths.md or CLAUDE.md
# (its revert would change the evaluator), a change with no code left to replay or to plant in, and one that no longer
# applies to main (then a newer commit is needed).
# On any refusal after the branch exists, the tree goes back to main and the branch is deleted. ADR-0044.
# Run by the seed-bugs workflow through factory-ops. Tested by .github/scripts/test-seed-branch.sh.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"
. "$(dirname "$0")/_protected.sh"

refuse() { jq -n --arg r "$1" '{ok: false, reason: $r}'; exit 0; }
branch=""
undo() {   # undo <reason>: back to main, the half-made branch gone, then refuse
    git reset --quiet --hard >/dev/null 2>&1
    git switch --quiet main >/dev/null 2>&1
    [ -n "$branch" ] && git branch -D --quiet "$branch" >/dev/null 2>&1
    refuse "$1"
}

[ $# -eq 2 ] || refuse "usage: seed-branch.sh <branch> <commit>"
branch_name=$1 commit=$2
[[ $branch_name =~ $SEED_BRANCH_RE ]] || refuse "not a seed branch name (review/<8 hex>): ${branch_name:0:40}"
[[ $commit =~ ^[0-9a-f]{7,40}$ ]] || refuse "not a commit: ${commit:0:40}"

[ "$(git rev-parse --abbrev-ref HEAD 2>/dev/null)" = main ] || refuse "not on main: start /seed-bugs from main"
dirty=$(git status --porcelain --untracked-files=all 2>/dev/null) || refuse "cannot read the working tree's status"
[ -z "$dirty" ] || refuse "the working tree is not clean"
head=$(git rev-parse HEAD) || refuse "cannot read HEAD"
origin=$(git rev-parse --verify --quiet refs/remotes/origin/main) || refuse "no refs/remotes/origin/main: fetch first"
[ "$head" = "$origin" ] || refuse "main is not at origin/main: pull first"
[ ! -e "$RUNS_DIR/LOCK" ] || refuse "an autopilot run holds the lock ($RUNS_DIR/LOCK)"

sha=$(git rev-parse --verify --quiet "$commit^{commit}") || refuse "no such commit: $commit"
git merge-base --is-ancestor "$sha" "$head" || refuse "$commit is not on main"
parent=$(git rev-parse --verify --quiet "$sha^1") || refuse "$commit has no parent"
git show-ref --verify --quiet "refs/heads/$branch_name" && refuse "the branch $branch_name exists already"

changed=$(git diff --name-only --no-renames "$parent" "$sha") || refuse "cannot list the change's files"
# Each list read on its own: in a brace group only the last call's status counts, so a list that cannot be read would
# leave its paths out of the check without a refusal (review round 2 of #0-173).
owner_only=$(protected_paths protected) || refuse "cannot read the protected paths ($PROTECTED_PATHS_FILE: protected)"
build_config=$(protected_paths build-config) || refuse "cannot read the protected paths ($PROTECTED_PATHS_FILE: build-config)"
protected=$(printf '%s\n%s\n' "$owner_only" "$build_config" | paths_regex anchored) \
    || refuse "cannot read the protected paths ($PROTECTED_PATHS_FILE)"
# grep's own error (2) must not read as "no protected path" (backlog #0-132).
evaluator=$(grep -E "$protected|^CLAUDE\.md$" <<<"$changed"); rc=$?
[ "$rc" -le 1 ] || refuse "cannot check the change's paths against the protected paths"
[ -z "$evaluator" ] || refuse "the change touches the evaluator ($(tr '\n' ' ' <<<"$evaluator")): choose another commit"
# One path per array element (a path may hold a space), read literally by git (no glob or pathspec magic).
code=()
while IFS= read -r f; do [ -n "$f" ] && code+=("$f"); done < <(grep -vE '\.md$' <<<"$changed")
[ "${#code[@]}" -gt 0 ] || refuse "the change has no code to replay"
kept=$(git --literal-pathspecs diff --name-only --no-renames --diff-filter=d "$parent" "$sha" -- "${code[@]}") \
    || refuse "cannot list the change's remaining files"
[ -n "$kept" ] || refuse "the change only deletes code: nothing to plant in"

patch=$(mktemp) || refuse "cannot make a temporary file"
trap 'rm -f "$patch"' EXIT
git --literal-pathspecs diff --binary --no-renames "$parent" "$sha" -- "${code[@]}" >"$patch" || refuse "cannot read the change's diff"
subject=$(git log -1 --format=%s "$sha^2" 2>/dev/null || git log -1 --format=%s "$sha") || subject="the change"

branch=$branch_name
git switch --quiet -c "$branch" "$head" || undo "cannot create $branch"
git apply --index -R "$patch" 2>/dev/null || undo "the change no longer applies to main (its code changed since): choose a newer commit"
# git revert's subject, without its "This reverts commit <sha>." line: that sha is on main, and would say the change is.
git commit --quiet -m "Revert \"$subject\"" || undo "cannot commit the base"
base=$(git rev-parse HEAD)
git apply --index "$patch" || undo "the change does not apply back on its base"
git commit --quiet -m "$subject" || undo "cannot commit the change"
jq -n --arg b "$branch" --arg base "$base" --arg h "$(git rev-parse HEAD)" --arg f "$kept" \
    '{ok: true, branch: $b, base: $base, head: $h, files: ($f | split("\n") | map(select(. != "")))}'
