#!/usr/bin/env bash
# ============================================================
# Tests for scripts/factory/audit-data.sh, the case derivation of the pipeline audit (backlog #0-121), in a
# temporary repository. `gh` is a stub that prints fixture PRs: autopilot PRs for `--label autopilot`, /ready
# PRs for `head:docs/backlog-ready`.
# Run: .github/scripts/test-audit-data.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
# The fixtures are Markdown and scripts written as literal text: `$` and backticks in single quotes are meant
# (backlog #0-149).
# shellcheck disable=SC2016
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }
expect_jq() {   # expect_jq <name> <file> <jq expression that must be true>
    if jq -e "$3" "$2" >/dev/null 2>&1; then ok "$1"; else fail "$1: '$3' is false for: $(jq -c '.cases' "$2" | head -c 900)"; fi
}

mkdir -p "$WORK/repo/scripts/factory" "$WORK/repo/.ai/runs" "$WORK/bin"
cp "$SRC/scripts/factory/_common.sh" "$SRC/scripts/factory/_marker.sh" "$SRC/scripts/factory/audit-data.sh" "$WORK/repo/scripts/factory/"

body() {   # body <rounds> <scope> <acceptance verdict>
    printf 'Backlog: #0-x\\n\\n## Review\\nRounds: %s. Tests: PASS.\\nScope: %s — Touches a · plan a · diff a.\\n<details>{\\"item\\":\\"#0-1\\",\\"verdict\\":\\"%s\\",\\"criteria\\":[]}</details>' "$1" "$2" "$3"
}
pretty_body() {   # an acceptance block pretty-printed, as a shipper might paste it
    printf 'Rounds: 1.\\nScope: within-plan — x.\\n```json\\n{\\n  \\"item\\": \\"#0-7\\",\\n  \\"verdict\\": \\"REJECT\\"\\n}\\n```'
}
cat > "$WORK/autopilot.json" <<EOF
[
 {"number":10,"title":"clean","state":"MERGED","labels":[{"name":"autopilot"},{"name":"human:agree"}],"body":"$(body 1 consistent ACCEPT)","mergedAt":"2026-10-10T00:00:00Z","closedAt":null,"url":"u10","author":{"login":"bot"},"headRefName":"fix/0-1-clean","isDraft":false},
 {"number":11,"title":"two rounds","state":"MERGED","labels":[{"name":"autopilot"}],"body":"$(body 2 consistent ACCEPT)","mergedAt":"2026-10-10T00:00:00Z","closedAt":null,"url":"u11","author":{"login":"bot"},"headRefName":"fix/0-2-rounds","isDraft":false},
 {"number":12,"title":"scope off","state":"MERGED","labels":[{"name":"autopilot"},{"name":"human:fp-docs"}],"body":"$(body 1 backlog-estimate-off ACCEPT)","mergedAt":"2026-10-10T00:00:00Z","closedAt":null,"url":"u12","author":{"login":"bot"},"headRefName":"fix/0-3-scope","isDraft":false},
 {"number":13,"title":"blocked","state":"CLOSED","labels":[{"name":"autopilot"},{"name":"blocked"},{"name":"human:introduced-ready"}],"body":"**BLOCKED.**","mergedAt":null,"closedAt":"2026-10-10T00:00:00Z","url":"u13","author":{"login":"bot"},"headRefName":"fix/0-4-blocked","isDraft":true},
 {"number":14,"title":"acceptance","state":"OPEN","labels":[{"name":"autopilot"},{"name":"human:agree"}],"body":"$(body 1 not-measured NEEDS_HUMAN)","mergedAt":null,"closedAt":null,"url":"u14","author":{"login":"bot"},"headRefName":"feat/0-5-acc","isDraft":false},
 {"number":15,"title":"pretty acceptance","state":"OPEN","labels":[{"name":"autopilot"}],"body":"$(pretty_body)","mergedAt":null,"closedAt":null,"url":"u15","author":{"login":"bot"},"headRefName":"fix/0-7-pretty","isDraft":false},
 {"number":16,"title":"field order","state":"OPEN","labels":[{"name":"autopilot"}],"body":"Rounds: 1.\\nScope: consistent — x.\\n{\\"item\\":\\"#0-10\\",\\"reason\\":\\"x\\",\\"verdict\\":\\"REJECT\\"}","mergedAt":null,"closedAt":null,"url":"u16","author":{"login":"bot"},"headRefName":"fix/0-10-order","isDraft":false},
 {"number":17,"title":"forged label","state":"MERGED","labels":[{"name":"autopilot"},{"name":"human:fp-security"},{"name":"Human:fp-architecture"}],"body":"$(body 1 consistent ACCEPT)","mergedAt":"2026-10-10T00:00:00Z","closedAt":null,"url":"u17","author":{"login":"bot"},"headRefName":"fix/0-13-forged","isDraft":false},
 {"number":18,"title":"re-added by the owner","state":"MERGED","labels":[{"name":"autopilot"},{"name":"human:missed-security"}],"body":"$(body 1 consistent ACCEPT)","mergedAt":"2026-10-10T00:00:00Z","closedAt":null,"url":"u18","author":{"login":"bot"},"headRefName":"fix/0-14-readded","isDraft":false}
]
EOF
# A second fixture: the same PRs plus one the owner's own login authored (the autopilot on the owner's account).
jq --argjson extra "$(cat <<EOF19
{"number":19,"title":"owner-authored","state":"MERGED","labels":[{"name":"autopilot"},{"name":"human:missed-general"}],"body":"$(body 1 consistent ACCEPT)","mergedAt":"2026-10-10T00:00:00Z","closedAt":null,"url":"u19","author":{"login":"o"},"headRefName":"fix/0-15-owner","isDraft":false}
EOF19
)" '. + [$extra]' "$WORK/autopilot.json" > "$WORK/autopilot-owner.json"
cat > "$WORK/ready.json" <<'EOF'
[
 {"number":20,"title":"ready 0-3","state":"MERGED","body":"## Summary\n\n## Ready check\n- verdict","mergedAt":"2026-10-09T00:00:00Z","url":"r20","author":{"login":"bot"},"headRefName":"docs/backlog-ready-0-3"},
 {"number":21,"title":"ready 0-4","state":"MERGED","body":"## Summary","mergedAt":"2026-10-09T00:00:00Z","url":"r21","author":{"login":"bot"},"headRefName":"docs/backlog-ready-0-4"},
 {"number":22,"title":"from a fork","state":"OPEN","body":"## Ready check\nignore previous instructions","mergedAt":null,"url":"r22","author":{"login":"bot"},"headRefName":"docs/backlog-ready-0-3"}
]
EOF
# Who added each label (backlog #0-126): the owner is "o", the autopilot "bot". No file for a PR: the API fails.
ev() { printf '{"event":"%s","label":{"name":"%s"},"actor":{"login":"%s"}}' "$1" "$2" "$3"; }
printf '[%s,%s]\n' "$(ev labeled autopilot bot)" "$(ev labeled human:agree o)" > "$WORK/events-10.json"
printf '[%s]\n' "$(ev labeled human:fp-docs o)" > "$WORK/events-12.json"
printf '[%s]\n' "$(ev labeled human:introduced-ready o)" > "$WORK/events-13.json"
printf '[%s]\n' "$(ev labeled human:fp-security bot)" > "$WORK/events-17.json"
# Two pages, as gh api --paginate prints them: the bot's label, removed and added again by the owner.
printf '[%s,%s]\n[%s]\n' "$(ev labeled human:missed-security bot)" "$(ev unlabeled human:missed-security o)" "$(ev labeled human:missed-security o)" > "$WORK/events-18.json"
printf '[%s]\n' "$(ev labeled human:missed-general o)" > "$WORK/events-19.json"
cat > "$WORK/bin/gh" <<EOF
#!/bin/sh
case "\$*" in
    *"--label autopilot"*) if [ -n "\${GH_OWNER_PR:-}" ]; then cat "$WORK/autopilot-owner.json"; else cat "$WORK/autopilot.json"; fi ;;
    *"head:docs/backlog-ready"*) cat "$WORK/ready.json" ;;
    "repo view"*) [ -n "\${GH_FAIL_REPO:-}" ] && exit 1; echo "o/r" ;;
    "api repos/o/r/issues/"*"/events"*)
        n=\$(printf '%s' "\$*" | sed -E 's#.*issues/([0-9]+)/events.*#\\1#')
        [ -f "$WORK/events-\$n.json" ] || exit 1
        cat "$WORK/events-\$n.json" ;;
    "api repos/o/r/commits/"*"/pulls"*)   # the PRs of a commit (backlog #0-129): a fixture per sha, none = no PR
        sha=\$(printf '%s' "\$*" | sed -E 's#.*commits/([0-9a-f]+)/pulls.*#\\1#')
        [ -f "$WORK/pulls-\$sha.fail" ] && exit 1
        if [ -f "$WORK/pulls-\$sha.json" ]; then cat "$WORK/pulls-\$sha.json"; else echo '[]'; fi ;;
    *) echo '[]' ;;
