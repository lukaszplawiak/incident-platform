#!/usr/bin/env bash
# ============================================================
# Collects the audit's raw data into one JSON file and prints a short summary as JSON.
#   audit-data.sh <since YYYY-MM-DD>
# Data: autopilot PRs updated since the date (labels incl. human:*, body with the verdict JSON in
# <details>), items with **Escaped from:**, seeded-defect results, the local run history, and the
# transcript directory (Claude Code keeps transcripts for cleanupPeriodDays, 90 in settings.json).
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"
since=${1:?since date YYYY-MM-DD}
out="$RUNS_DIR/audit/audit-data-$since.json"; mkdir -p "$(dirname "$out")"

case "$since" in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;; *) echo '{"error":"since must be YYYY-MM-DD"}'; exit 1 ;; esac
prs_file="$RUNS_DIR/audit/prs-$since.json"; mkdir -p "$(dirname "$prs_file")"
echo '[]' > "$prs_file"
if command -v gh >/dev/null 2>&1; then
    gh pr list --label autopilot --state all --search "updated:>=$since" --limit 200 \
       --json number,title,state,labels,body,mergedAt,closedAt,url > "$prs_file" 2>/dev/null || echo '[]' > "$prs_file"
fi
escaped=$(grep -hE '\*\*Escaped from:\*\*' BACKLOG.md BACKLOG-DONE.md 2>/dev/null | jq -R . | jq -s .)
seeded='[]'
[ -f .ai/audit/benchmark/results.md ] && seeded=$(grep -E '^\|' .ai/audit/benchmark/results.md | jq -R . | jq -s .)
state=$(state_init; cat "$STATE_FILE")
proj_dir="$HOME/.claude/projects/$(printf '%s' "$FACTORY_ROOT" | sed 's#[/.]#-#g')"

# PR bodies go through a file (--slurpfile): 200 bodies on a command line can exceed ARG_MAX.
jq -n --arg since "$since" --slurpfile prs "$prs_file" --argjson escaped "$escaped" --argjson seeded "$seeded" \
      --argjson state "$state" --arg transcripts "$proj_dir" \
      '{since:$since, prs:$prs[0], escaped:$escaped, seeded:$seeded, state:$state, transcriptsDir:$transcripts}' > "$out"

jq -n --arg file "$out" --argjson n "$(jq length "$prs_file")" --arg transcripts "$proj_dir" \
      --argjson labelled "$(jq '[.[] | select(any(.labels[]; .name|startswith("human:")))] | length' "$prs_file")" \
      '{file:$file, prs:$n, prsWithHumanLabels:$labelled, transcriptsDir:$transcripts}'
