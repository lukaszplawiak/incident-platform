#!/usr/bin/env bash
# ============================================================
# Tests for scripts/factory/start-item.sh (backlog #0-209), in a temporary repository: an item starts on its branch from
# the base commit, with the whole work template copied (a file added to the template too), progress.md titled and
# holding the start's entry, only the item's own status field changed, and one commit; every refusal (a bad argument,
# a tree that is not a clean main at the base, a branch or work directory that exists, an item that is not ready or not
# Open) leaves the tree on a clean main, with no branch and no work directory.
# Run: .github/scripts/test-start-item.sh   (also run by .github/workflows/factory-guards.yml)
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
mkdir -p scripts/factory .ai/work/_template/sub .ai/rules
cp "$SRC"/scripts/factory/_common.sh "$SRC"/scripts/factory/_backlog.sh "$SRC"/scripts/factory/_protected.sh \
   "$SRC"/scripts/factory/start-item.sh scripts/factory/
cp "$SRC"/.ai/rules/protected-paths.md .ai/rules/
cp "$SRC"/.ai/work/_template/*.md .ai/work/_template/
printf 'nested\n' > .ai/work/_template/sub/extra.md          # a file the picker's listed cp would have missed
printf '.ai/runs/\n' > .gitignore
cat > BACKLOG.md <<'EOF'
# Backlog

### 0-25. The queue's version is lost

**Type:** bug · **Priority:** Medium · **Status:** Open (found in review). Text.
**Autopilot:** ready · **Risk:** low · **Complexity:** low · **Depends on:** —

Its text quotes the fields, which count for nothing (review round 1 of #0-209): an item `**Autopilot:** ready` and
`**Status:** Open` on that commit.

### 0-250. A later item, whose number starts with 0-25: its status must stay

**Type:** bug · **Priority:** Low · **Status:** Open.
**Autopilot:** ready · **Risk:** low

### 0-26. Not ready

**Type:** ci · **Priority:** Low · **Status:** Open.
**Autopilot:** human-only · **Risk:** low

Its text quotes **Autopilot:** ready, which is not its field.

### 0-27. Already in progress

**Type:** ci · **Priority:** Low · **Status:** In progress — autopilot run 20261101-000000.
**Autopilot:** ready · **Risk:** low

### 0-28. Twice in the backlog

**Type:** ci · **Priority:** Low · **Status:** Open.
**Autopilot:** ready · **Risk:** low

### 0-28. Twice in the backlog, again

**Type:** ci · **Priority:** Low · **Status:** Open.
**Autopilot:** ready · **Risk:** low

### 0-30. A design item, ready by mistake

**Type:** design · **Priority:** Low · **Status:** Open.
**Autopilot:** ready · **Risk:** low

### 0-31. A misspelt type

**Type:** secruity · **Priority:** Low · **Status:** Open.
**Autopilot:** ready · **Risk:** low

### 0-29. Its own sub-heading first, and wide spacing

### Notes

**Type:** ci · **Priority:** Low · **Status:**   Open. Spaced as backlog_json still reads it.
**Autopilot:** ready · **Risk:** low

## Later

**Type:** ci · **Priority:** Low · **Status:** Open. A line after the section ends, which no item owns.
EOF
git add -A && git commit -qm base
base=$(git rev-parse HEAD)
RUN=20261109-120000
S=scripts/factory/start-item.sh

# clean_main <name> — the tree is a clean main at the base, with no item branch and no work directory but the template.
clean_main() {
    local why="" stray
    [ "$(git rev-parse --abbrev-ref HEAD)" = main ] || why="not on main"
    [ -z "$(git status --porcelain --untracked-files=all)" ] || why="$why; the tree is dirty"
    [ "$(git rev-parse HEAD)" = "$base" ] || why="$why; main moved"
    [ -z "$(git branch --list '*/0-*')" ] || why="$why; a branch was left: $(git branch --list '*/0-*' | tr -d ' ')"
    stray=$(find .ai/work -mindepth 1 -maxdepth 1 ! -name _template)
    [ -z "$stray" ] || why="$why; a work directory was left: $(tr '\n' ' ' <<<"$stray")"
    if [ -z "$why" ]; then ok "$1: a clean main is left"; else fail "$1: ${why#; }"; fi
}
# refusal <name> <reason fragment> <args...> — the script refuses with that reason.
refusal() {
    local name=$1 want=$2 out; shift 2
    out=$("$S" "$@")
    if jq -e --arg w "$want" '.ok == false and (.reasons | length == 1) and (.reasons[0] | contains($w))' <<<"$out" >/dev/null; then
        ok "$name"
    else fail "$name: expected a refusal containing \"$want\", got $out"; fi
}
# refused <name> <reason fragment> <args...> — the same, from a clean main, which it leaves as it found it.
refused() { refusal "$@"; clean_main "$1"; }

