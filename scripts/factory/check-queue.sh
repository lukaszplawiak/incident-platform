#!/usr/bin/env bash
# ============================================================
# Validates the execution queue (.ai/plan/queue.md) against BACKLOG.md and BACKLOG-DONE.md. The planner
# proposes the queue, the owner approves it by merging it; this script is what makes "approved" mean
# "consistent": an agent proposes, code rejects an inconsistent proposal (.ai/rules/planning.md).
# The autopilot works strictly one item at a time, in row order.
#
#   check-queue.sh               the working tree (/plan-backlog, before the owner sees the proposal)
#   check-queue.sh --ref <ref>   the files as they are on a commit (next-item.sh, on the run's base)
#
# Errors (exit 1): markers missing; a row without an item id; an item that is neither open nor done; an
# item twice; a queued item that is human-only or of type design; a dependency that is neither done nor
# queued before its dependant; an unknown module in **Touches:**.
# Warnings: a queued item that is not `ready` (the queue stops there until the owner marks it); a queued
# item without **Touches:** (no scope measurement for it).
# No queue file: valid, "present": false (the autopilot then falls back to priority order).
# Output: {"valid", "present", "errors", "warnings", "rows", "openRows"}
# ============================================================
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$(git rev-parse --show-toplevel 2>/dev/null)" || { echo '{"valid":false,"errors":["not inside the repository"]}'; exit 1; }
command -v jq >/dev/null 2>&1 || { echo '{"valid":false,"errors":["jq is not installed"]}'; exit 1; }
. "$HERE/_backlog.sh"

ref=""
if [ "${1:-}" = "--ref" ]; then
    ref=${2:-}
    git rev-parse --verify --quiet "$ref^{commit}" >/dev/null || { jq -n --arg r "$ref" '{valid:false, errors:["unknown ref " + $r]}'; exit 1; }
fi
read_file() {   # read_file <path> — from the ref, or from the working tree
    if [ -n "$ref" ]; then git show "$ref:$1" 2>/dev/null; else cat "$1" 2>/dev/null; fi
}
has_file() {
    if [ -n "$ref" ]; then git cat-file -e "$ref:$1" 2>/dev/null; else [ -f "$1" ]; fi
}

if ! has_file .ai/plan/queue.md; then
    echo '{"valid":true,"present":false,"errors":[],"warnings":[],"rows":0,"openRows":0}'
    exit 0
fi
backlog=$(read_file BACKLOG.md | backlog_json) || { echo '{"valid":false,"errors":["cannot read BACKLOG.md"]}'; exit 1; }
done_ids=$(read_file BACKLOG-DONE.md | done_json) || done_ids='[]'
queue=$(read_file .ai/plan/queue.md | queue_json) || { echo '{"valid":false,"errors":["cannot parse .ai/plan/queue.md"]}'; exit 1; }

result=$(jq -n --argjson items "$backlog" --argjson done "$done_ids" --argjson q "$queue" --argjson known "$KNOWN_MODULES" '
  ($items | map({key: .id, value: .}) | from_entries) as $byId
  | def isDone($id): ($done | index($id)) != null;
    $q.rows as $rows
  | ($rows | map(select(.item != "")) | map({key: .item, value: .index}) | from_entries) as $posOf
  | ($rows | map(select(.item != "" and (isDone(.item) | not) and $byId[.item] != null)
             | . + {it: $byId[.item]})) as $open
  | [
      (if $q.markers then empty else "the queue table must sit between <!-- queue:start --> and <!-- queue:end -->" end),
      ($rows[] | select(.item == "") | "row \(.index + 1) (\(.pos)): no backlog item id"),
      ($rows | map(select(.item != "")) | group_by(.item)[] | select(length > 1) | "#\(.[0].item) is queued \(length) times"),
      ($rows[] | select(.item != "" and $byId[.item] == null and (isDone(.item) | not))
               | "#\(.item) is neither an open item in BACKLOG.md nor done in BACKLOG-DONE.md"),
      ($open[] | select(.it.autopilot == "human-only") | "#\(.item) is human-only: the autopilot never runs it, so it cannot be queued"),
      ($open[] | select(.it.type == "design") | "#\(.item) is a design item: decide it (ADR) and queue its implementation item instead"),
      ($open[] as $r | $r.it.dependsOn[] as $d
         | select(isDone($d) | not)
         | if $posOf[$d] == null then "#\($r.item) depends on #\($d), which is neither done nor queued"
           elif $posOf[$d] > $r.index then "#\($r.item) depends on #\($d), which is queued after it"
           else empty end),
      ($open[] as $r | $r.it.modules[] | select(. as $m | $known | index($m) | not)
         | "#\($r.item): unknown module \"\(.)\" in Touches (allowed: \($known | join(", ")))")
    ] as $errors
  | [
      ($open[] | select(.it.autopilot != "ready" and .it.autopilot != "human-only")
         | "#\(.item) is \(if .it.autopilot == "" then "without an Autopilot line" else .it.autopilot end): the queue stops at it until the owner marks it ready (/ready)"),
      ($open[] | select(.it.modules | length == 0)
         | "#\(.item) has no **Touches:** line: its scope will not be measured (.ai/rules/planning.md, \"Touches\")")
    ] as $warnings
  | {valid: ($errors | length == 0), present: true, errors: $errors, warnings: $warnings,
     rows: ($rows | length), openRows: ($open | length)}')
echo "$result"
[ "$(printf '%s' "$result" | jq -r .valid)" = true ]
