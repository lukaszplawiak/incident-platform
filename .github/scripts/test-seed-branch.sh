#!/usr/bin/env bash
# ============================================================
# Tests for scripts/factory/seed-branch.sh, seed-check.sh (backlog #0-173), seed-fold.sh and seed-record.sh (#0-177),
# in a temporary repository with an origin: the seed branch starts from main (so the evaluator stays main's), its base
# reverts the change's code and its head brings it back, Markdown is not replayed, a rename is a deletion and an
# addition, a merge commit is its whole pull request, and every refusal leaves the tree on a clean main without the
# branch; seed-check.sh confirms the seeder's one commit on that branch, in the change's files, and refuses anything
# else; seed-fold.sh makes the change and the defect one commit; nothing on the branch names the run (#0-177); and
# seed-record.sh appends a run's row to the gitignored .ai/runs/benchmark/results.md, which leaves main clean.
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
   "$SRC"/scripts/factory/seed-check.sh "$SRC"/scripts/factory/seed-fold.sh "$SRC"/scripts/factory/seed-record.sh scripts/factory/
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
SF=scripts/factory/seed-fold.sh
SR=scripts/factory/seed-record.sh
# Branch names as seed-bugs.js derives them (FNV-1a of "<pattern>\n<run id>"); the scripts take them as given.
B1=review/0684d0a2 B2=review/0584cf0f B3=review/11111111 B4=review/22222222 B9=review/99999999
on_clean_main() { [ "$(git rev-parse --abbrev-ref HEAD)" = main ] && [ -z "$(git status --porcelain)" ]; }
# refused <name> <reason regex> <args...>: ok false with that reason, and the tree left on a clean main, no seed branch.
refused() {
    local name=$1 re=$2 out; shift 2
    out=$("$SB" "$@")
    if jq -e --arg re "$re" '.ok == false and (.reason | test($re))' <<<"$out" >/dev/null && on_clean_main \
       && [ -z "$(git branch --list 'review/*')" ]; then ok "$name"
    else fail "$name: $out (on $(git rev-parse --abbrev-ref HEAD), branches: $(git branch --list 'review/*' | tr '\n' ' '))"; fi
}

# reason <name> <reason regex> <args...>: ok false with that reason (for the cases set up off a clean main).
reason() {
    local name=$1 re=$2 out; shift 2
    out=$("$SB" "$@")
    if jq -e --arg re "$re" '.ok == false and (.reason | test($re))' <<<"$out" >/dev/null; then ok "$name"; else fail "$name: $out"; fi
}

echo "seed-branch.sh: what it refuses"
for name in seed/P-10-r1 review/0684d0a review/0684d0a2x 'review/0684d0a2;x' review/ABCDEF12 review/../x; do
    refused "a branch name that is not review/<8 hex>: $name" "not a seed branch name" "$name" "$change"
done
refused "bad commit" "not a commit" "$B1" HEAD
refused "unknown commit" "no such commit" "$B1" deadbeef
refused "a change that touches the evaluator" "touches the evaluator \\(\\.claude/x" "$B1" "$evaluator_change"
refused "a change to Markdown only" "no code to replay" "$B1" "$md_only"
refused "a change that only deletes code" "nothing to plant in" "$B1" "$delete_change"
git switch -q -c other && reason "not on main" "not on main" "$B1" "$change"; git switch -q main && git branch -q -D other
printf 'dirt\n' > svc/A.java; out=$("$SB" "$B1" "$change")
if jq -e '.ok == false and (.reason | test("not clean"))' <<<"$out" >/dev/null; then ok "a dirty tree"; else fail "a dirty tree: $out"; fi
git checkout -q -- svc/A.java
# The tracked results.md is changed only by a series' pull request now (#0-177): changed by hand, it is dirt like any other.
mkdir -p .ai/audit/benchmark && printf '| row |\n' > .ai/audit/benchmark/results.md
reason "an uncommitted results.md is not clean" "^the working tree is not clean$" "$B1" "$change"
rm -rf .ai/audit
mkdir -p .ai/runs && echo "R1 $(git rev-parse HEAD)" > .ai/runs/LOCK
refused "an autopilot run holds the lock" "holds the lock" "$B1" "$change"
rm -f .ai/runs/LOCK
printf 'local\n' > svc/C.java && git add svc/C.java && git commit -qm local
refused "main ahead of origin/main" "not at origin/main" "$B1" "$change"
git reset -q --hard origin/main
# A protected-paths.md whose Owner-only list cannot be read (its markers gone) refuses, even with build-config intact.
sed -i.bak -e '/protected:start/d' -e '/protected:end/d' .ai/rules/protected-paths.md && rm -f .ai/rules/protected-paths.md.bak
git commit -qam "chore: markers lost" && git update-ref refs/remotes/origin/main HEAD
refused "an Owner-only list that cannot be read" "cannot read the protected paths .*: protected" "$B1" "$evaluator_change"
git reset -q --hard HEAD~1 && git update-ref refs/remotes/origin/main HEAD