echo "start-item.sh (backlog #0-209)"

refused "no arguments"            "usage: start-item.sh"
refused "a short sha"             "not a commit sha"           "${base:0:12}" 0-25 fix/0-25-queue "$RUN"
refused "an item with #"          "not an item id"             "$base" '#0-25' fix/0-25-queue "$RUN"
refused "another item's branch"   "not a branch of #0-25"      "$base" 0-25 fix/0-26-queue "$RUN"
refused "a branch with a space"   "not a branch of #0-25"      "$base" 0-25 'fix/0-25-a b' "$RUN"
refused "a branch with .."        "not a branch of #0-25"      "$base" 0-25 'fix/0-25-a/../../x' "$RUN"
refused "a name git refuses"       "cannot create the branch fix/0-25-a..b" "$base" 0-25 'fix/0-25-a..b' "$RUN"
if "$S" "$base" 0-25 'fix/0-25-a..b' "$RUN" | grep -q 'was left'; then fail "a name git refuses: the reason says a branch was left"
else ok "a name git refuses: the reason names no branch left"; fi
refused "a bad run id"            "not a run id"               "$base" 0-25 fix/0-25-queue 'x; rm -rf .'
refused "no such section"         "has 0 sections ### 0-99."   "$base" 0-99 fix/0-99-none "$RUN"
refused "not ready, quoting ready" "is not **Autopilot:** ready on the base commit (human-only)" "$base" 0-26 ci/0-26-not-ready "$RUN"
refused "not Open"                "status is not Open on the base commit: In progress" "$base" 0-27 ci/0-27-in-progress "$RUN"
refused "two sections"            "has 2 sections ### 0-28."   "$base" 0-28 ci/0-28-two "$RUN"
# The prefix is the one the item's **Type:** gives, read from the base commit (backlog #0-195).
refused "a prefix its type does not give" "the branch of #0-25 (Type: bug) is fix/0-25-<slug>, not feat/0-25-queue" "$base" 0-25 feat/0-25-queue "$RUN"
refused "a design item"           "#0-30's type has no branch prefix on the base commit (Type: design)" "$base" 0-30 feat/0-30-x "$RUN"
refused "a misspelt type"         "#0-31's type has no branch prefix on the base commit (Type: secruity)" "$base" 0-31 fix/0-31-x "$RUN"

# Each of these states is made on purpose, and must be left as it is: nothing is created, nothing is undone.
printf 'x\n' > stray.txt
refusal "an untracked file"       "the working tree is not clean" "$base" 0-25 fix/0-25-queue "$RUN"
if [ -e stray.txt ] && [ -z "$(git branch --list 'fix/0-25-*')" ]; then
    ok "an untracked file: kept, no branch made"
else fail "an untracked file: removed, or a branch made"; fi
rm stray.txt
git switch -q -c other
refusal "not on main"             "not on main"                "$base" 0-25 fix/0-25-queue "$RUN"
if [ "$(git rev-parse --abbrev-ref HEAD)" = other ]; then
    ok "not on main: left on its branch"
