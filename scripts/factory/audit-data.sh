#!/usr/bin/env bash
# ============================================================
# Collects the audit's raw data into one JSON file and prints a short summary as JSON.
#   audit-data.sh <since YYYY-MM-DD>
# Data: autopilot PRs updated since the date (labels incl. human:*, body with the verdict JSON in
# <details>), items with **Escaped from:**, seeded-defect results, the local run history, and the
# transcript directory (Claude Code keeps transcripts for cleanupPeriodDays, 90 in settings.json).
#
# For the pipeline audit (backlog #0-121) it also collects the merged /ready PRs (branches docs/backlog-ready-<0-N>,
# with their "Ready check" section) and derives the CASES by the rules in .ai/rules/audit.md, "Pipeline audit" —
# this script is their one implementation. Derived from what the pipeline already records, so no agent and no person
# has to remember to log a case. Only MERGED /ready PRs are read at all: merging is the owner's, so a PR opened from
# a fork on a docs/backlog-ready-* branch neither stands in for one nor puts its text in front of the analyst.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"
since=${1:?since date YYYY-MM-DD}
out="$RUNS_DIR/audit/audit-data-$since.json"; mkdir -p "$(dirname "$out")"

case "$since" in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;; *) echo '{"error":"since must be YYYY-MM-DD"}'; exit 1 ;; esac
prs_file="$RUNS_DIR/audit/prs-$since.json"
ready_file="$RUNS_DIR/audit/ready-prs-$since.json"
echo '[]' > "$prs_file"; echo '[]' > "$ready_file"
if command -v gh >/dev/null 2>&1; then
    gh pr list --label autopilot --state all --search "updated:>=$since" --limit 200 \
       --json number,title,state,labels,body,mergedAt,closedAt,url,headRefName,isDraft > "$prs_file" 2>/dev/null || echo '[]' > "$prs_file"
    # No date filter: an item's /ready PR is normally merged before the period its autopilot run falls in.
    gh pr list --state all --search "head:docs/backlog-ready" --limit 200 \
       --json number,title,state,body,mergedAt,url,headRefName > "$ready_file" 2>/dev/null || echo '[]' > "$ready_file"
fi
# A real marker names the fixing item (`**Fixes:** #0-N · **Escaped from:** <stage>`); prose that only describes the
# convention (BACKLOG's conventions, an item's text) does not, and is not counted (first pipeline audit, obs. 5).
# The same definition as the jq capture of $escapedCases below: change both together.
MARKER='\*\*Fixes:\*\* #0-[0-9]+.*\*\*Escaped from:\*\*'
escaped=$(grep -hE "$MARKER" BACKLOG.md BACKLOG-DONE.md 2>/dev/null | jq -R . | jq -s .)
# The escape markers ADDED since the date (the git history of the backlog files): only those are new cases, so an
# old escape is not traced again in every audit. ($escaped above, all of them, stays for the reviewers' audit.)
# A marker removed in the same period too was moved (an item closed: BACKLOG.md to BACKLOG-DONE.md), not added.
backlog_log=$(git log --since="$since" --format= -p -- BACKLOG.md BACKLOG-DONE.md 2>/dev/null)
escaped_added=$(printf '%s\n' "$backlog_log" | grep -E "^\\+[^+].*$MARKER" | sed 's/^+//' | sort -u | jq -R . | jq -s .)
escaped_removed=$(printf '%s\n' "$backlog_log" | grep -E "^-[^-].*$MARKER" | sed 's/^-//' | sort -u | jq -R . | jq -s .)
escaped_new=$(jq -n --argjson a "$escaped_added" --argjson r "$escaped_removed" '$a - $r')
seeded='[]'
[ -f .ai/audit/benchmark/results.md ] && seeded=$(grep -E '^\|' .ai/audit/benchmark/results.md | jq -R . | jq -s .)
state=$(state_init; cat "$STATE_FILE")
proj_dir="$HOME/.claude/projects/$(printf '%s' "$FACTORY_ROOT" | sed 's#[/.]#-#g')"