echo "seed-branch.sh: the branch"
# The last run's row, in the gitignored .ai/runs/benchmark/ (seed-record.sh): main stays clean, the next run starts.
mkdir -p .ai/runs/benchmark && printf '| r0 | P-09 | ARC-02 | architecture | yes | yes | — | — |\n' > .ai/runs/benchmark/results.md
reflog_before=$(git reflog show --format='%H %gs' HEAD)   # the owner's HEAD reflog, which the fold must keep
out=$("$SB" "$B1" "$change")
if jq -e --arg b "$B1" '.ok == true and .branch == $b and .files == ["svc/A.java"]' <<<"$out" >/dev/null; then ok "made, with the change's code files only, a row in .ai/runs/ or not"
else fail "made: $out"; fi
base=$(jq -r .base <<<"$out"); head=$(jq -r .head <<<"$out")
if [ "$(git rev-parse --abbrev-ref HEAD)" = "$B1" ]; then ok "checked out"; else fail "not checked out"; fi
if ! git ls-tree -r --name-only HEAD | grep -q '^\.ai/runs/'; then ok "the row in .ai/runs/ is not on the branch"; else fail "a row rode onto the branch"; fi
if [ "$(git rev-parse "$base^")" = "$(git rev-parse main)" ]; then ok "starts from main"; else fail "does not start from main"; fi
if [ "$(git show "$base:svc/A.java")" = v1 ]; then ok "the base has the change's code reverted"; else fail "base A.java: $(git show "$base:svc/A.java")"; fi
if [ "$(git show "$head:svc/A.java")" = v2 ]; then ok "the head has it back"; else fail "head A.java"; fi
if [ "$(git show "$head:BACKLOG.md")" = "backlog 3" ]; then ok "Markdown is main's, not replayed"; else fail "BACKLOG.md was replayed"; fi
if [ "$(git show "$head:.claude/x")" = "$(git show main:.claude/x)" ]; then ok "the evaluator is main's"; else fail ".claude changed"; fi
if [ "$(git log -1 --format=%s "$head")" = "fix(svc): the change under test" ]; then ok "the head carries the change's subject"; else fail "subject: $(git log -1 --format=%s "$head")"; fi
if [ "$(git log -1 --format=%B "$base")" = 'Revert "fix(svc): the change under test"' ]; then ok "the base says what git revert says, and no more"
else fail "base message: $(git log -1 --format=%B "$base")"; fi

echo "seed-check.sh: the tree the panel runs on"
check() {   # check <name> <reason regex | ok> <args...>
    local name=$1 re=$2 out; shift 2
    out=$("$SC" "$@")
    if [ "$re" = ok ]; then
        if jq -e '.ok == true and .files == ["svc/A.java"]' <<<"$out" >/dev/null; then ok "$name"; else fail "$name: $out"; fi
    elif jq -e --arg re "$re" '.ok == false and (.reason | test($re))' <<<"$out" >/dev/null; then ok "$name"
    else fail "$name: $out"; fi
}
check "no seeder's commit yet" "not the one commit on top" "$B1" "$base" "$head"
printf 'v2 planted\n' > svc/A.java && git commit -qam "refactor(svc): simplify"
check "the seeder's one commit, in the change's file" ok "$B1" "$base" "$head"
check "bad base" "not a base commit" "$B1" abc "$head"
check "a bad branch name" "not a seed branch name" seed/P-10-r1 "$base" "$head"
check "a base that is not under the head" "is not the commit on top of" "$B1" "$(git rev-parse main)" "$head"
check "another run's branch" "not on $B9" "$B9" "$base" "$head"
printf 'more\n' >> svc/A.java
check "changes left uncommitted" "not clean" "$B1" "$base" "$head"
git checkout -q -- svc/A.java
printf 'again\n' >> svc/A.java && git commit -qam "refactor(svc): again"
check "two commits on top" "not the one commit on top" "$B1" "$base" "$head"
git reset -q --hard "$head" && printf 'z\n' > svc/Z.java && git add svc/Z.java && git commit -qm "refactor(svc): add Z"
check "a file outside the change" "outside the change: svc/Z.java" "$B1" "$base" "$head"
git reset -q --hard "$head" && printf 'w\n' > .claude/x && git commit -qam "refactor(svc): tweak"
check "the evaluator, outside the change" "outside the change: \\.claude/x" "$B1" "$base" "$head"
git reset -q --hard "$head"

