#!/usr/bin/env bash
# ============================================================
# Tests for .github/scripts/check-escape-markers.sh (backlog #0-128), in a temporary repository with a copy of
# the check and of scripts/factory/_marker.sh.
# Run: .github/scripts/test-escape-markers.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
# The cases are Markdown lines: backticks and `$` in single quotes are literal on purpose (backlog #0-149).
# shellcheck disable=SC2016
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }

mkdir -p "$WORK/repo/scripts/factory" "$WORK/repo/.github/scripts"
cp "$SRC/scripts/factory/_marker.sh" "$WORK/repo/scripts/factory/"
cp "$SRC/.github/scripts/check-escape-markers.sh" "$WORK/repo/.github/scripts/"
cd "$WORK/repo" || exit 1
git init -q -b main
check() { .github/scripts/check-escape-markers.sh "$@" 2>&1; }

expect_pass() {   # expect_pass <name> <line...>: a file of these lines passes
    local name=$1; shift
    printf '%s\n' "# x" "$@" > BACKLOG.md
    if out=$(check BACKLOG.md); then ok "$name"; else fail "$name: refused: $out"; fi
}
expect_fail() {   # expect_fail <name> <line>: the line, as line 2 of a file, fails with its line number
    printf '%s\n' "# x" "$2" > BACKLOG.md
    if out=$(check BACKLOG.md); then fail "$1: passed"
    elif printf '%s' "$out" | grep -q '::error file=BACKLOG.md,line=2::'; then ok "$1"
    else fail "$1: no line number: $out"; fi
}

echo "check-escape-markers: well-formed and prose"
expect_pass "a real marker"                    '**Type:** bug · **Fixes:** #0-8 · **Escaped from:** review-general'
expect_pass "the convention, a placeholder"   'An item gets `**Fixes:** #0-N · **Escaped from:** <stage>` on the same line.'
expect_pass "prose in backticks"              'The audits count `**Escaped from:**` markers.'
expect_pass "the marker at the end of a line" 'counts only a marker in the form `**Fixes:** #0-N · **Escaped from:**'
expect_pass "a stage the parser cannot read is the audit's case, not CI's" '**Fixes:** #0-12 · **Escaped from:** Review-General'
expect_pass "no marker at all"                'Plain text.'
expect_pass "a sentence, no colon"            'The defect escaped from review into main.'

echo "check-escape-markers: malformed"
expect_fail "no Fixes part"                   '**Type:** bug · **Escaped from:** plan'
expect_fail "a placeholder item with a stage" '**Fixes:** #0-N · **Escaped from:** plan'
expect_fail "Fixes after Escaped from"        '**Escaped from:** plan · **Fixes:** #0-8'
expect_fail "no space before the stage"       '**Escaped from:**implement'
# Near misses (review round 1): the audits read MARKER only, so each of these vanished from them too.
expect_fail "another case"                    '**Fixes:** #0-8 · **Escaped From:** plan'
expect_fail "not bold"                        '**Fixes:** #0-8 · Escaped from: plan'
expect_fail "the colon outside the bold"      '**Fixes:** #0-8 · **Escaped from**: plan'

echo "check-escape-markers: every file, every line"
printf '%s\n' '**Fixes:** #0-1 · **Escaped from:** plan' > BACKLOG.md
printf '%s\n' '# done' 'x' '**Escaped from:** ship' > BACKLOG-DONE.md
out=$(check); rc=$?
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -q '::error file=BACKLOG-DONE.md,line=3::'; then ok "the default files include BACKLOG-DONE.md"
else fail "default files: rc=$rc $out"; fi
printf '%s\n' '**Escaped from:** plan' '**Escaped from:** queue' > BACKLOG.md
out=$(check BACKLOG.md)
if [ "$(printf '%s\n' "$out" | grep -c '::error file=')" -eq 2 ]; then ok "each malformed line is reported"
else fail "two lines: $out"; fi

echo "check-escape-markers: fails closed"
rm BACKLOG-DONE.md
if check BACKLOG.md BACKLOG-DONE.md >/dev/null; then fail "a missing file passed"; else ok "a missing file fails"; fi
printf '%s\n' '**Fixes:** #0-1 · **Escaped from:** plan' > BACKLOG.md
printf "MARKER='x' MARKER_ATTEMPT='('\n" > scripts/factory/_marker.sh
if check BACKLOG.md >/dev/null; then fail "a broken pattern passed"; else ok "grep failing on a broken pattern fails"; fi
printf '# no definitions\n' > scripts/factory/_marker.sh
if check BACKLOG.md >/dev/null; then fail "an empty definition passed"; else ok "no MARKER defined fails"; fi
rm scripts/factory/_marker.sh
if check BACKLOG.md >/dev/null; then fail "no _marker.sh passed"; else ok "a missing _marker.sh fails"; fi

if [ "$failures" -gt 0 ]; then echo "$failures escape-marker test(s) failed"; exit 1; fi
echo "All escape-marker tests passed."