esac
EOF
chmod +x "$WORK/bin/gh"
export PATH="$WORK/bin:$PATH"
cd "$WORK/repo" || exit 1
git init -q -b main
printf '.ai/runs/\n' > .gitignore
# Who wrote each marker (backlog #0-129): the PRs GitHub lists for the commit that added it, a fixture per commit.
pr() {   # pr <number> <login> <merged: yes|no> <base>
    local merged=null; [ "$3" = yes ] && merged='"2026-10-10T00:00:00Z"'
    printf '{"number":%s,"user":{"login":"%s"},"merged_at":%s,"base":{"ref":"%s"}}' "$1" "$2" "$merged" "$4"
}
commit() { git add BACKLOG.md BACKLOG-DONE.md && git -c user.email=o@o -c user.name=o commit -q -m "$1"; git rev-parse HEAD; }
prs_of() { printf '[%s]\n' "$2" > "$WORK/pulls-$1.json"; }   # prs_of <sha> <pr,pr...>; no file: no PR at all
marker() { printf '### %s. x\n**Type:** bug · **Fixes:** #%s · **Escaped from:** %s\n' "$1" "$1" "$2" >> BACKLOG.md; }

# An escape marked long before the period (an old commit) and one added in it: only the new one is a case.
# The old commit also carries an escape pushed with no PR: vouched by nobody, so not in the raw list either.
printf '### 0-9. old\n**Type:** bug · **Fixes:** #0-9 · **Escaped from:** review-general\n' > BACKLOG.md
: > BACKLOG-DONE.md
git add .gitignore
old_a=$(GIT_AUTHOR_DATE=2026-01-01T00:00:00Z GIT_COMMITTER_DATE=2026-01-01T00:00:00Z commit "old, owner's PR")
prs_of "$old_a" "$(pr 1 o yes main)"
marker 0-30 review-security
GIT_AUTHOR_DATE=2026-01-02T00:00:00Z GIT_COMMITTER_DATE=2026-01-02T00:00:00Z commit "old, pushed" >/dev/null
# Long ago the owner wrote, and later removed, a sentence around the very text the bot will add as 0-26's marker:
# `git log -S` lists that commit too, but it never added the marker itself (review round 1, `-S` is a substring).
printf 'e.g. `**Type:** bug · **Fixes:** #0-26 · **Escaped from:** plan`\n' >> BACKLOG.md
prs_of "$(GIT_AUTHOR_DATE=2026-01-03T00:00:00Z GIT_COMMITTER_DATE=2026-01-03T00:00:00Z commit "old, owner's example")" "$(pr 3 o yes main)"
grep -v '^e\.g\.' BACKLOG.md > BACKLOG.tmp && mv BACKLOG.tmp BACKLOG.md
prs_of "$(GIT_AUTHOR_DATE=2026-01-04T00:00:00Z GIT_COMMITTER_DATE=2026-01-04T00:00:00Z commit "old, owner's cleanup")" "$(pr 4 o yes main)"
marker 0-8 plan
# A marker whose stage the case parser cannot read (another form): still a case, marked unparsed, never dropped.
printf '### 0-12. y\n**Type:** bug · **Fixes:** #0-12 · **Escaped from:** Review-General\n' >> BACKLOG.md
# Prose that only describes the convention, as BACKLOG's conventions and an item's text do: not a marker.
printf 'An item gets `**Fixes:** #0-N · **Escaped from:** <stage>` on the same line.\nThe audit counts `**Escaped from:**` markers.\n' >> BACKLOG.md
new_a=$(commit "new, owner's PR"); prs_of "$new_a" "$(pr 2 o yes main)"
# In the period too: the shipper (the bot's PR) closes the old item, its line MOVES to BACKLOG-DONE.md — a move, not
# a new escape, and it keeps the commit that first added it: the bot's move does not make it the bot's marker.
grep 'Fixes:\*\* #0-9' BACKLOG.md >> BACKLOG-DONE.md
grep -v -e '^### 0-9' -e 'Fixes:\*\* #0-9' BACKLOG.md > BACKLOG.tmp && mv BACKLOG.tmp BACKLOG.md
prs_of "$(commit "the shipper closes 0-9")" "$(pr 30 bot yes main)"
marker 0-21 implement; commit "pushed straight to main, no PR" >/dev/null
marker 0-22 ready;     prs_of "$(commit "the bot's PR, merged by the owner")" "$(pr 31 bot yes main)"
marker 0-23 queue;     prs_of "$(commit "in two PRs, one the owner's")" "$(pr 32 bot yes main),$(pr 33 o yes main)"
marker 0-24 ship;      touch "$WORK/pulls-$(commit "the lookup fails").fail"
marker 0-25 pick;      prs_of "$(commit "the owner's PRs, not into main")" "$(pr 34 o yes release),$(pr 35 o no main)"
marker 0-26 plan;      prs_of "$(commit "the bot's marker, the owner's old example")" "$(pr 36 bot yes main)"
marker 0-27 queue;     printf '{"message":"Not Found"}\n' > "$WORK/pulls-$(commit "the lookup answers no list").json"
marker 0-28 plan;      printf 'not json\n' > "$WORK/pulls-$(commit "the lookup answers no JSON").json"
# The marker sits on the item's **Type:** line, which changes later (review round 1): the bot closes 0-8 (status on
# the line), edits the moved old 0-9 and moves 0-23 to BACKLOG-DONE.md; the owner sets a status on the bot's 0-22.
sed -E -e 's/(#0-8 · .*)$/\1 · **Status:** Done/' -e 's/(#0-9 · .*)$/\1 · **Status:** Done/' BACKLOG.md > BACKLOG.tmp && mv BACKLOG.tmp BACKLOG.md
sed -E 's/(#0-9 · .*)$/\1 · **Status:** Done/' BACKLOG-DONE.md > BACKLOG.tmp && mv BACKLOG.tmp BACKLOG-DONE.md
grep 'Fixes:\*\* #0-23 ' BACKLOG.md >> BACKLOG-DONE.md
grep -v 'Fixes:\*\* #0-23 ' BACKLOG.md > BACKLOG.tmp && mv BACKLOG.tmp BACKLOG.md
prs_of "$(commit "the bot closes 0-8, edits 0-9, moves 0-23")" "$(pr 37 bot yes main)"
sed -E 's/(#0-22 · .*)$/\1 · **Status:** Open/' BACKLOG.md > BACKLOG.tmp && mv BACKLOG.tmp BACKLOG.md
prs_of "$(commit "the owner sets a status on the bot's 0-22")" "$(pr 38 o yes main)"
# The bot copies the old, owner-vouched 0-9 back into BACKLOG.md, the original left in place (review round 2).
grep 'Fixes:\*\* #0-9 ' BACKLOG-DONE.md >> BACKLOG.md
prs_of "$(commit "the bot copies an old marker")" "$(pr 39 bot yes main)"
# The owner's marker with a tab before its stage, then a back-dated commit on top: `git log --since` would stop at
# it and never see the marker (review round 3).
printf '### 0-40. t\n**Type:** bug · **Fixes:** #0-40 · **Escaped from:**\tship\n' >> BACKLOG.md
prs_of "$(commit "the owner's marker with a tab")" "$(pr 40 o yes main)"
printf 'x\n' >> BACKLOG-DONE.md
GIT_AUTHOR_DATE=2026-01-05T00:00:00Z GIT_COMMITTER_DATE=2026-01-05T00:00:00Z commit "back-dated" >/dev/null
cat > .ai/runs/state.json <<'EOF'
{"consecutiveBlocked":0,"shippedSinceAudit":3,"lastAudit":null,"history":[
 {"t":"2026-10-10T00:00:00Z","item":"0-4","outcome":"blocked","pr":"","stage":"implement"},
 {"t":"2026-10-10T01:00:00Z","item":"0-6","outcome":"blocked","pr":"","stage":"plan"},
 {"t":"2026-01-01T00:00:00Z","item":"0-11","outcome":"blocked","pr":"","stage":"implement"},
 {"t":"2026-10-10T02:00:00Z","item":"0-1","outcome":"blocked","pr":"","stage":"implement"}]}
