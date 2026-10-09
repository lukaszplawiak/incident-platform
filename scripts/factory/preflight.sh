#!/usr/bin/env bash
# ============================================================
# Autopilot preflight. Prints one JSON object: {"ok": bool, "reasons": [...], "baseRef", "baseSha",
# "runId", "today", "state": {...}}. Exit 0 whenever it could decide (ok or not ok).
#
# Refuses to start when: .ai/STOP exists; the working tree is not clean; the current branch is not main
# or cannot fast-forward to origin/main; an open issue carries the `autopilot-stop` label; the latest CI
# run on main failed; the circuit breaker tripped (FACTORY_MAX_BLOCKED consecutive BLOCKED items,
# default 2); an audit is due (FACTORY_AUDIT_EVERY shipped items since the last one, default 10); the
# daily merge limit is reached (FACTORY_MAX_MERGES_PER_DAY, default 5); another run holds the lock
# (younger than FACTORY_LOCK_HOURS, default 3); Docker is not reachable (the tests need it).
# The breaker and the audit trigger create .ai/STOP, so the owner has to clear them on purpose.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"
state_init
ensure_ignored

reasons=()
add() { reasons+=("$1"); }

MAX_BLOCKED=${FACTORY_MAX_BLOCKED:-2}
AUDIT_EVERY=${FACTORY_AUDIT_EVERY:-10}
MAX_MERGES=${FACTORY_MAX_MERGES_PER_DAY:-5}
LOCK_HOURS=${FACTORY_LOCK_HOURS:-3}
today=$(date -u +%F)
run_id=$(date -u +%Y%m%d-%H%M%S)

[ -f .ai/STOP ] && add "STOP file present: $(head -c 300 .ai/STOP | tr '\n' ' ')"
# Personal allow rules would merge into the dontAsk session and widen what agents may run.
[ -f .claude/settings.local.json ] && add ".claude/settings.local.json exists: its allow rules would apply to the autopilot; move it out of the workspace (it is personal, see docs/ai-factory.md)"

[ -z "$(git status --porcelain)" ] || add "working tree is not clean"
branch=$(git rev-parse --abbrev-ref HEAD)
[ "$branch" = main ] || add "current branch is $branch, not main"
if git remote get-url origin >/dev/null 2>&1; then
    git fetch --quiet origin main 2>/dev/null || add "git fetch origin main failed"
    if [ "$branch" = main ] && [ -z "$(git status --porcelain)" ]; then
        git merge --ff-only --quiet origin/main 2>/dev/null || add "main cannot fast-forward to origin/main"
    fi
fi
base=$(base_ref)
base_sha=$(git rev-parse --verify --quiet "$base" 2>/dev/null || echo "")

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    stops=$(gh issue list --label autopilot-stop --state open --json number --jq 'length' 2>/dev/null || echo "?")
    if ! is_int "$stops"; then add "could not list autopilot-stop issues"
    elif [ "$stops" -gt 0 ]; then add "$stops open issue(s) labelled autopilot-stop"; fi
    labels=$(gh label list --limit 200 --json name --jq '.[].name' 2>/dev/null || echo "")
    for l in autopilot shadow blocked risk-high autopilot-stop; do
        grep -qx "$l" <<<"$labels" || add "label '$l' does not exist (backlog #0-113)"
    done
    ci=$(gh run list --workflow ci.yml --branch main --limit 1 --json status,conclusion --jq '.[0] | "\(.status)/\(.conclusion)"' 2>/dev/null || echo "?")
    case "$ci" in
        completed/success|"") ;;
        completed/*) add "latest CI run on main: $ci" ;;
        "?") add "could not read CI status of main" ;;
        *) ;;  # in progress: the next preflight decides
    esac
    merged=$(gh pr list --label autopilot --state merged --search "merged:>=$today" --json number --jq 'length' 2>/dev/null || echo "?")
    if ! is_int "$merged"; then add "could not count today's merged autopilot PRs"
    elif [ "$merged" -ge "$MAX_MERGES" ]; then add "daily merge limit reached ($merged/$MAX_MERGES)"; fi
else
    add "gh is not installed or not authenticated (the autopilot opens PRs with it)"
fi

blocked=$(jq -r '.consecutiveBlocked' "$STATE_FILE")
shipped=$(jq -r '.shippedSinceAudit' "$STATE_FILE")
is_int "$blocked" && is_int "$shipped" || { add "state file unreadable"; blocked=99; shipped=0; }
if [ "$blocked" -ge "$MAX_BLOCKED" ]; then
    add "circuit breaker: $blocked items BLOCKED in a row"
    [ -f .ai/STOP ] || printf 'circuit breaker: %s items BLOCKED in a row (%s). Resolve them, then delete this file and run scripts/factory-admin/state-reset.sh blocked.\n' "$blocked" "$today" > .ai/STOP
fi
if [ "$shipped" -ge "$AUDIT_EVERY" ]; then
    add "audit due: $shipped items shipped since the last audit"
    [ -f .ai/STOP ] || printf 'audit due: %s items shipped since the last audit (%s). Run /audit (args since, date), decide, run /apply-audit, then delete this file.\n' "$shipped" "$today" > .ai/STOP
fi

lock="$RUNS_DIR/LOCK"
if [ -f "$lock" ]; then
    age_h=$(( ( $(date +%s) - $(stat -c %Y "$lock" 2>/dev/null || stat -f %m "$lock") ) / 3600 ))
    if [ "$age_h" -lt "$LOCK_HOURS" ]; then add "another run holds the lock: $(cat "$lock")"; fi
fi

docker info >/dev/null 2>&1 || add "Docker is not reachable (Testcontainers tests need it)"

ok=true
[ "${#reasons[@]}" -eq 0 ] || ok=false
if [ "$ok" = true ]; then
    if [ -n "$base_sha" ]; then echo "$run_id $base_sha" > "$lock"; else ok=false; reasons+=("could not resolve the base commit"); fi
fi

jq -n --argjson ok "$ok" --arg base "$base" --arg sha "$base_sha" --arg run "$run_id" --arg today "$today" \
      --slurpfile state "$STATE_FILE" \
      --args '{ok:$ok, reasons:$ARGS.positional, baseRef:$base, baseSha:$sha, runId:$run, today:$today, state:$state[0]}' \
      ${reasons[@]+"${reasons[@]}"}
