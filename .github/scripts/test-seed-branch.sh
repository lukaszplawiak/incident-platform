#!/usr/bin/env bash
# ============================================================
# Tests for scripts/factory/seed-branch.sh and seed-check.sh (backlog #0-173), in a temporary repository with an
# origin: the seed branch starts from main (so the evaluator stays main's), its base reverts the change's code and its
# head brings it back, Markdown is not replayed, a rename is a deletion and an addition, a merge commit is its whole
# pull request, and every refusal leaves the tree on a clean main without the branch; seed-check.sh confirms the
# seeder's one commit on that branch, in the change's files, and refuses anything else.
# Run: .github/scripts/test-seed-branch.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }

R=$WORK/repo
git init -q --bare -b main "$WORK/origin.git"
git init -q -b main "$R"
cd "$R" || exit 1
git config user.email t@t && git config user.name t
mkdir -p scripts/factory .ai/rules svc .claude
cp "$SRC"/scripts/factory/_common.sh "$SRC"/scripts/factory/_protected.sh "$SRC"/scripts/factory/seed-branch.sh \
   "$SRC"/scripts/factory/seed-check.sh scripts/factory/
cp "$SRC"/.ai/rules/protected-paths.md .ai/rules/
printf '.ai/runs/\n' > .gitignore
printf 'v1\n' > svc/A.java && printf 'backlog 1\n' > BACKLOG.md && printf 'x\n' > .claude/x
printf 'b\n' > svc/B.java && printf 'd\n' > svc/D.java && printf 'e\n' > svc/E.java && printf 'm1\n' > svc/M.java
git add -A && git commit -qm base
printf 'v2\n' > svc/A.java && printf 'backlog 2\n' > BACKLOG.md
git add -A && git commit -qm "fix(svc): the change under test"
change=$(git rev-parse HEAD)
printf 'y\n' > .claude/x && git add -A && git commit -qm "chore: touches the evaluator"
evaluator_change=$(git rev-parse HEAD)
printf 'backlog 3\n' > BACKLOG.md && git add -A && git commit -qm "docs: backlog moved on"
md_only=$(git rev-parse HEAD)
git mv svc/B.java svc/C.java && git rm -q svc/D.java && git commit -qm "refactor(svc): B becomes C, D goes"
rename_change=$(git rev-parse HEAD)
git rm -q svc/E.java && git commit -qm "chore(svc): drop E"
delete_change=$(git rev-parse HEAD)
git switch -q -c feat && printf 'm2\n' > svc/M.java && git add -A && git commit -qm "feat(svc): the merged change"
git switch -q main && git merge -q --no-ff -m "Merge pull request #1 from feat" feat && git branch -q -D feat
merge_change=$(git rev-parse HEAD)
git remote add origin "$WORK/origin.git" && git push -q origin main 2>/dev/null && git fetch -q origin

SB=scripts/factory/seed-branch.sh
SC=scripts/factory/seed-check.sh
on_clean_main() { [ "$(git rev-parse --abbrev-ref HEAD)" = main ] && [ -z "$(git status --porcelain)" ]; }
# refused <name> <reason regex> <args...>: ok false with that reason, and the tree left on a clean main, no seed branch.
refused() {
    local name=$1 re=$2 out; shift 2
    out=$("$SB" "$@")
    if jq -e --arg re "$re" '.ok == false and (.reason | test($re))' <<<"$out" >/dev/null && on_clean_main \
       && [ -z "$(git branch --list 'seed/*')" ]; then ok "$name"
    else fail "$name: $out (on $(git rev-parse --abbrev-ref HEAD), branches: $(git branch --list 'seed/*' | tr '\n' ' '))"; fi
}

# reason <name> <reason regex> <args...>: ok false with that reason (for the cases set up off a clean main).
reason() {
    local name=$1 re=$2 out; shift 2
    out=$("$SB" "$@")
    if jq -e --arg re "$re" '.ok == false and (.reason | test($re))' <<<"$out" >/dev/null; then ok "$name"; else fail "$name: $out"; fi
}