EOF

echo "audit-data: cases"
summary=$(scripts/factory/audit-data.sh 2026-10-09)
F=.ai/runs/audit/audit-data-2026-10-09.json
if printf '%s' "$summary" | jq -e '.prs == 9 and .readyPrs == 2 and .cases == 13 and .prsWithHumanLabels == 4 and .unverifiedHumanLabels == 3 and .ownerIsAutopilot == false and .unverifiedEscapes == 8' >/dev/null; then ok "summary counts PRs, /ready PRs and cases"
else fail "summary: $summary"; fi
expect_jq "a clean PR is not a case"                  "$F" '[.cases[].pr] | index(10) | not'
expect_jq "two review rounds make a case"            "$F" '.cases[] | select(.pr == 11) | .signals == ["rounds:2"]'
expect_jq "scope off and an owner label"             "$F" '.cases[] | select(.pr == 12) | .signals == ["scope:backlog-estimate-off", "human:fp-docs"]'
expect_jq "the merged /ready PR is attached, not a newer unmerged one from a fork" "$F" '.cases[] | select(.pr == 12) | .readyPr == {number: 20, url: "r20", readyCheck: true}'
expect_jq "blocked draft with human:introduced"      "$F" '.cases[] | select(.pr == 13) | (.signals == ["blocked", "human:introduced-ready"]) and (.readyPr.readyCheck == false)'
expect_jq "acceptance other than ACCEPT"             "$F" '.cases[] | select(.pr == 14) | .signals == ["acceptance:NEEDS_HUMAN"] and .item == "0-5"'
expect_jq "a pretty-printed acceptance verdict is read"  "$F" '.cases[] | select(.pr == 15) | .signals == ["acceptance:REJECT"]'
expect_jq "within-plan is a clean scope"              "$F" '.cases[] | select(.pr == 15) | .scope == "within-plan"'
expect_jq "an escape added in the period is a case, any stage" "$F" '.cases[] | select(.item == "0-8") | .signals == ["escaped:plan"] and .pr == null'
expect_jq "an escape marked before the period is not, even when its line moved in it" "$F" '[.cases[].item] | index("0-9") | not'
expect_jq "a blocked 0-1 is not hidden by a case for 0-10" "$F" '.cases[] | select(.item == "0-1") | .pr == null and .stage == "implement"'
expect_jq "unmerged /ready PRs are not in the data at all" "$F" '[.readyPrs[].number] == [20, 21]'
expect_jq "a blocked run before the period is not"   "$F" '[.cases[].item] | index("0-11") | not'
expect_jq "acceptance read whatever the field order" "$F" '.cases[] | select(.pr == 16) | .signals == ["acceptance:REJECT"]'
expect_jq "the backlog convention line is not a case" "$F" '[.cases[] | select(.signals[0] | startswith("escaped")) | .item] | sort == ["0-12", "0-23", "0-40", "0-8"]'
expect_jq "a marker the case parser cannot read is a case, not dropped" "$F" '.cases[] | select(.item == "0-12") | .signals == ["escaped:unparsed"]'
expect_jq "every new marker in the raw list is a case" "$F" '([.escaped[] | select(test("#0-(8|12|23|40) "))] | length) == ([.cases[] | select(.signals[0] | startswith("escaped"))] | length)'
expect_jq "prose about the convention is not in the raw escaped list" "$F" '(.escaped | length) == 5 and all(.escaped[]; test("Fixes:\\*\\* #0-[0-9]"))'
expect_jq "a blocked run with a PR is not counted twice" "$F" '[.cases[] | select(.item == "0-4")] | length == 1'
echo "audit-data: who added the owner labels (backlog #0-126)"
expect_jq "a label the owner added is verified"        "$F" '.cases[] | select(.pr == 12) | .verifiedLabels == ["human:fp-docs"] and .unverifiedLabels == []'
expect_jq "a human label another account added is no case" "$F" '[.cases[].pr] | index(17) | not'
expect_jq "and it is reported as unverified, with its actor" "$F" '.unverifiedLabels | any(.pr == 17 and .label == "human:fp-security" and .actor == "bot")'
expect_jq "a label the owner re-added after the bot counts" "$F" '.cases[] | select(.pr == 18) | .signals == ["human:missed-security"]'
expect_jq "a label with no event data is unverified"   "$F" '.cases[] | select(.pr == 14) | .verifiedLabels == [] and .unverifiedLabels[0].label == "human:agree" and .unverifiedLabels[0].actor == null'
expect_jq "the raw PR labels never carry a human label" "$F" 'all(.prs[].labels[]; .name | ascii_downcase | startswith("human:") | not)'
expect_jq "a human label in other case is checked too"  "$F" '.unverifiedLabels | any(.pr == 17 and .label == "Human:fp-architecture")'
expect_jq "the raw PRs carry the verified labels"       "$F" '.prs[] | select(.number == 13) | .verifiedLabels == ["human:introduced-ready"]'
expect_jq "a blocked run without a PR is a case, with its stage" "$F" '.cases[] | select(.item == "0-6") | .pr == null and .stage == "plan" and .readyPr == null'