else fail "not on main: moved to $(git rev-parse --abbrev-ref HEAD)"; fi
git switch -q main && git branch -q -D other
git commit -q --allow-empty -m moved
refusal "main moved since the preflight" "is not at the base commit" "$base" 0-25 fix/0-25-queue "$RUN"
git reset -q --hard "$base"
git branch -q fix/0-25-old "$base"
refusal "the branch exists"       "the branch fix/0-25-old exists already" "$base" 0-25 fix/0-25-old "$RUN"
if [ -n "$(git branch --list fix/0-25-old)" ]; then
    ok "the branch exists: kept for the owner"
else fail "the branch exists: deleted"; fi
git branch -q -D fix/0-25-old
mkdir -p .ai/work/0-25 && printf 'left\n' > .ai/work/0-25/progress.md && git add -A && git commit -qm "a leftover" && left=$(git rev-parse HEAD)
refusal "the work directory exists" ".ai/work/0-25 exists already" "$left" 0-25 fix/0-25-queue "$RUN"
if [ "$(cat .ai/work/0-25/progress.md)" = left ] && [ -z "$(git branch --list 'fix/0-25-*')" ]; then
    ok "the work directory exists: kept, no branch made"
else fail "the work directory exists: changed, or a branch made"; fi
git reset -q --hard "$base"
clean_main "the setup cases"

# A failure after the branch exists: the work directory cannot be created (the directory's mode is not tracked, so the
# tree stays clean). The branch goes, and the tree is back on a clean main.
chmod 555 .ai/work
if [ ! -w .ai/work ]; then
    refused "a failure after the branch exists" "cannot create .ai/work/0-25" "$base" 0-25 fix/0-25-queue "$RUN"
else
    ok "a failure after the branch exists: skipped (running as root, every directory is writable)"
fi
chmod 755 .ai/work
# The same after the directory exists: a `cp` that fails half-way (a stand-in first on PATH) leaves a partial copy,
# which goes with the branch.
mkdir -p "$WORK/failing-cp"
printf '#!/bin/sh\nmkdir -p .ai/work/0-25 && : > .ai/work/0-25/partial.md\nexit 1\n' > "$WORK/failing-cp/cp"
chmod +x "$WORK/failing-cp/cp"
out=$(PATH="$WORK/failing-cp:$PATH" "$S" "$base" 0-25 fix/0-25-queue "$RUN")
if jq -e '.ok == false and (.reasons[0] | contains("cannot copy .ai/work/_template to .ai/work/0-25"))' <<<"$out" >/dev/null; then
    ok "a cp that fails after the branch exists"
else fail "a cp that fails after the branch exists: $out"; fi
clean_main "a cp that fails after the branch exists"

# An item read as backlog_json reads it: a sub-heading of its own before its Type line, spacing before Open.
out=$("$S" "$base" 0-29 ci/0-29-spaced "$RUN")
if jq -e '.ok == true' <<<"$out" >/dev/null && [ "$(git diff "$base" HEAD -- BACKLOG.md | grep -c '^+[^+]')" = 1 ] \
    && git diff "$base" HEAD -- BACKLOG.md | grep -qF "+**Type:** ci · **Priority:** Low · **Status:** In progress — autopilot run $RUN. Spaced"; then
    ok "starts an item read as backlog_json reads it (sub-heading, spacing)"
else fail "an item with a sub-heading and spacing: $out $(git diff "$base" HEAD -- BACKLOG.md | grep '^[-+][^-+]')"; fi
git switch -q main && git branch -q -D ci/0-29-spaced && rm -rf .ai/work/0-29
clean_main "after the spaced item"

# The item starts.
out=$("$S" "$base" 0-25 fix/0-25-queue-version "$RUN")
if jq -e '.ok == true and .branch == "fix/0-25-queue-version" and .workDir == ".ai/work/0-25" and (.head | test("^[0-9a-f]{40}$"))' <<<"$out" >/dev/null; then
    ok "starts: the answer"