# PR bodies go through a file (--slurpfile): 200 bodies on a command line can exceed ARG_MAX.
jq -n --arg since "$since" --slurpfile prs "$prs_file" --slurpfile ready "$ready_file" --argjson escaped "$escaped" \
      --argjson escapedNew "$escaped_new" \
      --argjson seeded "$seeded" --argjson state "$state" --arg transcripts "$proj_dir" '
  def first_match($re): [match($re).captures[0].string] | first;
  def item_of: (.headRefName // "") | first_match("/(0-[0-9]+)-");
  # The /ready PR of an item, the newest first, with whether it carries a "Ready check" section.
  def ready_of($id): [$ready[0][] | select(.headRefName == ("docs/backlog-ready-" + $id) and .mergedAt != null)]
                     | sort_by(.number) | last
                     | if . == null then null
                       else {number, url, readyCheck: ((.body // "") | test("(?m)^## Ready check"))} end;
  ($prs[0] | map(
      (.labels | map(.name)) as $labels
      | (.body // "") as $body
      | {pr: .number, url, item: (item_of), branch: .headRefName, draft: .isDraft,
         merged: (.mergedAt != null), labels: $labels,
         rounds: ($body | first_match("(?m)^Rounds: ([0-9]+)") | if . == null then null else tonumber end),
         scope: ($body | first_match("(?m)^Scope: ([a-z-]+)")),
         acceptance: ($body | first_match("\"item\"\\s*:\\s*\"#0-[0-9]+\"[\\s\\S]*?\"verdict\"\\s*:\\s*\"([A-Z_]+)\""))}
      | . + {signals: [
          (if (.labels | index("blocked")) then "blocked" else empty end),
          (if (.rounds // 1) > 1 then "rounds:\(.rounds)" else empty end),
          (if .scope != null and (.scope | IN("consistent", "not-measured", "within-plan", "within-touches") | not)
             then "scope:\(.scope)" else empty end),
          (if .acceptance != null and .acceptance != "ACCEPT" then "acceptance:\(.acceptance)" else empty end),
          (.labels[] | select(test("^human:(fp|missed|introduced)-")))
        ]}
      | select(.signals | length > 0)
      | . + {readyPr: (if .item == null then null else ready_of(.item) end)})) as $prCases
  | ([$prCases[].item] | map(select(. != null))) as $covered
  # Exact comparisons only: inside and contains match strings as substrings ("0-1" inside "0-12").
  | ($state.history // [] | map(select(.outcome == "blocked" and ((.pr // "") == "") and ((.t // "") >= $since)
                                        and ((.item as $i | $covered | index($i)) == null)))
     | map({pr: null, url: null, item, branch: null, draft: null, merged: false, labels: [], rounds: null,
            scope: null, acceptance: null, stage: (.stage // null), signals: ["blocked (run history, no PR)"],
            readyPr: ready_of(.item)})) as $stateCases
  # The same marker definition as MARKER above: change both together. A line MARKER accepted but this capture cannot
  # parse (a stage name in another form) is an "escaped:unparsed" case, not dropped: every marker counted is traced.
  | ($escapedNew | map(capture("\\*\\*Fixes:\\*\\* #(?<item>0-[0-9]+).*\\*\\*Escaped from:\\*\\* *(?<from>[a-z][a-z0-9-]*)")?
                       // {item: first_match("#(0-[0-9]+)"), from: null})
     | map({pr: null, url: null, item, branch: null, draft: null, merged: true, labels: [], rounds: null,
            scope: null, acceptance: null, signals: [if .from == null then "escaped:unparsed" else "escaped:\(.from)" end],
            readyPr: ready_of(.item)})) as $escapedCases
  | {since: $since, prs: $prs[0], readyPrs: [$ready[0][] | select(.mergedAt != null)], escaped: $escaped, seeded: $seeded, state: $state,
     cases: ($prCases + $stateCases + $escapedCases), transcriptsDir: $transcripts}' > "$out"

jq -n --arg file "$out" --argjson n "$(jq length "$prs_file")" --arg transcripts "$proj_dir" \
      --argjson labelled "$(jq '[.[] | select(any(.labels[]; .name|startswith("human:")))] | length' "$prs_file")" \
      --argjson cases "$(jq '.cases | length' "$out")" --argjson ready "$(jq '[.[] | select(.mergedAt != null)] | length' "$ready_file")" \
      '{file:$file, prs:$n, prsWithHumanLabels:$labelled, readyPrs:$ready, cases:$cases, transcriptsDir:$transcripts}'