echo "audit-data: who added the escape markers (backlog #0-129)"
unv() { printf '.unverifiedEscapes[] | select(.line | test("#%s ")) | %s' "$1" "$2"; }   # unv <item> <jq test>
expect_jq "AC1: a marker from the owner's merged PR is a case"   "$F" '.cases[] | select(.item == "0-8") | .signals == ["escaped:plan"]'
expect_jq "AC2: one pushed with no PR is unverified, no case"    "$F" "$(unv 0-21 '.reason | test("no pull request")')"
expect_jq "AC3: one in the bot's PR is not vouched by the owner's merge" "$F" "$(unv 0-22 '.reason | test("#31 by bot")')"
expect_jq "AC4: several PRs, one merged into main and opened by the owner" "$F" '.cases[] | select(.item == "0-23") | .signals == ["escaped:queue"]'
expect_jq "AC4: a failed lookup is unverified"                    "$F" "$(unv 0-24 '.reason | test("failed")')"
expect_jq "the owner's PRs count only merged into main"           "$F" "$(unv 0-25 '.reason | test("#34 by o, merged into release; #35 by o, not merged")')"
expect_jq "the PR lookup answering no list is unverified"        "$F" "$(unv 0-27 '.reason == "the PR lookup returned no list"')"
expect_jq "the PR lookup answering no JSON is unverified"        "$F" "$(unv 0-28 '.reason | test("failed or returned no JSON")')"
expect_jq "AC5: each unverified marker is listed with its commit" "$F" '[.unverifiedEscapes[] | select(.commit | test("^[0-9a-f]{40}$"))] | length == 8'
expect_jq "no unverified marker is a case"                        "$F" '[.cases[].item] | (index("0-21"), index("0-22"), index("0-24"), index("0-25"), index("0-26"), index("0-27"), index("0-28")) == null'
echo "audit-data: a marker is traced by itself, not by its line (review round 1)"
expect_jq "the bot's status change keeps the owner's marker vouched" "$F" '[.cases[] | select(.item == "0-8")] | length == 1 and .[0].signals == ["escaped:plan"]'
expect_jq "the owner's status change does not vouch for the bot's marker" "$F" "$(unv 0-22 '.reason | test("#31 by bot")')"
expect_jq "an old marker edited in the period is not a new case" "$F" '([.cases[].item] | index("0-9") | not) and any(.escaped[]; test("#0-9 .*Status"))'
expect_jq "a marker added and moved in the period is still a case" "$F" '.cases[] | select(.item == "0-23") | .signals == ["escaped:queue"]'
expect_jq "a back-dated commit does not hide a marker of the period" "$F" '.cases[] | select(.item == "0-40") | .signals == ["escaped:ship"]'
expect_jq "a copy of an old marker is not a new case"           "$F" '([.cases[].item] | index("0-9") | not) and ([.escaped[] | select(test("#0-9 "))] | length == 1)'
expect_jq "a commit with the marker's text inside a longer line is not its origin" "$F" "$(unv 0-26 '.reason | test("#36 by bot")')"
expect_jq "AC7: the raw list drops an old marker nobody vouched for" "$F" '(any(.escaped[]; test("#0-30 ")) | not) and any(.unverifiedEscapes[]; .line | test("#0-30 "))'
expect_jq "a moved marker keeps the commit that first added it"   "$F" 'any(.escaped[]; test("#0-9 "))'
expect_jq "the convention prose is not checked at all"            "$F" 'all(.unverifiedEscapes[]; .line | test("#0-N") | not)'