echo "seed-fold.sh: the change and the defect as one commit"
fold() {   # fold <name> <reason regex> <args...>: ok false with that reason, the branch where it was
    local name=$1 re=$2 out before; shift 2
    before=$(git rev-parse "$B1")
    out=$("$SF" "$@")
    if jq -e --arg re "$re" '.ok == false and (.reason | test($re))' <<<"$out" >/dev/null && [ "$(git rev-parse "$B1")" = "$before" ]; then ok "$name"
    else fail "$name: $out"; fi
}
printf 'v2 planted\n' > svc/A.java && git commit -qam "refactor(svc): simplify"
seeded=$(git rev-parse HEAD)
fold "a bad branch name" "not a seed branch name" seed/P-10-r1 "$base" "$head" "$seeded"
fold "a bad commit" "not a commit" "$B1" "$base" "$head" HEAD
fold "not the checked commit" "not the checked commit" "$B1" "$base" "$head" "$head"
fold "a head not on the base" "is not the commit on top of" "$B1" "$head" "$head" "$seeded"
printf 'more\n' >> svc/A.java
fold "changes left uncommitted" "not clean" "$B1" "$base" "$head" "$seeded"
git checkout -q -- svc/A.java
fold "not on the branch" "on $B1, not on $B9" "$B9" "$base" "$head" "$seeded"
out=$("$SF" "$B1" "$base" "$head" "$seeded")
folded=$(jq -r .head <<<"$out")
if jq -e '.ok == true' <<<"$out" >/dev/null && [ "$(git rev-parse HEAD)" = "$folded" ] && [ "$(git rev-parse --abbrev-ref HEAD)" = "$B1" ]; then
    ok "folded, the branch at the new commit"; else fail "fold: $out"; fi
if [ "$(git rev-parse "$folded^")" = "$base" ] && [ "$(git rev-list --count "$base..HEAD")" = 1 ]; then ok "one commit on the base"
else fail "commits on the base: $(git rev-list --count "$base..HEAD")"; fi
if [ "$(git rev-parse "$folded^{tree}")" = "$(git rev-parse "$seeded^{tree}")" ] && [ -z "$(git status --porcelain)" ]; then
    ok "the seeded tree, and a clean working tree"; else fail "tree or status after the fold"; fi
if [ "$(git log -1 --format=%B "$folded")" = "fix(svc): the change under test" ]; then ok "under the change's subject only"
else fail "folded message: $(git log -1 --format=%B "$folded")"; fi
# What a reviewer reads of the branch: its name and every message from main on. None names the run or the planting.
told="$B1 $(git log --format=%B "$(git rev-parse main)..HEAD")"
if ! grep -qiE 'seed|plant|benchmark|P-[0-9]|simplify' <<<"$told"; then ok "nothing on the branch names the run (#0-177)"; else fail "a tell: $told"; fi
# Nor its reflogs (review round 1 of #0-177): `git log -g` and `HEAD@{n}` showed the seeder's commit alone.
branch_log=$(git reflog show --format=%H "$B1" 2>/dev/null)
head_log=$(git reflog show --format=%H HEAD)
if ! grep -qxF -e "$seeded" -e "$head" <<<"$branch_log$head_log"; then ok "no reflog names the seeder's commit or the change without it"
else fail "a reflog still names them: branch [$branch_log] HEAD [$head_log]"; fi
if [ "$(git reflog show --format='%H %gs' HEAD | tail -n +2)" = "$reflog_before" ] \
   && [ "$(git reflog show --format=%gs -n 1 HEAD)" = "checkout: moving from main to $B1" ]; then
    ok "the owner's HEAD reflog is kept, below the run's switch"
