#!/usr/bin/env bash
# ============================================================
# Tests for scripts/factory/state.sh, in a temporary repository (the real .ai/runs/state.json is never
# touched). Covers the breaker and audit counters and the history entry of each outcome, including the
# stop stage and the "-" placeholder for "no PR" of a blocked item (backlog #0-122).
# Run: .github/scripts/test-factory-state.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }

# expect_jq <name> <json> <jq expression that must be true>
expect_jq() {
    if printf '%s' "$2" | jq -e "$3" >/dev/null 2>&1; then ok "$1"; else fail "$1: '$3' is false for: $(printf '%s' "$2" | head -c 600)"; fi
}

mkdir -p "$WORK/repo/scripts/factory"
cp "$SRC"/scripts/factory/_common.sh "$SRC"/scripts/factory/state.sh "$WORK/repo/scripts/factory/"
cd "$WORK/repo" || exit 1
git init -q -b main
ST=scripts/factory/state.sh

echo "state.sh"
expect_jq "a missing state starts at zero" "$($ST get)" '.consecutiveBlocked == 0 and .shippedSinceAudit == 0 and .history == []'

touch .ai/runs/LOCK
out=$($ST record blocked 0-42 - implement)
expect_jq "blocked without a PR: empty pr, stage kept" "$out" \
    '.history[-1] == (.history[-1] | {t, item: "0-42", outcome: "blocked", pr: "", stage: "implement"}) and .consecutiveBlocked == 1'
[ -e .ai/runs/LOCK ] && fail "record released no lock" || ok "record releases the lock"

out=$($ST record blocked 0-43 https://github.com/o/r/pull/7 ship)
expect_jq "blocked with a PR and a stage" "$out" '.history[-1].pr == "https://github.com/o/r/pull/7" and .history[-1].stage == "ship" and .consecutiveBlocked == 2'

out=$($ST record blocked 0-44)
expect_jq "blocked without stage: no stage key" "$out" '(.history[-1] | has("stage") | not) and .history[-1].pr == ""'

out=$($ST record shipped 0-45 https://github.com/o/r/pull/8)
expect_jq "shipped resets the breaker and counts for the audit" "$out" \
    '.consecutiveBlocked == 0 and .shippedSinceAudit == 1 and (.history[-1] | has("stage") | not) and .history[-1].outcome == "shipped"'

if $ST record merged 0-46 >/dev/null 2>&1; then fail "unknown outcome accepted"; else ok "unknown outcome refused"; fi

echo '{not json' > .ai/runs/state.json
expect_jq "an unreadable state trips the breaker" "$($ST get)" '.consecutiveBlocked == 99'

if [ "$failures" -gt 0 ]; then echo "$failures state test(s) failed"; exit 1; fi
echo "All state tests passed."
