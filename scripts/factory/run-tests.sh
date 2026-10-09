#!/usr/bin/env bash
# ============================================================
# Deterministic test gate of the autopilot, run before the review panel (a reviewer never judges code
# that does not build). Prints JSON:
#   {"status":"pass|fixable|stop", "exitCode", "modules", "log", "summary": [last relevant lines]}
#   pass    — ./mvnw verify succeeded for the affected modules (tests + JaCoCo check)
#   fixable — the build or a test failed: the implementer gets the summary and fixes it
#   stop    — the environment failed (no Docker, a dependency could not be downloaded): a human looks
# Usage: run-tests.sh <item-id> <round> [base-ref]
# Affected modules: every top-level module the branch touches; all of them when shared, service-parent
# or the root POM changed (CLAUDE.md: changing shared changes all 7 services). `-am` builds what they
# depend on. Never add -DskipTests here: this script is the gate.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"

item=${1:?item id}; round=${2:?round}; base=${3:-$(base_ref)}
safe_item=$(printf '%s' "$item" | tr -c 'A-Za-z0-9._-' '-')
dir="$RUNS_DIR/$safe_item"; mkdir -p "$dir"
log="$dir/tests-r$round.log"

# The gate tests a commit, not a working tree: uncommitted changes would be tested but never reviewed.
if [ -n "$(git status --porcelain --untracked-files=normal -- . ':!.ai/runs')" ]; then
    jq -n --arg log "$log" '{status:"stop", exitCode:-1, modules:"", log:$log, head:"", summary:["the working tree is not clean: commit or remove the changes, the gate tests HEAD only"]}'
    exit 0
fi
head=$(git rev-parse HEAD)

ALL="shared,service-parent,auth-service,ingestion-service,incident-service,notification-service,escalation-service,postmortem-service,oncall-service"
paths=$(bash "$(dirname "$0")/changed-paths.sh" "$base")
if ! printf '%s' "$paths" | jq -e '(.error == null) and (.modules|type == "array")' >/dev/null 2>&1; then
    jq -n --arg log "$log" --arg h "$head" --arg p "$paths" '{status:"stop", exitCode:-1, modules:"", log:$log, head:$h, summary:["changed-paths failed: " + $p]}'
    exit 0
fi
if [ "$(printf '%s' "$paths" | jq -r '.allModules')" = true ]; then
    mods=$ALL
else
    mods=$(printf '%s' "$paths" | jq -r '.modules | join(",")')
fi
if [ -z "$mods" ]; then
    jq -n --arg log "$log" --arg h "$head" '{status:"pass", exitCode:0, modules:"", log:$log, head:$h, summary:["no Maven module changed: nothing to build"]}'
    exit 0
fi

./mvnw -B -ntp verify -pl "$mods" -am > "$log" 2>&1
rc=$?

if [ "$rc" -eq 0 ]; then
    status=pass
elif grep -Eq 'Could not find a valid Docker environment|Cannot connect to the Docker daemon|Could not transfer artifact|Could not resolve dependencies|Connection refused.*(repo|maven)|No space left on device' "$log"; then
    status=stop
else
    status=fixable
fi

summary=$( { grep -E '^\[ERROR\]|Tests run:.*Fail|FAILURE|BUILD (SUCCESS|FAILURE)|Coverage checks have not been met' "$log" | grep -v 'Re-run Maven\|-> \[Help' | tail -60; } | jq -R . | jq -s .)
# The build must not have changed tracked files (a generated file, a plugin rewriting sources).
[ "$(git rev-parse HEAD)" = "$head" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || status=stop
jq -n --arg s "$status" --argjson rc "$rc" --arg m "$mods" --arg log "$log" --arg h "$head" --argjson sum "$summary" \
    '{status:$s, exitCode:$rc, modules:$m, log:$log, head:$h, summary:$sum}'