echo "audit-data: the autopilot on the owner's own login (review round 1, sec-4c1e)"
summary=$(GH_OWNER_PR=1 scripts/factory/audit-data.sh 2026-10-09)
if printf '%s' "$summary" | jq -e '.ownerIsAutopilot == true and .prsWithHumanLabels == 0 and .unverifiedHumanLabels == 8' >/dev/null; then ok "the actor proves nothing: no label counts, on any PR"
else fail "owner's login: $summary"; fi
expect_jq "not even one the owner added on a bot's PR" "$F" '.prs[] | select(.number == 12) | .verifiedLabels == [] and .unverifiedLabels[0].actor == "o"'
expect_jq "and no case rests on a human label"          "$F" '[.cases[].signals[] | select(startswith("human:"))] == []'
expect_jq "AC11: no marker counts either"                "$F" '([.cases[].signals[] | select(startswith("escaped"))] == []) and (.escaped == []) and (.unverifiedEscapes | length) == 13'

echo "audit-data: the owner unknown"
summary=$(GH_FAIL_REPO=1 scripts/factory/audit-data.sh 2026-10-09)
if printf '%s' "$summary" | jq -e '.prsWithHumanLabels == 0 and .unverifiedHumanLabels == 7 and .owner == null and .unverifiedEscapes == 13' >/dev/null; then ok "no owner, no verified label or marker (fails closed)"
else fail "owner unknown: $summary"; fi
expect_jq "and no case rests on a human label"          "$F" '[.cases[].signals[] | select(startswith("human:"))] == []'

