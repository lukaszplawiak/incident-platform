#!/usr/bin/env bash
# ============================================================
# Local autopilot state in .ai/runs/state.json (gitignored).
#   state.sh get
#   state.sh record <shipped|blocked> <item> [pr-url|-] [stage]   (also releases the lock)
#     stage: the autopilot phase a blocked item stopped in (pick, plan, implement, review, acceptance, ship),
#     so the audit can tell where a problem was detected from where it was introduced (backlog #0-122; #0-42's
#     history said only "blocked", with no PR and no stage). "-" stands for "no PR".
#   state.sh unlock
#   state.sh branch <type/name>   records the run's own branch in the lock, once (backlog #0-131)
# Resetting the breaker or the audit counter is the owner's: scripts/factory-admin/state-reset.sh, which
# autopilot sessions may not run.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"
state_init
now=$(date -u +%FT%TZ)
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT   # only record moves it into place; every other command leaves it behind otherwise
case "${1:-get}" in
    get) cat "$STATE_FILE" ;;
    record)
        outcome=${2:?shipped or blocked}; item=${3:?item id}; pr=${4:-}; stage=${5:-}
        [ "$pr" = "-" ] && pr=""
        # $t, $i, $p and $s are jq's variables (the --arg below), not the shell's (backlog #0-149).
        # shellcheck disable=SC2016
        case "$outcome" in
            shipped) filter='.consecutiveBlocked=0 | .shippedSinceAudit+=1 | .history=(.history+[{t:$t,item:$i,outcome:"shipped",pr:$p}])[-200:]' ;;
            blocked) filter='.consecutiveBlocked+=1 | .history=(.history+[{t:$t,item:$i,outcome:"blocked",pr:$p} + (if $s == "" then {} else {stage:$s} end)])[-200:]' ;;
            *) echo "unknown outcome: $outcome" >&2; exit 2 ;;
        esac
        # Release the lock even if the state cannot be updated; never replace the state with a failed write.
        rm -f "$RUNS_DIR/LOCK"
        { jq --arg i "$item" --arg p "$pr" --arg s "$stage" --arg t "$now" "$filter" "$STATE_FILE" > "$tmp" && mv "$tmp" "$STATE_FILE"; } \
            || { echo '{"error":"state update failed; state file left unchanged"}'; exit 1; }
        cat "$STATE_FILE" ;;
    unlock) rm -f "$RUNS_DIR/LOCK"; echo '{"unlocked":true}' ;;
    branch)
        # The run's own branch (backlog #0-131): the one guard-git-push.sh lets the autopilot push. Recorded once per
        # run, into the lock preflight.sh wrote (`<runId> <sha>` becomes `<runId> <sha> <branch>`); a second call, a
        # missing lock or a name that is not a feature branch is refused, so the branch cannot move mid-run.
        name=${2:-}
        lock="$RUNS_DIR/LOCK"
        fail() { jq -n --arg e "$1" '{error: $e}'; exit 1; }
        [[ $name =~ ^[a-z]+/[A-Za-z0-9._-]+$ ]] || fail "not a feature branch name: ${name:0:80}"
        case "$name" in */main) fail "not a feature branch name: $name" ;; esac
        [ -f "$lock" ] || fail "no run holds the lock"
        read -r run sha recorded rest < "$lock" || fail "cannot read the lock"
        [ -n "${run:-}" ] && [ -n "${sha:-}" ] || fail "the lock holds no run and base commit"
        [ -z "${recorded:-}${rest:-}" ] || fail "this run already recorded its branch (${recorded:-?})"
        printf '%s %s %s\n' "$run" "$sha" "$name" > "$lock" || fail "cannot write the lock"
        jq -n --arg b "$name" '{branch: $b}' ;;
    *) echo "usage: state.sh get|record|unlock|branch <name>" >&2; exit 2 ;;
esac
