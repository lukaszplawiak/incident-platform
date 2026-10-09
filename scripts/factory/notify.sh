#!/usr/bin/env bash
# ============================================================
# Optional push notification (ntfy) for STOP, BLOCKED, shipped PRs and audit reports.
#   notify.sh <event> <detail>      e.g. notify.sh blocked 0-25 · notify.sh pr-ready 123
# Only identifiers travel: the workflow passes an event name and an item or PR number.
# No-op unless FACTORY_NTFY_TOPIC is set (a long random topic name; anyone who knows it can read it, so
# put identifiers and links here, never code, secrets or tenant data). FACTORY_NTFY_SERVER defaults to
# https://ntfy.sh; the devcontainer firewall must allow that host.
# ============================================================
set -uo pipefail
event=${1:-event}; detail=${2:-}
title="Autopilot: $event"; msg="$event ${detail} ($(basename "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"))"
if [ -z "${FACTORY_NTFY_TOPIC:-}" ]; then echo '{"sent":false,"reason":"FACTORY_NTFY_TOPIC not set"}'; exit 0; fi
server=${FACTORY_NTFY_SERVER:-https://ntfy.sh}
if curl -fsS --max-time 10 -H "Title: $title" -d "$msg" "$server/$FACTORY_NTFY_TOPIC" >/dev/null; then
    echo '{"sent":true}'
else
    echo '{"sent":false,"reason":"curl failed"}'
fi
