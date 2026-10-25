#!/usr/bin/env bash
# ============================================================
# Tests for scripts/factory/preflight.sh's reading of main's CI result (backlog #0-148) and of Claude Code's
# version (backlog #0-162 PR 2: 2.1.295 or newer, the running binary's), in a temporary repository with gh, docker
# and claude replaced by stubs on PATH (the real .ai/runs/ is never touched).
#
# The gh stub answers `gh run list` from a list of runs, newest first, filtered the way GitHub filters
# them: `--branch` by the run's head branch, `--event` by its event, then `--limit`. That is what made
# #0-148 possible: a fork pull request's run from a branch named `main` has head branch `main` too, and
# without `--event push` it was "the latest CI run on main".
# Run: .github/scripts/test-preflight.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }

mkdir -p "$WORK/bin" "$WORK/repo/scripts/factory"
cp "$SRC"/scripts/factory/_common.sh "$SRC"/scripts/factory/preflight.sh "$WORK/repo/scripts/factory/"

# gh: authenticated, no autopilot-stop issue, every label present, no merged autopilot PR today; runs
# from $RUNS (a JSON array, newest first).
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
jq_expr=. branch="" event="" limit=20
cmd="$1 $2"
shift 2
while [ $# -gt 0 ]; do
    case "$1" in
        --jq) jq_expr=$2; shift 2 ;;
        --branch) branch=$2; shift 2 ;;
        --event) event=$2; shift 2 ;;
        --limit) limit=$2; shift 2 ;;
        *) shift ;;
    esac
done
case "$cmd" in
    "auth status") exit 0 ;;
    "issue list"|"pr list") echo '[]' | jq -r "$jq_expr" ;;
    "label list") jq -n '[{name:"autopilot"},{name:"shadow"},{name:"blocked"},{name:"risk-high"},{name:"autopilot-stop"}]' | jq -r "$jq_expr" ;;
    "run list") printf '%s' "$RUNS" | jq --arg b "$branch" --arg e "$event" --argjson n "$limit" \
                    '[.[] | select(($b == "" or .headBranch == $b) and ($e == "" or .event == $e))] | .[:$n]' | jq -r "$jq_expr" ;;
    *) echo "gh stub: unexpected '$cmd'" >&2; exit 1 ;;
esac
STUB
printf '#!/bin/sh\nexit 0\n' > "$WORK/bin/docker"
# claude: prints $CLAUDE_VERSION_OUT as its version (a current one when unset); `running` is a second binary, the
# one CLAUDE_CODE_EXECPATH names in the cases that set it.
# The stub expands $CLAUDE_VERSION_OUT when it runs, not when it is written: single quotes meant.
# shellcheck disable=SC2016
printf '#!/bin/sh\nprintf "%%s\\n" "${CLAUDE_VERSION_OUT-2.1.310 (Claude Code)}"\n' > "$WORK/bin/claude"
printf '#!/bin/sh\necho "2.1.294 (Claude Code)"\n' > "$WORK/running"
# hangs: never answers (a binary stuck before it prints its version).
printf '#!/bin/sh\nsleep 30\necho "2.1.310 (Claude Code)"\n' > "$WORK/hangs"
chmod +x "$WORK/bin/gh" "$WORK/bin/docker" "$WORK/bin/claude" "$WORK/running" "$WORK/hangs"

cd "$WORK/repo" || exit 1
git init -q -b main
printf '.ai/runs/\n.ai/STOP\n' > .gitignore
git add -A && git -c user.name=t -c user.email=t@t commit -qm init

# run_preflight <runs JSON> — prints preflight's JSON; removes the lock a passing run takes. $CV is the version the
# claude stub prints (unset: a current one), $EXEC the CLAUDE_CODE_EXECPATH (unset: none, as outside Claude Code),
# $WAIT how long preflight waits for it to answer, in tenths of a second.
run_preflight() {
    if [ -n "${EXEC:-}" ]; then
        RUNS=$1 PATH="$WORK/bin:$PATH" CLAUDE_CODE_EXECPATH=$EXEC FACTORY_CLAUDE_VERSION_WAIT=${WAIT:-100} \
            scripts/factory/preflight.sh
    elif [ -n "${CV+set}" ]; then
        RUNS=$1 PATH="$WORK/bin:$PATH" CLAUDE_VERSION_OUT=$CV env -u CLAUDE_CODE_EXECPATH scripts/factory/preflight.sh
    else
        RUNS=$1 PATH="$WORK/bin:$PATH" env -u CLAUDE_CODE_EXECPATH scripts/factory/preflight.sh
    fi
    rm -f .ai/runs/LOCK
}

