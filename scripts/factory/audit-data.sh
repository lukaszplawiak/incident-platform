#!/usr/bin/env bash
# ============================================================
# Collects the audit's raw data into one JSON file and prints a short summary as JSON.
#   audit-data.sh <since YYYY-MM-DD>
# Data: autopilot PRs updated since the date (labels, with human:* only as verifiedLabels / unverifiedLabels — see
# "Owner labels" below; body with the verdict JSON in <details>), items with **Escaped from:**, seeded-defect results, the local run history, and the
# transcript directory (Claude Code keeps transcripts for cleanupPeriodDays, 90 in settings.json).
#
# For the pipeline audit (backlog #0-121) it also collects the merged /ready PRs (branches docs/backlog-ready-<0-N>,
# with their "Ready check" section) and derives the CASES by the rules in .ai/rules/audit.md, "Pipeline audit" —
# this script is their one implementation. Derived from what the pipeline already records, so no agent and no person
# has to remember to log a case. Only MERGED /ready PRs are read at all: merging is the owner's, so a PR opened from
# a fork on a docs/backlog-ready-* branch neither stands in for one nor puts its text in front of the analyst.
#
# Owner labels (backlog #0-126): a `human:*` label counts only when the repository owner added it, read from the PR's
# label events (the last `labeled` event of a label still on the PR). The data keeps `verifiedLabels` and
# `unverifiedLabels` apart and strips `human:*` from the raw `labels`, so no reader can count an unchecked one; a
# label by another account, with no event data, or with the owner unknown is unverified; and while any autopilot PR
# is authored by the owner's own login, every label is (the autopilot then acts with the owner's token, so the actor
# proves nothing on any PR — review round 1, sec-4c1e). It fails closed.
# The owner is the repository's owner login (`gh repo view`), which the autopilot cannot change; an organisation's
# repository has an organisation there, so every label would be unverified until this reads its members.
#
# Escape markers (backlog #0-129): a `**Fixes:** #0-N · **Escaped from:** <stage>` line counts only when GitHub
# vouches for who wrote it: the commit that first added the marker itself (from `**Fixes:**` to the stage, found with
# `git log -S` and checked in the commit's diff, so moving the line to BACKLOG-DONE.md or changing the rest of it,
# the status, keeps its origin) belongs to a PR merged into main and opened by the owner
# (`gh api repos/<repo>/commits/<sha>/pulls`). A commit another account pushed onto the owner's PR branch is in
# the owner's PR too; the autopilot cannot push there since backlog #0-131 (only its run's own branch).
# Not enough: the commit's author or committer (free text; the owner and the bot share the container), a GitHub
# signature (the owner signs nothing; the web-flow one on merge commits is GitHub's), the owner's merge of someone
# else's PR (in shadow mode the owner merges every autopilot PR). A marker pushed with no PR, from another account's
# PR, with the lookup failing, with the owner unknown, or while the autopilot runs on the owner's login (the same
# rule as for labels) is listed in `unverifiedEscapes` with its reason, and is neither a case nor in `escaped`.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"
since=${1:?since date YYYY-MM-DD}
out="$RUNS_DIR/audit/audit-data-$since.json"; mkdir -p "$(dirname "$out")"

