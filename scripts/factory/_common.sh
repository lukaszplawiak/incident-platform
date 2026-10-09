#!/usr/bin/env bash
# Shared helpers for scripts/factory/*.sh. Sourced, not run.
# Requires: bash (3.2 is enough), git, jq. Some scripts also need gh (authenticated) and docker.

FACTORY_ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo '{"ok":false,"reasons":["not inside the repository"]}'; exit 1; }
cd "$FACTORY_ROOT" || exit 1
RUNS_DIR=".ai/runs"
STATE_FILE="$RUNS_DIR/state.json"
mkdir -p "$RUNS_DIR"

command -v jq >/dev/null 2>&1 || { echo '{"ok":false,"reasons":["jq is not installed"]}'; exit 1; }

# Same rule as .claude/hooks/_lib.sh: during a run, the commit recorded in .ai/runs/LOCK.
base_ref() {
    local sha
    if [ -f "$RUNS_DIR/LOCK" ]; then
        sha=$(awk '{print $2}' "$RUNS_DIR/LOCK" 2>/dev/null)
        case "$sha" in [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) echo "$sha"; return ;; esac
    fi
    if [ -n "${FACTORY_BASE_REF:-}" ]; then echo "$FACTORY_BASE_REF"; return; fi
    if git rev-parse --verify --quiet refs/remotes/origin/main >/dev/null; then echo refs/remotes/origin/main; else echo refs/heads/main; fi
}

state_init() {
    # A missing or unreadable state file is recreated with a breaker that has tripped, not reset to zero:
    # losing the state must not silently re-arm the autopilot.
    if [ ! -s "$STATE_FILE" ] || ! jq -e '(.consecutiveBlocked|type)=="number" and (.shippedSinceAudit|type)=="number"' "$STATE_FILE" >/dev/null 2>&1; then
        if [ -e "$STATE_FILE" ]; then
            echo '{"consecutiveBlocked":99,"shippedSinceAudit":0,"lastAudit":null,"history":[],"note":"state file was unreadable"}' > "$STATE_FILE"
        else
            echo '{"consecutiveBlocked":0,"shippedSinceAudit":0,"lastAudit":null,"history":[]}' > "$STATE_FILE"
        fi
    fi
}

# is_int <value> — true for a non-negative integer
is_int() { case "$1" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }

# The runs directory, the lock and STOP are local state and must never be committed.
ensure_ignored() {
    for p in ".ai/runs/" ".ai/STOP"; do
        git check-ignore -q "$p" 2>/dev/null || echo "warning: $p is not gitignored" >&2
    done
}
