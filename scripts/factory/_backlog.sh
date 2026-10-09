#!/usr/bin/env bash
# ============================================================
# Parsing of BACKLOG.md, BACKLOG-DONE.md and .ai/plan/queue.md for the factory scripts. Sourced, not run.
# Text in, JSON out (jq). Portable: awk + jq, no bash 4 features, so the owner can run the checks on macOS.
#
#   backlog_json   < BACKLOG.md        → [{id, title, type, priority, status, autopilot, risk, complexity,
#                                           dependsOn:[ids], followUpOf, touches, modules:[...], order}]
#   done_json      < BACKLOG-DONE.md   → ["0-1", "0-18", …]
#   queue_json     < queue.md          → {markers: bool, rows: [{index, pos, item, why}]}
#
# Ids are bare ("0-25"), without "#". The order of an item is its row in the open-items table.
# ============================================================

# Values allowed in **Touches:** (and in the architect's planned modules) — the Maven modules of the root
# POM, plus areas outside them. scripts/factory/changed-paths.sh maps a diff onto the same vocabulary, so
# the three can be compared (.ai/rules/planning.md, "Touches"):
#   root   files at the repository root (pom.xml, README.md, CLAUDE.md, Makefile), .ai/context/,
#          architecture-tests/
#   docs   docs/          k8s   k8s/          docker   docker/          ci   .github/, scripts/
KNOWN_MODULES='["shared","service-parent","auth-service","ingestion-service","incident-service","notification-service","escalation-service","postmortem-service","oncall-service","root","docs","k8s","docker","ci"]'

backlog_json() {
    LC_ALL=C awk '
        function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
        function field(line, name,    key, i, rest, j) {
            key = "**" name ":**"; i = index(line, key); if (!i) return ""
            rest = substr(line, i + length(key)); j = index(rest, " \302\267 "); if (j) rest = substr(rest, 1, j - 1)
            gsub(/`/, "", rest); return trim(rest)
        }
        function flush() {
            if (id != "") printf "I\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", id, title, type, prio, status, auto, risk, cx, deps, fup, touches
            id = ""
        }
        /^\| *\[0-[0-9]+\]/ {
            split($0, c, "|"); tid = c[2]; sub(/^ *\[/, "", tid); sub(/\].*/, "", tid)
            printf "T\t%s\t%s\n", tid, trim(c[5]); next
        }
        /^### 0-[0-9]+\./ {
            flush(); id = $2; sub(/\.$/, "", id); title = $0; sub(/^### 0-[0-9]+\. */, "", title)
            type = prio = status = auto = risk = cx = deps = fup = touches = ""; next
        }
        /^## / { flush(); next }
        id != "" && /^\*\*Type:\*\*/ && type == "" { type = field($0, "Type"); prio = field($0, "Priority"); status = field($0, "Status") }
        id != "" && /^\*\*Autopilot:\*\*/ && auto == "" {
            auto = field($0, "Autopilot"); risk = field($0, "Risk"); cx = field($0, "Complexity")
            deps = field($0, "Depends on"); fup = field($0, "Follow-up of")
        }
        id != "" && /^\*\*Touches:\*\*/ && touches == "" { touches = field($0, "Touches") }
        END { flush() }
    ' | jq -R -s '
        split("\n") | map(select(length > 0) | split("\t")) as $rows
        | ($rows | map(select(.[0] == "T")) | to_entries
            | map({key: .value[1], value: {order: .key, priority: .value[2]}}) | from_entries) as $table
        | $rows | map(select(.[0] == "I") | {
            id: .[1], title: .[2], type: (.[3] | ascii_downcase), priority: .[4], status: .[5],
            autopilot: (.[6] | ascii_downcase), risk: (.[7] | ascii_downcase), complexity: (.[8] | ascii_downcase),
            dependsOn: [.[9] | scan("0-[0-9]+")],
            followUpOf: ([.[10] | scan("0-[0-9]+")] | first // null),
            touches: .[11],
            modules: (.[11] | gsub("\\([^)]*\\)"; "") | split(",") | map(gsub("^\\s+|\\s+$"; ""))
                      | map(select(length > 0 and . != "—" and . != "-")) | unique)
          } | . + {order: ($table[.id].order // 9999),
                   priority: (if .priority == "" then ($table[.id].priority // "") else .priority end)})'
}

done_json() {
    LC_ALL=C grep -oE '^\| *0-[0-9]+ *\|' | grep -oE '0-[0-9]+' | jq -R . | jq -s 'unique'
}

queue_json() {
    LC_ALL=C awk '
        function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
        /<!-- queue:start -->/ { on = 1; seen++; next }
        /<!-- queue:end -->/   { on = 0; seen++; next }
        on && /^\|/ {
            n = split($0, c, "|"); item = trim(c[3]); pos = trim(c[2])
            if (item == "Item" || $0 ~ /^[|: -]+$/) next
            id = ""; if (match(item, /0-[0-9]+/)) id = substr(item, RSTART, RLENGTH)
            printf "Q\t%s\t%s\t%s\n", pos, id, trim(c[4])
        }
        END { printf "M\t%d\n", seen }
    ' | jq -R -s '
        split("\n") | map(select(length > 0) | split("\t")) as $rows
        | { markers: (($rows | map(select(.[0] == "M")) | first | .[1] | tonumber) == 2),
            rows: ($rows | map(select(.[0] == "Q")) | to_entries
                   | map({index: .key, pos: .value[1], item: .value[2], why: (.value[3] // "")})) }'
}