case "$since" in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;; *) echo '{"error":"since must be YYYY-MM-DD"}'; exit 1 ;; esac
prs_file="$RUNS_DIR/audit/prs-$since.json"
ready_file="$RUNS_DIR/audit/ready-prs-$since.json"
events_file="$RUNS_DIR/audit/label-events-$since.json"
vouch_file="$RUNS_DIR/audit/escape-vouch-$since.json"
echo '[]' > "$prs_file"; echo '[]' > "$ready_file"; echo '{}' > "$events_file"; echo '{}' > "$vouch_file"
owner="" repo=""
if command -v gh >/dev/null 2>&1; then
    gh pr list --label autopilot --state all --search "updated:>=$since" --limit 200 \
       --json number,title,state,labels,body,mergedAt,closedAt,url,headRefName,isDraft,author > "$prs_file" 2>/dev/null || echo '[]' > "$prs_file"
    # No date filter: an item's /ready PR is normally merged before the period its autopilot run falls in.
    gh pr list --state all --search "head:docs/backlog-ready" --limit 200 \
       --json number,title,state,body,mergedAt,url,headRefName > "$ready_file" 2>/dev/null || echo '[]' > "$ready_file"
    # Who added each label, only for the PRs that carry a human:* one. A failed call leaves that PR without events:
    # its human labels stay unverified, never trusted.
    repo=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null) || repo=""
    case "$repo" in */*) owner=${repo%%/*} ;; *) repo="" ;; esac
    if [ -n "$repo" ]; then
        for n in $(jq -r '.[] | select(any(.labels[]?; .name | ascii_downcase | startswith("human:"))) | .number' "$prs_file"); do
            ev=$(gh api "repos/$repo/issues/$n/events" --paginate 2>/dev/null \
                 | jq -s '[.[][]? | select(.event == "labeled") | {label: .label.name, actor: .actor.login}]' 2>/dev/null) || continue
            jq --arg n "$n" --argjson ev "$ev" '. + {($n): $ev}' "$events_file" > "$events_file.tmp" && mv "$events_file.tmp" "$events_file"
        done
    fi
fi
# When an autopilot PR is authored by the owner login, the autopilot runs with the owner token: every label event
# and every PR it makes names the owner, so neither proves anything (review round 1 of #0-126, sec-4c1e; backlog
# #0-129 for the markers).
owner_is_autopilot=false
if [ -n "$owner" ] && jq -e --arg o "$owner" 'any(.[]; (.author.login // null) == $o)' "$prs_file" >/dev/null 2>&1; then
    owner_is_autopilot=true
fi
# A real marker names the fixing item (`**Fixes:** #0-N · **Escaped from:** <stage>`); prose that only describes the
# convention (BACKLOG's conventions, an item's text) does not, and is not counted (first pipeline audit, obs. 5).
# MARKER, and how to read the marker, its item and its stage out of a line, are defined once, in _marker.sh
# (backlog #0-128).
. "$(dirname "$0")/_marker.sh"
# The history below is the local one: no replace refs and no grafts, which would let code with write access to .git
# show an owner's commit adding a marker it never added (review round 1 of #0-129; the rest of that trust is the
# "code the session runs" gap, README).
# Every git call that reads a diff also ignores textconv and external diff drivers, which .git/config could set to
# rewrite what -S, -p and show see; a shallow clone is refused below (its boundary commits show their whole files as
# added) (review round 2).
export GIT_NO_REPLACE_OBJECTS=1 GIT_GRAFT_FILE=/dev/null
NODIFF=(--no-textconv --no-ext-diff)
shallow=$(git rev-parse --is-shallow-repository 2>/dev/null) || shallow=unknown
# Unique: a copied line is one marker, not two, in the reviewers audit too.
escaped=$(grep -hE "$MARKER" BACKLOG.md BACKLOG-DONE.md 2>/dev/null | jq -R . | jq -s 'unique')
# The escape markers ADDED since the date (the git history of the backlog files): only those are new cases, so an
# old escape is not traced again in every audit. ($escaped above, all of them, stays for the reviewers' audit.)
# Per marker (marker_span), not per line: every marker on a line added in the period is a candidate, its newest
# line kept; it is NEW only when the commit that first added it (its origin, below) is in the period. So a move (an
# item closed: BACKLOG.md to BACKLOG-DONE.md), a change to the rest of its line (the status) or a copy of an old
# marker is not a new escape, while a marker added and then moved or edited in the same period is. Not a +/- count:
# a copy of an old owner's line, left beside the original, was +1/-0 and made a vouched case again (review round 2).
# --since-as-filter, not --since: --since stops walking at the first older commit, so one back-dated commit would
# hide every marker behind it (review round 3). Each line goes to jq as its own JSON value, no separator to split
# on: a marker may hold a tab (review round 3).
backlog_log=$(git log "${NODIFF[@]}" --since-as-filter="$since" --format= -p -- BACKLOG.md BACKLOG-DONE.md 2>/dev/null </dev/null)
escaped_new=$(printf '%s\n' "$backlog_log" | grep -E "^\+[^+].*$MARKER" | sed 's/^+//' | while IFS= read -r l; do
                  jq -n --arg s "$(marker_span "$l")" --arg l "$l" '{span: $s, line: $l}'
              done | jq -s 'group_by(.span) | map(.[0].line)')
# Who wrote each marker (backlog #0-129, see the header): one entry per line, {commit, added (its committer date),
# verified, reason, item, stage}. A line the loop leaves without an entry is unverified too (the jq below), so a failure here never counts a
# marker.
# pulls_verdict <sha>: OK when a PR merged into main and opened by the owner contains the commit, else the reason.
# An explicit OK, not an empty answer: nothing printed (a later edit gone wrong) is a reason, never a vouch.
pulls_verdict() {
    local pulls
    pulls=$(gh api "repos/$repo/commits/$1/pulls" --paginate 2>/dev/null </dev/null | jq -s 'add // []' 2>/dev/null) \
        || { echo "the PR lookup for $1 failed or returned no JSON"; return; }
    printf '%s' "$pulls" | jq -r --arg o "$owner" '
        if type != "array" then "the PR lookup returned no list"
        elif any(.[]; .merged_at != null and .base.ref == "main" and .user.login == $o) then "OK"
        elif length == 0 then "no pull request: pushed straight to a branch"
        else "no PR merged into main and opened by the owner: "
             + ([.[] | "#\(.number) by \(.user.login // "?"), "
                       + (if .merged_at == null then "not merged" else "merged into \(.base.ref)" end)] | join("; "))
        end' 2>/dev/null || echo "the PR lookup for $1 could not be read"
}
# adds_marker <sha> <span>: the commit adds a line whose marker is exactly <span>. `git log -S` matches a substring,
# so an owner's commit with a longer line around the same text (`…plan` in backticks, a sentence) is listed too;
# only a commit that adds the marker itself is its origin (review round 1 of #0-129).
adds_marker() {
    local a
    while IFS= read -r a; do
        [ "$(marker_span "$a")" = "$2" ] && return 0
    done < <(git show "${NODIFF[@]}" --format= "$1" -- BACKLOG.md BACKLOG-DONE.md 2>/dev/null </dev/null | grep -E '^\+[^+]' | grep -F -- "$2" | sed 's/^+//')
    return 1
}
# Every command in the loop below reads /dev/null, not the loop's input (the marker lines) (review round 3).
verdicts=""   # "<sha> <verdict>" lines: one lookup per commit (bash 3.2 has no associative arrays)
while IFS= read -r line; do
    [ -n "$line" ] || continue
    span=$(marker_span "$line")
    sha=""
    # The origin is the oldest commit, in the history's order, that adds this marker, wherever the line went and
    # whatever else on it changed. Commit dates can be set by anyone, but a forged one can only make a marker
    # unverified (the bot's commit found first) or not new: the vouching itself is GitHub's.
    for c in $(git log "${NODIFF[@]}" -S"$span" --reverse --format=%H -- BACKLOG.md BACKLOG-DONE.md 2>/dev/null </dev/null); do
        if adds_marker "$c" "$span"; then sha=$c; break; fi
    done
    if [ -z "$owner" ] || [ -z "$repo" ]; then reason="the repository owner is unknown"
    elif [ "$owner_is_autopilot" = true ]; then reason="autopilot PRs are authored by the owner login: a PR's opener proves nothing"
    elif [ "$shallow" != false ]; then reason="the repository is a shallow clone (or unreadable): its history cannot say who added it"
    elif [ -z "$sha" ]; then reason="no commit in the local history adds it (a shallow clone?)"
    else
        cached=$(printf '%s\n' "$verdicts" | grep -m 1 "^$sha ")
        if [ -n "$cached" ]; then verdict=${cached#"$sha "}
        else verdict=$(pulls_verdict "$sha"); verdicts="$verdicts$sha $verdict"$'\n'
        fi
        case "$verdict" in
            OK) reason="" ;;
            "") reason="the PR lookup for $sha printed nothing" ;;
            *) reason=$verdict ;;
        esac
    fi
    added=""; [ -n "$sha" ] && added=$(git show -s --format=%cs "$sha" 2>/dev/null </dev/null)
    jq --arg l "$line" --arg c "$sha" --arg a "$added" --arg r "$reason" --arg i "$(marker_item "$line")" --arg s "$(marker_stage "$line")" \
       '. + {($l): {commit: (if $c == "" then null else $c end), added: (if $a == "" then null else $a end), verified: ($r == ""), reason: (if $r == "" then null else $r end),
                    item: (if $i == "" then null else $i end), stage: $s}}' \
       "$vouch_file" > "$vouch_file.tmp" && mv "$vouch_file.tmp" "$vouch_file"
done < <(jq -rn --argjson a "$escaped" --argjson b "$escaped_new" '$a + $b | unique[]')
seeded='[]'
[ -f .ai/audit/benchmark/results.md ] && seeded=$(grep -E '^\|' .ai/audit/benchmark/results.md | jq -R . | jq -s .)
state=$(state_init; cat "$STATE_FILE")
proj_dir="$HOME/.claude/projects/$(printf '%s' "$FACTORY_ROOT" | sed 's#[/.]#-#g')"

# PR bodies go through a file (--slurpfile): 200 bodies on a command line can exceed ARG_MAX.
jq -n --arg since "$since" --slurpfile prs "$prs_file" --slurpfile ready "$ready_file" --argjson escaped "$escaped" \
      --argjson escapedNew "$escaped_new" --slurpfile events "$events_file" --arg owner "$owner" \
      --argjson ownerIsAutopilot "$owner_is_autopilot" --slurpfile vouch "$vouch_file" \
      --argjson seeded "$seeded" --argjson state "$state" --arg transcripts "$proj_dir" '
  def first_match($re): [match($re).captures[0].string] | first;
  def item_of: (.headRefName // "") | first_match("/(0-[0-9]+)-");
  # The /ready PR of an item, the newest first, with whether it carries a "Ready check" section.
  def ready_of($id): [$ready[0][] | select(.headRefName == ("docs/backlog-ready-" + $id) and .mergedAt != null)]
                     | sort_by(.number) | last
                     | if . == null then null
                       else {number, url, readyCheck: ((.body // "") | test("(?m)^## Ready check"))} end;
  # A marker counts only when its entry in the vouch file says so (backlog #0-129); no entry is no vouching.
  def vouched: . as $l | ($vouch[0][$l].verified // false) == true;
  # The human:* labels of one PR, each with who added it and, when it does not count, why (backlog #0-126).
  # $ownerIsAutopilot comes from the shell, which needs it for the markers too.
  def checked_labels($ev):
    [ .[] | select(ascii_downcase | startswith("human:")) | . as $l
      | (if $ev == null then null else ([$ev[] | select(.label == $l)] | last | .actor // null) end) as $actor
      | {label: $l, actor: $actor,
         reason: (if $owner == "" then "the repository owner is unknown"
                  elif $actor == null then "no label event in the data"
                  elif $actor != $owner then "added by \($actor), not the owner"
                  elif $ownerIsAutopilot then "autopilot PRs are authored by the owner login: no actor proves anything"
                  else null end)} ];
  ($prs[0] | map(
      (.labels | map(.name)) as $all
      | $events[0][(.number | tostring)] as $ev
      # Bound first: jq evaluates the arguments of a function on the input of the call ($all here), not on the PR.
      | ($all | checked_labels($ev)) as $checked
      # Any case: GitHub matches label names case-insensitively (review round 4).
      | . + {labels: [.labels[] | select(.name | ascii_downcase | startswith("human:") | not)],
             verifiedLabels: [$checked[] | select(.reason == null) | .label],
             unverifiedLabels: [$checked[] | select(.reason != null)]})) as $prsV
  | ($prsV | map(
      (.labels | map(.name)) as $labels
      | (.body // "") as $body
      | {pr: .number, url, item: (item_of), branch: .headRefName, draft: .isDraft,
         merged: (.mergedAt != null), labels: $labels, verifiedLabels, unverifiedLabels,
         rounds: ($body | first_match("(?m)^Rounds: ([0-9]+)") | if . == null then null else tonumber end),
         scope: ($body | first_match("(?m)^Scope: ([a-z-]+)")),
         acceptance: ($body | first_match("\"item\"\\s*:\\s*\"#0-[0-9]+\"[\\s\\S]*?\"verdict\"\\s*:\\s*\"([A-Z_]+)\""))}
      | . + {signals: [
          (if (.labels | index("blocked")) then "blocked" else empty end),
          (if (.rounds // 1) > 1 then "rounds:\(.rounds)" else empty end),
          (if .scope != null and (.scope | IN("consistent", "not-measured", "within-plan", "within-touches") | not)
             then "scope:\(.scope)" else empty end),
          (if .acceptance != null and .acceptance != "ACCEPT" then "acceptance:\(.acceptance)" else empty end),
          (.verifiedLabels[] | select(test("^human:(fp|missed|introduced)-"; "i")))
        ]}
      | select(.signals | length > 0)
      | . + {readyPr: (if .item == null then null else ready_of(.item) end)})) as $prCases
  | ([$prCases[].item] | map(select(. != null))) as $covered
  # Exact comparisons only: inside and contains match strings as substrings ("0-1" inside "0-12").
  | ($state.history // [] | map(select(.outcome == "blocked" and ((.pr // "") == "") and ((.t // "") >= $since)
                                        and ((.item as $i | $covered | index($i)) == null)))
     | map({pr: null, url: null, item, branch: null, draft: null, merged: false, labels: [], verifiedLabels: [],
            unverifiedLabels: [], rounds: null,
            scope: null, acceptance: null, stage: (.stage // null), signals: ["blocked (run history, no PR)"],
            readyPr: ready_of(.item)})) as $stateCases
  # The item and stage come from _marker.sh (the vouch entry), so no second pattern lives here. A stage in another
  # form (`Review-General`) is an "escaped:unparsed" case, not dropped: every marker counted is traced. Only vouched
  # markers are cases (backlog #0-129), and only those first added in the period (review round 2).
  | ($escapedNew | map(select(vouched and (($vouch[0][.].added // "") >= $since))) | map($vouch[0][.] | {item, from: (.stage | if test("^[a-z][a-z0-9-]*$") then . else null end)})
     | map({pr: null, url: null, item, branch: null, draft: null, merged: true, labels: [], verifiedLabels: [],
            unverifiedLabels: [], rounds: null,
            scope: null, acceptance: null, signals: [if .from == null then "escaped:unparsed" else "escaped:\(.from)" end],
            readyPr: ready_of(.item)})) as $escapedCases
  | {since: $since, owner: (if $owner == "" then null else $owner end),
     ownerIsAutopilot: $ownerIsAutopilot,
     unverifiedLabels: [$prsV[] | .number as $n | .unverifiedLabels[] | {pr: $n} + .],
     # The raw list for the reviewers audit holds vouched markers only; the others are named, never counted.
     unverifiedEscapes: [$escaped + $escapedNew | unique | .[] | . as $l
                         | ($vouch[0][$l] // {commit: null, verified: false, reason: "not checked"})
                         | select(.verified != true) | {line: $l, commit, reason}],
     prs: $prsV, readyPrs: [$ready[0][] | select(.mergedAt != null)], escaped: [$escaped[] | select(vouched)],
     seeded: $seeded, state: $state,
     cases: ($prCases + $stateCases + $escapedCases), transcriptsDir: $transcripts}' > "$out"

jq -n --arg file "$out" --argjson n "$(jq length "$prs_file")" --arg transcripts "$proj_dir" \
      --argjson labelled "$(jq '[.prs[] | select(.verifiedLabels | length > 0)] | length' "$out")" \
      --argjson unverified "$(jq '.unverifiedLabels | length' "$out")" \
      --argjson ownerIsAutopilot "$(jq '.ownerIsAutopilot' "$out")" --argjson owner "$(jq '.owner' "$out")" \
      --argjson unverifiedEscapes "$(jq '.unverifiedEscapes | length' "$out")" \
      --argjson cases "$(jq '.cases | length' "$out")" --argjson ready "$(jq '[.[] | select(.mergedAt != null)] | length' "$ready_file")" \
      '{file:$file, prs:$n, prsWithHumanLabels:$labelled, unverifiedHumanLabels:$unverified, owner:$owner,
        ownerIsAutopilot:$ownerIsAutopilot, unverifiedEscapes:$unverifiedEscapes, readyPrs:$ready, cases:$cases,
        transcriptsDir:$transcripts}'