else fail "HEAD reflog: $(git reflog show --format=%gs HEAD | head -5 | tr '\n' '|')"; fi
git reset -q --hard "$head"
git switch -q main
check "not on the seed branch" "on main, not on $B1" "$B1" "$base" "$head"
reason "the branch exists already" "exists already" "$B1" "$change"
if [ "$(git rev-parse "$B1")" = "$head" ] && on_clean_main; then ok "an existing branch is left as it was"; else fail "an existing branch was touched"; fi
git branch -D -q "$B1"

echo "seed-branch.sh: renames, deletions and merge commits"
out=$("$SB" "$B3" "$rename_change")
if jq -e '.ok == true and .files == ["svc/C.java"]' <<<"$out" >/dev/null; then ok "a rename: only the file that is left to plant in"
else fail "rename: $out"; fi
base=$(jq -r .base <<<"$out"); head=$(jq -r .head <<<"$out")
if git cat-file -e "$base:svc/B.java" && git cat-file -e "$base:svc/D.java" && ! git cat-file -e "$base:svc/C.java" 2>/dev/null \
   && ! git cat-file -e "$head:svc/B.java" 2>/dev/null && ! git cat-file -e "$head:svc/D.java" 2>/dev/null; then
    ok "the base has B and D back, the head moves B to C and drops D"
else fail "rename base/head trees"; fi
printf 'b again\n' > svc/B.java && git add svc/B.java && git commit -qm "refactor(svc): B back"
check "a file the change deleted, brought back" "outside the change: svc/B.java" "$B3" "$base" "$head"
git reset -q --hard "$head"
# A seeder that switched away and back: the newest switch is not the run's first, its commit stays below it. Refused.
printf 'c planted\n' > svc/C.java && git commit -qam "refactor(svc): C" && seeded=$(git rev-parse HEAD)
git switch -q main && git switch -q "$B3"
out=$("$SF" "$B3" "$base" "$head" "$seeded")
if jq -e '.ok == false and (.reason | test("still names the seeder"))' <<<"$out" >/dev/null; then ok "a seeder that switched away and back"
else fail "switched away and back: $out"; fi
# A HEAD reflog without the run's switch (expired, or off): refused before anything moves.
git reset -q --hard "$seeded" 2>/dev/null; git update-ref "refs/heads/$B3" "$seeded"
git reflog expire --expire=now HEAD
out=$("$SF" "$B3" "$base" "$head" "$seeded")
if jq -e '.ok == false and (.reason | test("does not show the switch"))' <<<"$out" >/dev/null && [ "$(git rev-parse "$B3")" = "$seeded" ]; then
    ok "no switch in the HEAD reflog: refused, the branch where it was"; else fail "no switch: $out"; fi
git reset -q --hard "$head"
git switch -q main && git branch -D -q "$B3"
out=$("$SB" "$B4" "$merge_change")
if jq -e '.ok == true and .files == ["svc/M.java"]' <<<"$out" >/dev/null; then ok "a merge commit: its pull request's files"; else fail "merge: $out"; fi
base=$(jq -r .base <<<"$out"); head=$(jq -r .head <<<"$out")
if [ "$(git show "$base:svc/M.java")" = m1 ] && [ "$(git show "$head:svc/M.java")" = m2 ]; then ok "the merge's change reverted, then back"
else fail "merge base/head"; fi
if [ "$(git log -1 --format=%s "$head")" = "feat(svc): the merged change" ]; then ok "under its pull request's subject"
else fail "merge subject: $(git log -1 --format=%s "$head")"; fi
git switch -q main && git branch -D -q "$B4"

echo "seed-branch.sh: a change that no longer applies"
printf 'v3\n' > svc/A.java && git add -A && git commit -qm "fix(svc): later" && git push -q origin main 2>/dev/null && git fetch -q origin
refused "the change's code changed since" "no longer applies" "$B2" "$change"