# expect <name> <runs JSON> <jq expression that must be true of preflight's output>
expect() {
    local out
    out=$(run_preflight "$2")
    if printf '%s' "$out" | jq -e "$3" >/dev/null 2>&1; then ok "$1"; else fail "$1: '$3' is false for: $(printf '%s' "$out" | head -c 600)"; fi
}

push()   { printf '{"event":"push","headBranch":"main","status":"%s","conclusion":"%s"}' "$1" "$2"; }
forkpr() { printf '{"event":"pull_request","headBranch":"main","status":"%s","conclusion":"%s"}' "$1" "$2"; }
branchpr() { printf '{"event":"pull_request","headBranch":"fix/x","status":"%s","conclusion":"%s"}' "$1" "$2"; }

echo "preflight.sh: main's CI result"
expect "a green push to main passes" "[$(push completed success)]" '.ok == true and .reasons == []'
expect "a red push to main stops" "[$(push completed failure)]" \
    '.ok == false and (.reasons | index("latest CI run on main: completed/failure"))'
expect "no run of main yet passes (the \"\" arm, not an unknown status)" "[]" '.ok == true and .reasons == []'
expect "a run of main in progress passes (the next preflight decides)" "[$(push in_progress "")]" '.ok == true'
expect "a queued run of main passes" "[$(push queued "")]" '.ok == true'
expect "a status gh does not document stops" "[$(push mystery "")]" \
    '.ok == false and (.reasons | index("unexpected CI status of main: mystery/"))'
expect "a newer red run of a fork's branch main does not stop a green main" \
    "[$(forkpr completed failure),$(push completed success)]" '.ok == true and .reasons == []'
expect "a newer fork run awaiting approval does not stop a green main" \
    "[$(forkpr completed action_required),$(push completed success)]" '.ok == true and .reasons == []'
expect "a newer green run of a fork's branch main does not hide a red main" \
    "[$(forkpr completed success),$(push completed failure)]" \
    '.ok == false and (.reasons | index("latest CI run on main: completed/failure"))'
expect "a pull request of this repository's own branch is not main's" \
    "[$(branchpr completed failure),$(push completed success)]" '.ok == true'

echo "preflight.sh: Claude Code's version (onFailure, backlog #0-162)"
green="[$(push completed success)]"
older='.ok == false and any(.reasons[]; contains("is older than 2.1.295"))'
unread='.ok == false and any(.reasons[]; contains("cannot read the version of Claude Code"))'
CV="2.1.295 (Claude Code)" expect "2.1.295, the first that honours onFailure, passes" "$green" '.ok == true'
CV="2.1.1000 (Claude Code)" expect "2.1.1000 passes (numbers, not text)" "$green" '.ok == true'
CV="3.0.0 (Claude Code)" expect "3.0.0 passes" "$green" '.ok == true'
CV="2.1.294 (Claude Code)" expect "2.1.294 stops (it ignores onFailure)" "$green" "$older"
CV="2.1.30 (Claude Code)" expect "2.1.30 stops (\"30\" sorts after \"295\" as text)" "$green" "$older"
CV="2.0.999 (Claude Code)" expect "2.0.999 stops" "$green" "$older"
CV="1.9.400" expect "1.9.400 stops" "$green" "$older"
CV="Claude Code" expect "an output without a version stops" "$green" "$unread"
CV="" expect "an empty output stops" "$green" "$unread"
CV="2.1.295.1 (Claude Code)" expect "a version of four fields stops" "$green" "$unread"
CV="2.99999999999999999999.0 (Claude Code)" expect "a field too long for a number stops" "$green" "$unread"
EXEC="$WORK/running" expect "the running binary decides, not the one on PATH" "$green" \
    '.ok == false and any(.reasons[]; contains("Claude Code 2.1.294 is older"))'
EXEC="$WORK/no-such-claude" expect "a running binary that cannot answer stops (no fallback to PATH)" "$green" "$unread"
started=$SECONDS
EXEC="$WORK/hangs" WAIT=5 expect "a binary that hangs stops, not the preflight" "$green" "$unread"
if [ $((SECONDS - started)) -lt 20 ]; then ok "... and the preflight did not wait for it (the stub sleeps 30 s)"
else fail "the preflight waited $((SECONDS - started)) s for a binary that hangs"; fi

if [ "$failures" -gt 0 ]; then echo "$failures preflight test(s) failed"; exit 1; fi
echo "All preflight tests passed."