echo "seed-branch.sh: what it refuses"
refused "bad pattern" "not a pattern id" 'P-1;x' r1 "$change"
refused "bad run id" "not a run id" P-10 'r 1' "$change"
refused "bad commit" "not a commit" P-10 r1 HEAD
refused "unknown commit" "no such commit" P-10 r1 deadbeef
refused "a change that touches the evaluator" "touches the evaluator \\(\\.claude/x" P-10 r1 "$evaluator_change"
refused "a change to Markdown only" "no code to replay" P-10 r1 "$md_only"
refused "a change that only deletes code" "nothing to plant in" P-10 r1 "$delete_change"
git switch -q -c other && reason "not on main" "not on main" P-10 r1 "$change"; git switch -q main && git branch -q -D other
printf 'dirt\n' > svc/A.java; out=$("$SB" P-10 r1 "$change")
if jq -e '.ok == false and (.reason | test("not clean"))' <<<"$out" >/dev/null; then ok "a dirty tree"; else fail "a dirty tree: $out"; fi
git checkout -q -- svc/A.java
mkdir -p .ai/audit/benchmark && printf '| row |\n' > .ai/audit/benchmark/results.md
reason "the last run's results.md, named" "commit \\.ai/audit/benchmark/results\\.md" P-10 r1 "$change"
printf 'dirt\n' > svc/A.java
reason "results.md and more: not clean" "not clean" P-10 r1 "$change"
git checkout -q -- svc/A.java && rm -rf .ai/audit
mkdir -p .ai/runs && echo "R1 $(git rev-parse HEAD)" > .ai/runs/LOCK
refused "an autopilot run holds the lock" "holds the lock" P-10 r1 "$change"
rm -f .ai/runs/LOCK
printf 'local\n' > svc/C.java && git add svc/C.java && git commit -qm local
refused "main ahead of origin/main" "not at origin/main" P-10 r1 "$change"
git reset -q --hard origin/main
# A protected-paths.md whose Owner-only list cannot be read (its markers gone) refuses, even with build-config intact.
sed -i.bak -e '/protected:start/d' -e '/protected:end/d' .ai/rules/protected-paths.md && rm -f .ai/rules/protected-paths.md.bak
git commit -qam "chore: markers lost" && git update-ref refs/remotes/origin/main HEAD
refused "an Owner-only list that cannot be read" "cannot read the protected paths .*: protected" P-10 r1 "$evaluator_change"
git reset -q --hard HEAD~1 && git update-ref refs/remotes/origin/main HEAD

echo "seed-branch.sh: the branch"
out=$("$SB" P-10 r1 "$change")
if jq -e '.ok == true and .branch == "seed/P-10-r1" and .files == ["svc/A.java"]' <<<"$out" >/dev/null; then ok "made, with the change's code files only"
else fail "made: $out"; fi
base=$(jq -r .base <<<"$out"); head=$(jq -r .head <<<"$out")
if [ "$(git rev-parse --abbrev-ref HEAD)" = seed/P-10-r1 ]; then ok "checked out"; else fail "not checked out"; fi
if [ "$(git rev-parse "$base^")" = "$(git rev-parse main)" ]; then ok "starts from main"; else fail "does not start from main"; fi
if [ "$(git show "$base:svc/A.java")" = v1 ]; then ok "the base has the change's code reverted"; else fail "base A.java: $(git show "$base:svc/A.java")"; fi
if [ "$(git show "$head:svc/A.java")" = v2 ]; then ok "the head has it back"; else fail "head A.java"; fi
if [ "$(git show "$head:BACKLOG.md")" = "backlog 3" ]; then ok "Markdown is main's, not replayed"; else fail "BACKLOG.md was replayed"; fi
if [ "$(git show "$head:.claude/x")" = "$(git show main:.claude/x)" ]; then ok "the evaluator is main's"; else fail ".claude changed"; fi
if [ "$(git log -1 --format=%s "$head")" = "fix(svc): the change under test" ]; then ok "the head carries the change's subject"; else fail "subject: $(git log -1 --format=%s "$head")"; fi

