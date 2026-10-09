#!/usr/bin/env bash
# ============================================================
# Tests for scripts/factory/check-queue.sh and scripts/factory/next-item.sh, in a temporary repository
# with a small backlog. `gh` is replaced by a stub that prints the branches of open PRs from a file.
# Run: .github/scripts/test-queue.sh   (also run by .github/workflows/factory-guards.yml)
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

mkdir -p "$WORK/repo/scripts/factory" "$WORK/repo/.ai/plan" "$WORK/bin"
cp "$SRC"/scripts/factory/_common.sh "$SRC"/scripts/factory/_backlog.sh "$SRC"/scripts/factory/check-queue.sh "$SRC"/scripts/factory/next-item.sh "$WORK/repo/scripts/factory/"
printf '#!/bin/sh\ncat "%s/open-prs" 2>/dev/null; [ ! -f "%s/gh-fails" ]\n' "$WORK" "$WORK" > "$WORK/bin/gh"
chmod +x "$WORK/bin/gh"
: > "$WORK/open-prs"
export PATH="$WORK/bin:$PATH"

cd "$WORK/repo" || exit 1
git init -q -b main
printf '.ai/runs/\n' > .gitignore

backlog() {   # backlog <autopilot of 0-8>
cat > BACKLOG.md <<EOF
# Backlog

## Open items

| # | Title | Type | Priority | Status |
|---|---|---|---|---|
| [0-1](#0-1-a) | One | bug | High | Open |
| [0-2](#0-2-b) | Two | bug | Medium | Open |
| [0-3](#0-3-c) | Three | tech-debt | Low | Open |
| [0-4](#0-4-d) | Four | tech-debt | Medium | Open |
| [0-5](#0-5-e) | Five | ci | High | Open |
| [0-6](#0-6-f) | Six | design | High | Open |
| [0-7](#0-7-g) | Seven | tech-debt | Low | Open |
| [0-8](#0-8-h) | Eight | tech-debt | Low | Open |
| [0-10](#0-10-j) | Ten | tech-debt | Low | Open |
| [0-11](#0-11-k) | Eleven | tech-debt | Low | Open |

---

### 0-1. One

**Type:** bug · **Priority:** High · **Status:** Open
**Autopilot:** ready · **Risk:** low · **Complexity:** low · **Depends on:** —
**Touches:** incident-service (incident.fsm)

### 0-2. Two

**Type:** bug · **Priority:** Medium · **Status:** Open
**Autopilot:** ready · **Risk:** low · **Complexity:** low · **Depends on:** #0-1
**Touches:** incident-service (incident.api)

### 0-3. Three

**Type:** tech-debt · **Priority:** Low · **Status:** Open
**Autopilot:** ready · **Risk:** low · **Complexity:** low · **Depends on:** —
**Touches:** oncall-service

### 0-4. Four

**Type:** tech-debt · **Priority:** Medium · **Status:** Open
**Autopilot:** not-ready · **Risk:** low · **Complexity:** low · **Depends on:** —
**Touches:** notification-service

### 0-5. Five

**Type:** ci · **Priority:** High · **Status:** Open
**Autopilot:** human-only · **Risk:** high · **Complexity:** low · **Depends on:** —

### 0-6. Six

**Type:** design · **Priority:** High · **Status:** Open
**Autopilot:** ready · **Risk:** high · **Complexity:** low · **Depends on:** —

### 0-7. Seven

**Type:** tech-debt · **Priority:** Low · **Status:** Open
**Autopilot:** ready · **Risk:** low · **Complexity:** low · **Depends on:** —
**Touches:** shared (tenant)

### 0-8. Eight

**Type:** tech-debt · **Priority:** Low · **Status:** Open
**Autopilot:** $1 · **Risk:** low · **Complexity:** low · **Depends on:** #0-9 · **Follow-up of:** #0-9
**Touches:** escalation-service

### 0-10. Ten

**Type:** tech-debt · **Priority:** Low · **Status:** Open
**Autopilot:** ready · **Risk:** low · **Complexity:** low · **Depends on:** —
**Touches:** frontend

### 0-11. Eleven

**Type:** tech-debt · **Priority:** Low · **Status:** Open
**Autopilot:** ready · **Risk:** low · **Complexity:** low · **Depends on:** —
EOF
}
backlog proposed
cat > BACKLOG-DONE.md <<'EOF'
| # | Title | Delivered in |
|---|---|---|
| 0-9 | Nine | PR #1 |
EOF

queue() {     # queue <item> ...   (rows in this order)
    { echo '# Execution queue'; echo; echo '<!-- queue:start -->'
      echo '| # | Item | Why here |'; echo '|---|---|---|'
      n=0; for item in "$@"; do n=$((n+1)); echo "| $n | #$item | because |"; done
      echo '<!-- queue:end -->'; } > .ai/plan/queue.md
}
commit() { git add -A >/dev/null && git -c user.email=t@t -c user.name=t commit -q -m "${1:-x}" --allow-empty && git rev-parse HEAD; }
CQ=scripts/factory/check-queue.sh
NI=scripts/factory/next-item.sh

echo "check-queue"
expect_jq "no queue file is valid"         "$($CQ)" '.valid and (.present | not)'
queue 0-1 0-2 0-3;                         expect_jq "dependency before dependant"   "$($CQ)" '.valid and .openRows == 3'
queue 0-2 0-1;                             expect_jq "dependency queued after"       "$($CQ)" '(.valid | not) and (.errors | any(test("queued after")))'
queue 0-2;                                 expect_jq "dependency missing"            "$($CQ)" '(.valid | not) and (.errors | any(test("neither done nor queued")))'
queue 0-7 0-3;                             expect_jq "shared next to others is fine" "$($CQ)" '.valid'
queue 0-5;                                 expect_jq "human-only queued"             "$($CQ)" '(.valid | not) and (.errors | any(test("human-only")))'
queue 0-6;                                 expect_jq "design item queued"            "$($CQ)" '(.valid | not) and (.errors | any(test("design item")))'
queue 0-99;                                expect_jq "unknown item"                  "$($CQ)" '(.valid | not) and (.errors | any(test("neither an open item")))'
queue 0-1 0-1;                             expect_jq "item twice"                    "$($CQ)" '(.valid | not) and (.errors | any(test("queued 2 times")))'
queue 0-9 0-3;                             expect_jq "done item stays valid"         "$($CQ)" '.valid and .openRows == 1'
queue 0-10;                                expect_jq "unknown module"                "$($CQ)" '(.valid | not) and (.errors | any(test("unknown module")))'
queue 0-11;                                expect_jq "no Touches is a warning"       "$($CQ)" '.valid and (.warnings | any(test("not be measured")))'
queue 0-4;                                 expect_jq "not-ready is a warning"        "$($CQ)" '.valid and (.warnings | any(test("queue stops at it")))'
printf '| 1 | #0-1 | x |\n' > .ai/plan/queue.md; expect_jq "markers missing"     "$($CQ)" '(.valid | not) and (.errors | any(test("queue:start")))'
printf '<!-- queue:start -->\n| # | Item | Why here |\n|---|---|---|\n| 1 | soon | x |\n<!-- queue:end -->\n' > .ai/plan/queue.md
expect_jq "row without an id"              "$($CQ)" '(.valid | not) and (.errors | any(test("no backlog item id")))'
queue 0-1; base=$(commit q1); rm .ai/plan/queue.md
expect_jq "--ref reads the commit, not the tree" "$($CQ --ref "$base")" '.valid and .present and .rows == 1'

echo "next-item"
rm -f .ai/plan/queue.md; base=$(commit noqueue)
expect_jq "no queue: priority order"            "$($NI "$base")" '.ok and .item == "#0-1" and .source == "priority"'
expect_jq "manual item with an open dependency" "$($NI "$base" 0-2)" '.ok and .item == null and (.reason | test("waits for #0-1"))'
expect_jq "manual item that can start"          "$($NI "$base" 0-3)" '.ok and .item == "#0-3" and .source == "manual" and .modules == ["oncall-service"] and .risk == "low" and .complexity == "low"'
expect_jq "manual item that is human-only"      "$($NI "$base" 0-5)" '.ok and .item == null and (.reason | test("not ready"))'
queue 0-3 0-1; base=$(commit q)
expect_jq "first queue row"                     "$($NI "$base")" '.ok and .item == "#0-3" and .source == "queue" and .position == 1'
queue 0-9 0-3 0-1; base=$(commit q1b)
expect_jq "done rows are skipped"               "$($NI "$base")" '.ok and .item == "#0-3" and .position == 2'
queue 0-4 0-1; base=$(commit q2)
expect_jq "a not-ready row stops the queue"     "$($NI "$base")" '.ok and .item == null and (.reason | test("#0-4")) and (.skipped[0].item == "#0-4")'
queue 0-3 0-1; base=$(commit q2b)
echo "fix/0-3-three" > "$WORK/open-prs"
expect_jq "an open PR stops the queue"          "$($NI "$base")" '.ok and .item == null and (.reason | test("in progress"))'
: > "$WORK/open-prs"
git branch -q refactor/0-3-left-over
expect_jq "a local branch is a lock too"        "$($NI "$base")" '.ok and .item == null'
git branch -q -D refactor/0-3-left-over
queue 0-9; base=$(commit q3)
expect_jq "queue done: no fallback"             "$($NI "$base")" '.ok and .item == null and (.reason | test("plan-backlog"))'
backlog ready; queue 0-3; base=$(commit followup)
expect_jq "a ready follow-up of a done parent goes first" "$($NI "$base")" '.ok and .item == "#0-8" and .source == "follow-up" and .followUpOf == "0-9"'
backlog proposed; queue 0-2; base=$(commit badqueue)
expect_jq "invalid queue on the base: no start" "$($NI "$base")" '(.ok | not) and (.reasons[0] | test("invalid"))'
queue 0-3; base=$(commit q4); touch "$WORK/gh-fails"
expect_jq "gh failure: fail closed"             "$($NI "$base")" '(.ok | not) and (.reasons[0] | test("gh pr list"))'
rm -f "$WORK/gh-fails"
expect_jq "short sha refused"                   "$($NI "${base:0:12}")" '(.ok | not)'
expect_jq "malformed item refused"              "$($NI "$base" '0-3;x')" '(.ok | not)'
echo "#0-3 changed in the working tree" >> BACKLOG.md
expect_jq "the working copy is ignored"         "$($NI "$base")" '.ok and .item == "#0-3"'

if [ "$failures" -gt 0 ]; then echo "$failures queue test(s) failed"; exit 1; fi
echo "All queue tests passed."