echo "seed-record.sh: a run's row in .ai/runs/benchmark/results.md"
rows=.ai/runs/benchmark/results.md
rm -rf .ai/runs/benchmark
record() {   # record <name> <reason regex | ok> <args...>
    local name=$1 re=$2 out; shift 2
    out=$("$SR" "$@")
    if [ "$re" = ok ]; then
        if jq -e --arg f "$rows" '.appended == true and .file == $f' <<<"$out" >/dev/null; then ok "$name"; else fail "$name: $out"; fi
    elif jq -e --arg re "$re" '.appended == false and (.reason | test($re))' <<<"$out" >/dev/null; then ok "$name"
    else fail "$name: $out"; fi
}
record "a caught run" ok r1 P-10 PERF-01 performance yes yes performance,general,docs -
record "a missed run, a reviewer without an answer" ok r2 P-11 PERF-23 performance no no - security,docs
expected='| Run | Pattern | Rule | Expected | Caught by expected | Blocking | Also caught by | No answer |
|---|---|---|---|---|---|---|---|
| r1 | P-10 | PERF-01 | performance | yes | yes | general, docs | — |
| r2 | P-11 | PERF-23 | performance | no | no | — | security, docs |'
if [ "$(cat "$rows")" = "$expected" ]; then ok "the table: a header once, one row per run"; else fail "rows: $(cat "$rows")"; fi
if on_clean_main; then ok "main stays clean: the rows are gitignored"; else fail "the rows dirty main: $(git status --porcelain)"; fi
# A run id is matched in the first column only (review round 1): `security` is a dimension in r2's row, not its id.
record "a run id that is also a dimension" ok security P-12 PERF-02 performance yes no - -
record "a run id recorded already" "r1 is in \\.ai/runs/benchmark/results\\.md already" r1 P-10 PERF-01 performance yes yes - -
mkdir -p .ai/audit/benchmark && printf '| r7 | P-12 | PERF-02 | performance | yes | yes | — | — |\n' > .ai/audit/benchmark/results.md
record "a run id landed already" "r7 is in \\.ai/audit/benchmark/results\\.md already" r7 P-12 PERF-02 performance yes yes - -
# A long tracked file, the id in its first row: `cut | grep -q` would stop early, SIGPIPE cut, and pass (round 3).
{ printf '| r8 | P-12 | PERF-02 | performance | yes | yes | — | — |\n'
  for i in $(seq 1 50000); do printf '| x%s | P-01 | SEC-06 | security | yes | yes | — | — |\n' "$i"; done; } > .ai/audit/benchmark/results.md
record "a run id landed already, in a long file" "r8 is in \\.ai/audit/benchmark/results\\.md already" r8 P-12 PERF-02 performance yes yes - -
rm -rf .ai/audit
record "a bad run id" "not a run id" "r 1" P-10 PERF-01 performance yes yes - -
record "a bad pattern" "not a pattern id" P-1x r3 PERF-01 performance yes yes - -
record "a bad rule" "not a rule id" r3 P-10 "PERF-01'" performance yes yes - -
record "a rule with two letters after the dash" "not a rule id" r3 P-10 FAC-AB02 architecture yes yes - -
record "an unknown dimension" "not a dimension" r3 P-10 PERF-01 frontend yes yes - -
record "not yes or no" "not yes or no" r3 P-10 PERF-01 performance true yes - -
for list in 'general,frontend' 'general;rm' '*' 'general,' ''; do
    record "a bad list: '$list'" "not a list of dimensions" r3 P-10 PERF-01 performance yes yes "$list" -
    record "a bad no-answer list: '$list'" "not a list of dimensions" r3 P-10 PERF-01 performance yes yes - "$list"
done
record "the wrong number of arguments" "usage" r3 P-10 PERF-01 performance yes yes -
if [ "$(grep -c '^| [rs]' "$rows")" = 3 ]; then ok "a refused row writes nothing"; else fail "rows after refusals: $(cat "$rows")"; fi
# A dimension and a rule id's name may hold a digit (k8s, K8S-08: #0-115's k8s.md): as expected, in "Also caught by" and in "No answer".
record "a k8s run" ok r9 P-20 K8S-08 k8s yes yes k8s,general security,k8s
if [ "$(tail -1 "$rows")" = '| r9 | P-20 | K8S-08 | k8s | yes | yes | general | security, k8s |' ]; then ok "the k8s row"
else fail "k8s row: $(tail -1 "$rows")"; fi
# A factory id has a letter after the dash (FAC-A02: #0-115's F1, #0-185).
record "a factory run" ok r10 P-23 FAC-A02 architecture yes yes architecture,security -
if [ "$(tail -1 "$rows")" = '| r10 | P-23 | FAC-A02 | architecture | yes | yes | security | — |' ]; then ok "the factory row"
else fail "factory row: $(tail -1 "$rows")"; fi

if [ "$failures" -gt 0 ]; then echo "$failures seed-branch/seed-check/seed-fold/seed-record test(s) failed"; exit 1; fi
echo "All seed-branch, seed-check, seed-fold and seed-record tests passed."