else fail "starts: the answer is $out"; fi
if [ "$(git rev-parse --abbrev-ref HEAD)" = fix/0-25-queue-version ]; then
    ok "starts: on the item's branch"
else fail "starts: on $(git rev-parse --abbrev-ref HEAD)"; fi
if [ "$(git rev-parse HEAD^)" = "$base" ] && [ "$(git rev-parse HEAD)" = "$(jq -r .head <<<"$out")" ]; then
    ok "starts: one commit on the base, the one answered"
else fail "starts: the commits are $(git log --oneline "$base"..HEAD | tr '\n' ' ')"; fi
if [ -z "$(git status --porcelain --untracked-files=all)" ]; then
    ok "starts: a clean tree"
else fail "starts: the tree is dirty: $(git status --porcelain)"; fi
msg=$(git log -1 --format=%B)
if [ "$msg" = "$(printf 'chore(factory): start backlog #0-25\n\nAutopilot run %s.' "$RUN")" ]; then
    ok "starts: the commit message"
else fail "starts: the commit message is: $msg"; fi
files=$(git diff --name-only "$base" HEAD | sort | tr '\n' ' ')
if [ "$files" = "$(printf '%s ' .ai/work/0-25/handoff.md .ai/work/0-25/progress.md .ai/work/0-25/proofs.md .ai/work/0-25/sub/extra.md BACKLOG.md)" ]; then
    ok "starts: the whole template and the backlog, nothing else"
else fail "starts: the commit holds $files"; fi
for f in handoff.md proofs.md sub/extra.md; do
    if cmp -s ".ai/work/_template/$f" ".ai/work/0-25/$f"; then
        ok "starts: $f copied as it is"
    else fail "starts: $f differs from the template"; fi
done
first=$(head -n 1 .ai/work/0-25/progress.md)
if [ "$first" = "# #0-25 The queue's version is lost" ]; then
    ok "starts: progress.md's title"
else fail "starts: progress.md's title is $first"; fi
entry="- [start] started by start-item.sh; branch \`fix/0-25-queue-version\`; base \`$base\`"
if [ "$(tail -n 2 .ai/work/0-25/progress.md)" = "$(printf '%s\nNEXT: architect' "$entry")" ]; then
    ok "starts: the start's entry and NEXT: architect"
else fail "starts: progress.md ends with $(tail -n 2 .ai/work/0-25/progress.md)"; fi
if [ "$(tail -n +2 .ai/work/_template/progress.md)" = "$(sed -n "2,$(( $(wc -l <.ai/work/_template/progress.md) ))p" .ai/work/0-25/progress.md)" ]; then
    ok "starts: the rest of the template's progress.md kept"
else fail "starts: the template's progress.md was changed"; fi
diff=$(git diff "$base" HEAD -- BACKLOG.md | grep '^[-+][^-+]')
want=$(printf '%s\n%s' '-**Type:** bug · **Priority:** Medium · **Status:** Open (found in review). Text.' \
    "+**Type:** bug · **Priority:** Medium · **Status:** In progress — autopilot run $RUN (found in review). Text.")
if [ "$diff" = "$want" ]; then
    ok "starts: only the item's status field, not the text quoting it"
else fail "starts: the backlog diff is: $diff"; fi

# Started once, the same item is refused: its branch exists (and the script is not on main any more).
refused_again=$("$S" "$base" 0-25 fix/0-25-queue-version "$RUN")
if jq -e '.ok == false' <<<"$refused_again" >/dev/null; then
    ok "a second start is refused"
else fail "a second start: $refused_again"; fi
if [ "$(git rev-parse --abbrev-ref HEAD)" = fix/0-25-queue-version ]; then ok "a second start leaves the run's branch as it is"
else fail "a second start moved to $(git rev-parse --abbrev-ref HEAD)"; fi

if [ "$failures" -gt 0 ]; then echo "$failures start-item test(s) failed."; exit 1; fi
echo "All start-item tests passed."