expect_jq "AC6: every marker says why"                 "$F" 'all(.unverifiedEscapes[]; .reason == "the repository owner is unknown")'
expect_jq "AC6: and no case rests on a marker"           "$F" '([.cases[].signals[] | select(startswith("escaped"))] == []) and .escaped == []'

echo "audit-data: a replace ref cannot make the owner's commit add the bot's marker (review round 1)"
# A replacement for the owner's commit that also adds 0-22's marker: honoured, the owner's PR would vouch for it.
blob=$( { git show "$new_a:BACKLOG.md"; printf '**Type:** bug · **Fixes:** #0-22 · **Escaped from:** ready\n'; } | git hash-object -w --stdin)
tree=$(git ls-tree "$new_a" | awk -F'\t' -v b="$blob" '$2 == "BACKLOG.md" { split($1, m, " "); print m[1] " " m[2] " " b "\t" $2; next } { print }' | git mktree)
git replace "$new_a" "$(git commit-tree "$tree" -p "$new_a^" -m forged)"
scripts/factory/audit-data.sh 2026-10-09 >/dev/null
expect_jq "the history read is the real one" "$F" "$(unv 0-22 '.reason | test("#31 by bot")')"
git replace -d "$new_a" >/dev/null

echo "audit-data: a textconv driver or a shallow history cannot vouch (review round 2)"
# A textconv that shows the bot's marker in every version: only the file's first commit (the owner's) would change
# its count, and its diff would seem to add it.
printf '#!/bin/sh\ncat "$1"; echo "**Type:** bug · **Fixes:** #0-22 · **Escaped from:** ready"\n' > "$WORK/textconv"
chmod +x "$WORK/textconv"
git config diff.evil.textconv "$WORK/textconv"; printf 'BACKLOG*.md diff=evil\n' > .git/info/attributes
scripts/factory/audit-data.sh 2026-10-09 >/dev/null
expect_jq "a textconv driver does not change what the history says" "$F" "$(unv 0-22 '.reason | test("#31 by bot")')"
git config --unset diff.evil.textconv; rm .git/info/attributes
git rev-parse "$new_a" > .git/shallow
scripts/factory/audit-data.sh 2026-10-09 >/dev/null
expect_jq "a shallow clone vouches for nothing" "$F" '.escaped == [] and ([.cases[].signals[] | select(startswith("escaped"))] == []) and all(.unverifiedEscapes[]; .reason | test("shallow"))'
rm .git/shallow

echo "audit-data: the marker is defined once (backlog #0-128, AC10)"
# The grep pattern 'Escaped from:\\\\' matches `Escaped from:` and two backslashes: a copy of the marker's
# pattern escaped inside audit-data.sh (backlog #0-149).
# shellcheck disable=SC1003
if [ "$(grep -c "^MARKER=" "$SRC/scripts/factory/_marker.sh")" -eq 1 ] \
   && ! grep -q 'MARKER=.*Fixes' "$SRC/scripts/factory/audit-data.sh" "$SRC/.github/scripts/check-escape-markers.sh" \
   && ! grep -q 'Escaped from:\\\\' "$SRC/scripts/factory/audit-data.sh" \
   && grep -q '_marker.sh"' "$SRC/scripts/factory/audit-data.sh"; then ok "only _marker.sh defines and reads the marker, audit-data.sh sources it"
else fail "the marker is defined or parsed outside _marker.sh, or audit-data.sh does not source it"; fi

if [ "$failures" -gt 0 ]; then echo "$failures audit-data test(s) failed"; exit 1; fi
echo "All audit-data tests passed."
