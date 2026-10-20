#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/secret-scan.sh (backlog #0-109): one temporary git repository per case, a base
# commit and the case's commits on top, and the script run on base..HEAD with the real betterleaks. Every
# failing case names the message it must give, so a case cannot pass on another refusal.
#
# The secret is a GitHub token betterleaks' default rule matches, built at run time from two halves: this
# file holds no string the scanner (or GitHub's push protection) would take for one.
#
# Needs the betterleaks the script runs: BETTERLEAKS=<path> (CI: secret-scan, the pinned one). Locally:
#   BETTERLEAKS=<path to betterleaks> .github/scripts/test-secret-scan.sh
# ============================================================
set -euo pipefail

BETTERLEAKS=${BETTERLEAKS:?BETTERLEAKS must name the betterleaks binary the script runs}
SCRIPT="$(cd "$(dirname "$0")" && pwd)/secret-scan.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
passed=0
n=0
dir=""
TOKEN="ghp_Zq7mT2vLx9Rk4Nw8""Hc3Bf6Yd1Sa5Pe0Ju2Gi" # gitleaks:allow: made up, never issued by GitHub (the cases need a match)

ok()   { echo "  ok: $1"; passed=$((passed+1)); }
fail() { echo "::error::$1"; failures=$((failures+1)); }

# fresh: a new repository with one clean commit; BASE is that commit.
fresh() {
    n=$((n+1)); dir="$WORK/case$n"
    mkdir -p "$dir"
    git -C "$dir" init -q
    git -C "$dir" config user.email test@example.com
    git -C "$dir" config user.name test
    echo "base" > "$dir/README"
    git -C "$dir" add README
    git -C "$dir" commit -qm base
    BASE=$(git -C "$dir" rev-parse HEAD)
}
# commit <file> <content> — writes the file (an empty content deletes it) and commits.
commit() {
    if [ -z "$2" ]; then git -C "$dir" rm -q "$1"; else printf '%s\n' "$2" > "$dir/$1"; git -C "$dir" add "$1"; fi
    git -C "$dir" commit -qm "change $1"
}
# expect <name> <0|1> <text the output must contain> [<from>] — runs the script on <from> (default BASE)..HEAD.
expect() {
    local name=$1 want=$2 text=$3 from=${4:-$BASE} out rc=0
    out=$(cd "$dir" && "$SCRIPT" "$BETTERLEAKS" "$from" HEAD 2>&1) || rc=$?
    if [ "$want" = 0 ] && [ "$rc" -ne 0 ]; then fail "$name: refused: $out"; return; fi
    if [ "$want" = 1 ] && [ "$rc" -eq 0 ]; then fail "$name: accepted: $out"; return; fi
    case "$out" in *"$text"*) ;; *) fail "$name: output does not contain '$text': $out"; return ;; esac
    case "$out" in *"$TOKEN"*) fail "$name: the output carries the secret: $out"; return ;; esac
    ok "$name"
}

echo "secret-scan.sh: what the range adds"
fresh; commit app.txt "nothing secret here"
expect "a clean range" 0 "No secret in the 1 commit(s)"
fresh; commit config.txt "token = $TOKEN"
expect "a secret added in the range, redacted, with its file and line" 1 "::error file=config.txt,line=1::github-pat in commit"
fresh; commit config.txt "token = $TOKEN"; commit config.txt ""
expect "a secret added and deleted in the range (in no tree)" 1 "github-pat in commit"
fresh; commit config.txt "token = $TOKEN"; BASE=$(git -C "$dir" rev-parse HEAD); commit app.txt "unrelated"
expect "a secret before the range is not this range's" 0 "No secret in the 1 commit(s)"
fresh; commit fixture.txt "token = $TOKEN # gitleaks:allow: a test fixture, revoked"
expect "a finding allowed on its line" 0 "No secret"
expect "an allowed line is named in a warning" 0 "::warning file=fixture.txt::commit"
fresh; commit fixture.txt "token = $TOKEN # betterleaks:allow: a test fixture"
expect "a finding allowed with betterleaks:allow, named in a warning" 0 "::warning file=fixture.txt::commit"
fresh; commit fixture.txt "token = $TOKEN # gitleaks:allow: a test fixture"; commit fixture.txt ""
expect "an allowed line deleted later is still named (it is in no final diff)" 0 "::warning file=fixture.txt::commit"
fresh; commit "C 0" "token = $TOKEN # gitleaks:allow: a test fixture"
expect "an allowed line in a file named like a commit line is named" 0 "::warning file=C 0::commit"
fresh; commit app.txt "nothing secret here"
out=$(cd "$dir" && "$SCRIPT" "$BETTERLEAKS" "$BASE" HEAD 2>&1) || true
case "$out" in *"::warning"*) fail "a range without an allow comment warns: $out" ;; *) ok "a range without an allow comment warns nothing" ;; esac