echo "seed-check.sh: the tree the panel runs on"
check() {   # check <name> <reason regex | ok> <args...>
    local name=$1 re=$2 out; shift 2
    out=$("$SC" "$@")
    if [ "$re" = ok ]; then
        if jq -e '.ok == true and .files == ["svc/A.java"]' <<<"$out" >/dev/null; then ok "$name"; else fail "$name: $out"; fi
    elif jq -e --arg re "$re" '.ok == false and (.reason | test($re))' <<<"$out" >/dev/null; then ok "$name"
    else fail "$name: $out"; fi
}
check "no seeder's commit yet" "not the one commit on top" P-10 r1 "$base" "$head"
printf 'v2 planted\n' > svc/A.java && git commit -qam "refactor(svc): simplify"
check "the seeder's one commit, in the change's file" ok P-10 r1 "$base" "$head"
check "bad base" "not a base commit" P-10 r1 abc "$head"
check "a base that is not under the head" "is not the commit on top of" P-10 r1 "$(git rev-parse main)" "$head"
check "another run's branch" "not on seed/P-10-r9" P-10 r9 "$base" "$head"
printf 'more\n' >> svc/A.java
check "changes left uncommitted" "not clean" P-10 r1 "$base" "$head"
git checkout -q -- svc/A.java
printf 'again\n' >> svc/A.java && git commit -qam "refactor(svc): again"
check "two commits on top" "not the one commit on top" P-10 r1 "$base" "$head"
git reset -q --hard "$head" && printf 'z\n' > svc/Z.java && git add svc/Z.java && git commit -qm "refactor(svc): add Z"
check "a file outside the change" "outside the change: svc/Z.java" P-10 r1 "$base" "$head"
git reset -q --hard "$head" && printf 'w\n' > .claude/x && git commit -qam "refactor(svc): tweak"
check "the evaluator, outside the change" "outside the change: \\.claude/x" P-10 r1 "$base" "$head"
git reset -q --hard "$head"
git switch -q main
check "not on the seed branch" "on main, not on seed/P-10-r1" P-10 r1 "$base" "$head"
reason "the branch exists already" "exists already" P-10 r1 "$change"
if [ "$(git rev-parse seed/P-10-r1)" = "$head" ] && on_clean_main; then ok "an existing branch is left as it was"; else fail "an existing branch was touched"; fi
git branch -D -q seed/P-10-r1

echo "seed-branch.sh: renames, deletions and merge commits"
out=$("$SB" P-11 r3 "$rename_change")
if jq -e '.ok == true and .files == ["svc/C.java"]' <<<"$out" >/dev/null; then ok "a rename: only the file that is left to plant in"
else fail "rename: $out"; fi
base=$(jq -r .base <<<"$out"); head=$(jq -r .head <<<"$out")
if git cat-file -e "$base:svc/B.java" && git cat-file -e "$base:svc/D.java" && ! git cat-file -e "$base:svc/C.java" 2>/dev/null \
   && ! git cat-file -e "$head:svc/B.java" 2>/dev/null && ! git cat-file -e "$head:svc/D.java" 2>/dev/null; then
    ok "the base has B and D back, the head moves B to C and drops D"
else fail "rename base/head trees"; fi
printf 'b again\n' > svc/B.java && git add svc/B.java && git commit -qm "refactor(svc): B back"
check "a file the change deleted, brought back" "outside the change: svc/B.java" P-11 r3 "$base" "$head"
git reset -q --hard "$head"
git switch -q main && git branch -D -q seed/P-11-r3
out=$("$SB" P-12 r4 "$merge_change")
if jq -e '.ok == true and .files == ["svc/M.java"]' <<<"$out" >/dev/null; then ok "a merge commit: its pull request's files"; else fail "merge: $out"; fi
base=$(jq -r .base <<<"$out"); head=$(jq -r .head <<<"$out")
if [ "$(git show "$base:svc/M.java")" = m1 ] && [ "$(git show "$head:svc/M.java")" = m2 ]; then ok "the merge's change reverted, then back"
else fail "merge base/head"; fi
if [ "$(git log -1 --format=%s "$head")" = "feat(svc): the merged change" ]; then ok "under its pull request's subject"
else fail "merge subject: $(git log -1 --format=%s "$head")"; fi
git switch -q main && git branch -D -q seed/P-12-r4

echo "seed-branch.sh: a change that no longer applies"
printf 'v3\n' > svc/A.java && git add -A && git commit -qm "fix(svc): later" && git push -q origin main 2>/dev/null && git fetch -q origin
refused "the change's code changed since" "no longer applies" P-10 r2 "$change"

if [ "$failures" -gt 0 ]; then echo "$failures seed-branch/seed-check test(s) failed"; exit 1; fi
echo "All seed-branch and seed-check tests passed."
