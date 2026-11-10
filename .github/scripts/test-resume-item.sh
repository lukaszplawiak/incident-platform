#!/usr/bin/env bash
# ============================================================
# Tests for scripts/factory/resume-item.sh (backlog #0-215), in a temporary repository: a run resumes on the item's
# existing branch, and every refusal (a bad argument, a tree that is not a clean main at the base, a branch that does
# not exist or that no run started) leaves the tree on a clean main.
# Run: .github/scripts/test-resume-item.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }

R=$WORK/repo
git init -q -b main "$R"
cd "$R" || exit 1
git config user.email t@t && git config user.name t
mkdir -p scripts/factory
cp "$SRC"/scripts/factory/_common.sh "$SRC"/scripts/factory/resume-item.sh scripts/factory/
printf '.ai/runs/\n' > .gitignore
printf '# Backlog\n' > BACKLOG.md
git add -A && git commit -qm base
base=$(git rev-parse HEAD)
# A branch an earlier run started (its work directory), and one that holds none.
git switch -q -c fix/0-25-queue-version
mkdir -p .ai/work/0-25 && printf '# #0-25 x\n' > .ai/work/0-25/progress.md
git add -A && git commit -qm start
started=$(git rev-parse HEAD)
git switch -q main
git branch fix/0-26-no-work-dir

run() { scripts/factory/resume-item.sh "$@"; }

# refused <name> <reason text> <args...> — answers ok:false with that reason, and the tree is as it was: on main at base,
# with the same status (a dirty tree stays as dirty as it was).
refused() {
    local name=$1 want=$2 out before; shift 2
    before=$(git status --porcelain --untracked-files=all)
    out=$(run "$@")
    if [ "$(jq -r .ok <<<"$out")" = false ] && jq -e --arg w "$want" '.reasons[0] | contains($w)' <<<"$out" >/dev/null \
       && [ "$(git rev-parse --abbrev-ref HEAD)" = main ] && [ "$(git rev-parse HEAD)" = "$base" ] \
       && [ "$(git status --porcelain --untracked-files=all)" = "$before" ]; then ok "$name"
    else fail "$name: expected a refusal naming '$want', the tree unchanged on main, got $out (on $(git rev-parse --abbrev-ref HEAD))"; fi
}

echo "resume-item (backlog #0-215)"
refused "no arguments"                     "usage: resume-item.sh"
refused "one argument too many"            "usage: resume-item.sh"         "$base" fix/0-25-queue-version extra
refused "a short sha"                      "not a commit sha"              "${base:0:12}" fix/0-25-queue-version
refused "main is not an item's branch"     "not an item's branch"          "$base" main
refused "an option, not a branch"          "not an item's branch"          "$base" -c
refused "a branch without an item"         "not an item's branch"          "$base" fix/queue-version
refused "a branch that does not exist"     "no local branch fix/0-27-x"    "$base" fix/0-27-x
refused "a branch no run started"          "holds no .ai/work/0-26/progress.md" "$base" fix/0-26-no-work-dir

printf 'x\n' > untracked.txt
refused "an untracked file"                "not clean"                     "$base" fix/0-25-queue-version
rm untracked.txt
printf 'changed\n' >> BACKLOG.md
refused "a changed tracked file"           "not clean"                     "$base" fix/0-25-queue-version
git checkout -q -- BACKLOG.md

other=$(git commit-tree "$base^{tree}" -p "$base" -m other)
refused "main not at the base"             "not at the base commit"        "$other" fix/0-25-queue-version

git switch -q fix/0-26-no-work-dir
out=$(run "$base" fix/0-25-queue-version)
if [ "$(jq -r .ok <<<"$out")" = false ] && jq -e '.reasons[0] | contains("not on main")' <<<"$out" >/dev/null \
   && [ "$(git rev-parse --abbrev-ref HEAD)" = fix/0-26-no-work-dir ]; then ok "not on main: refused, the tree stays"
else fail "not on main: got $out"; fi
git switch -q main

out=$(run "$base" fix/0-25-queue-version)
if [ "$(jq -c . <<<"$out")" = "$(jq -nc --arg h "$started" '{ok: true, branch: "fix/0-25-queue-version", item: "0-25", head: $h}')" ] \
   && [ "$(git rev-parse --abbrev-ref HEAD)" = fix/0-25-queue-version ] && [ -z "$(git status --porcelain --untracked-files=all)" ]; then
    ok "the run's branch: switched, item and head answered"
else fail "the run's branch: got $out (on $(git rev-parse --abbrev-ref HEAD))"; fi

if [ "$failures" -gt 0 ]; then echo "$failures resume-item test(s) failed."; exit 1; fi
echo "All resume-item tests passed."