echo "secret-scan.sh: merge commits"
fresh; git -C "$dir" switch -qc side; commit README "side"; git -C "$dir" switch -q -
commit README "main side"; git -C "$dir" merge -q side >/dev/null 2>&1 || true
printf 'token = %s\n' "$TOKEN" > "$dir/README"; git -C "$dir" commit -qam "merge, the conflict resolved"
commit README "clean again"
expect "a secret written in a merge's conflict resolution, deleted later" 1 "github-pat in commit"
fresh; git -C "$dir" switch -qc side; commit README "side"; git -C "$dir" switch -q -
commit README "main side"; git -C "$dir" merge -q side >/dev/null 2>&1 || true
printf 'token = %s # gitleaks:allow: a test fixture\n' "$TOKEN" > "$dir/README"; git -C "$dir" commit -qam "merge, the conflict resolved"
merge=$(git -C "$dir" rev-parse HEAD); commit README "clean again"
expect "an allow comment written in a merge's conflict resolution names the merge" 0 "::warning file=README::commit ${merge:0:12}"

echo "secret-scan.sh: nothing in the repository changes the rules"
fresh; commit .gitleaks.toml "[allowlist]
paths = ['''.*''']"; commit config.txt "token = $TOKEN"
expect "a .gitleaks.toml at the root" 1 "::error file=.gitleaks.toml::.gitleaks.toml would replace"
fresh; commit .betterleaks.toml "[allowlist]
paths = ['''.*''']"
expect "a .betterleaks.toml at the root" 1 "::error file=.betterleaks.toml::.betterleaks.toml would replace"
fresh; commit config.txt "token = $TOKEN"
fp=$(cd "$dir" && "$BETTERLEAKS" git . --log-opts="$BASE..HEAD" --no-banner --log-level error --report-format json --report-path - 2>/dev/null | jq -r '.[0].Fingerprint' || true)
[ -n "$fp" ] && [ "$fp" != null ] || fail "no fingerprint to ignore: the case below would test nothing"
commit .gitleaksignore "$fp"
expect "a .gitleaksignore at the root" 1 "::error file=.gitleaksignore::.gitleaksignore would hide findings"
commit .gitleaksignore ""; commit .betterleaksignore "$fp"
expect "a .betterleaksignore at the root" 1 "::error file=.betterleaksignore::.betterleaksignore would hide findings"
fresh; commit config.txt "token = $TOKEN"
printf '[allowlist]\npaths = ['"'''.*'''"']\n' > "$WORK/open.toml"
out=$(cd "$dir" && GITLEAKS_CONFIG="$WORK/open.toml" BETTERLEAKS_CONFIG="$WORK/open.toml" "$SCRIPT" "$BETTERLEAKS" "$BASE" HEAD 2>&1) && rc=0 || rc=$?
if [ "$rc" -ne 0 ] && [[ "$out" == *"github-pat in commit"* ]]; then ok "a config variable from the environment is not read"; else fail "a config variable from the environment is read: $out"; fi

echo "secret-scan.sh: fails closed"
fresh; commit app.txt "x"
expect "an all-zero <from> (a new branch, a force-push)" 1 "no range to scan" 0000000000000000000000000000000000000000
expect "a <from> that is no commit" 1 "is not a commit of this clone" deadbeefdeadbeefdeadbeefdeadbeefdeadbeef
out=$(cd "$dir" && "$SCRIPT" /nonexistent/betterleaks "$BASE" HEAD 2>&1) && rc=0 || rc=$?
if [ "$rc" -ne 0 ] && [[ "$out" == *"is not an executable betterleaks"* ]]; then ok "a missing scanner"; else fail "a missing scanner: $out"; fi
printf '#!/bin/sh\nexit 2\n' > "$WORK/broken-scanner"; chmod +x "$WORK/broken-scanner"
out=$(cd "$dir" && "$SCRIPT" "$WORK/broken-scanner" "$BASE" HEAD 2>&1) && rc=0 || rc=$?
if [ "$rc" -ne 0 ] && [[ "$out" == *"betterleaks failed (exit 2)"* ]]; then ok "a scanner that fails"; else fail "a scanner that fails: $out"; fi
out=$(cd "$dir" && "$SCRIPT" "$BETTERLEAKS" "$BASE" 2>&1) && rc=0 || rc=$?
if [ "$rc" -eq 2 ] && [[ "$out" == *"usage:"* ]]; then ok "two arguments"; else fail "two arguments: $out"; fi

if [ "$failures" -gt 0 ]; then echo "$failures secret-scan case(s) failed"; exit 1; fi
echo "All $passed secret-scan cases passed."
