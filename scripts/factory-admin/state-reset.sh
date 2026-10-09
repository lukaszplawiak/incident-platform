#!/usr/bin/env bash
# ============================================================
# Owner-only resets of the autopilot's local state. Not under scripts/factory/ on purpose: autopilot
# sessions may run scripts/factory/*, and an agent that could reset the circuit breaker or the audit
# counter could disable both. settings.autopilot.json denies scripts/factory-admin/.
#   state-reset.sh blocked     after the BLOCKED items were resolved (and .ai/STOP deleted)
#   state-reset.sh audit       after an audit's decisions were applied (/apply-audit runs it)
# ============================================================
set -uo pipefail
. "$(dirname "$0")/../factory/_common.sh"
state_init
now=$(date -u +%FT%TZ); tmp=$(mktemp)
case "${1:-}" in
    blocked) jq '.consecutiveBlocked=0' "$STATE_FILE" > "$tmp" && mv "$tmp" "$STATE_FILE" ;;
    audit)   jq --arg t "$now" '.shippedSinceAudit=0 | .lastAudit=$t' "$STATE_FILE" > "$tmp" && mv "$tmp" "$STATE_FILE" ;;
    *) echo "usage: state-reset.sh blocked|audit" >&2; exit 2 ;;
esac
cat "$STATE_FILE"
