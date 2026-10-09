#!/usr/bin/env bash
# ============================================================
# Local autopilot state in .ai/runs/state.json (gitignored).
#   state.sh get
#   state.sh record <shipped|blocked> <item> [pr-url]   (also releases the lock)
#   state.sh unlock
# Resetting the breaker or the audit counter is the owner's: scripts/factory-admin/state-reset.sh, which
# autopilot sessions may not run.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"
state_init
now=$(date -u +%FT%TZ)
tmp=$(mktemp)
case "${1:-get}" in
    get) cat "$STATE_FILE" ;;
    record)
        outcome=${2:?shipped or blocked}; item=${3:?item id}; pr=${4:-}
        case "$outcome" in
            shipped) filter='.consecutiveBlocked=0 | .shippedSinceAudit+=1 | .history=(.history+[{t:$t,item:$i,outcome:"shipped",pr:$p}])[-200:]' ;;
            blocked) filter='.consecutiveBlocked+=1 | .history=(.history+[{t:$t,item:$i,outcome:"blocked",pr:$p}])[-200:]' ;;
            *) echo "unknown outcome: $outcome" >&2; exit 2 ;;
        esac
        # Release the lock even if the state cannot be updated; never replace the state with a failed write.
        rm -f "$RUNS_DIR/LOCK"
        jq --arg i "$item" --arg p "$pr" --arg t "$now" "$filter" "$STATE_FILE" > "$tmp" && mv "$tmp" "$STATE_FILE" \
            || { echo '{"error":"state update failed; state file left unchanged"}'; exit 1; }
        cat "$STATE_FILE" ;;
    unlock) rm -f "$RUNS_DIR/LOCK"; echo '{"unlocked":true}' ;;
    *) echo "usage: state.sh get|record|unlock" >&2; exit 2 ;;
esac
