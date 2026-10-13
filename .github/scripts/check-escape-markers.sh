#!/usr/bin/env bash
# ============================================================
# Checks that every escape marker in the backlog is one the audits can read.
#   check-escape-markers.sh [file...]        (default: BACKLOG.md BACKLOG-DONE.md)
# A line with `**Escaped from:** <stage>` (MARKER_ATTEMPT, in any case, bold or not) must be a marker exactly as
# written, `**Fixes:** #0-<digits>` before `**Escaped from:**` (MARKER), both read from scripts/factory/_marker.sh,
# the one definition audit-data.sh uses too. Otherwise it fails with the file and line. The convention's own prose
# (`**Escaped from:** <stage>`, `#0-N`) has no stage and passes.
# Fixed (backlog #0-128): such a line was neither in the audits' raw `escaped` list nor a case, so neither audit saw
# that escape. A marker with a stage the case parser cannot read (`Review-General`) passes: the audit reports it as
# an `escaped:unparsed` case.
# Run by .github/workflows/factory-guards.yml; tested by .github/scripts/test-escape-markers.sh.
# ============================================================
set -uo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "::error::not inside the repository"; exit 1; }
cd "$ROOT" || exit 1
MARKER="" MARKER_ATTEMPT=""
. scripts/factory/_marker.sh 2>/dev/null || { echo "::error::cannot read scripts/factory/_marker.sh"; exit 1; }
if [ -z "$MARKER" ] || [ -z "$MARKER_ATTEMPT" ]; then
    echo "::error::scripts/factory/_marker.sh defines no MARKER or MARKER_ATTEMPT"; exit 1
fi
[ $# -gt 0 ] || set -- BACKLOG.md BACKLOG-DONE.md

failures=0
for f in "$@"; do
    [ -f "$f" ] || { echo "::error::$f does not exist"; failures=$((failures+1)); continue; }
    attempts=$(grep -niE "$MARKER_ATTEMPT" "$f"); rc=$?
    # 1 is "no line matched"; anything above is grep failing (an unreadable file, a broken pattern).
    [ "$rc" -le 1 ] || { echo "::error::grep failed on $f"; failures=$((failures+1)); continue; }
    [ -n "$attempts" ] || continue
    while IFS= read -r hit; do
        [ -n "$hit" ] || continue
        echo "::error file=$f,line=${hit%%:*}::an escape marker not written as '**Fixes:** #0-<digits> · **Escaped from:** <stage>' is invisible to the audits: ${hit#*:}"
        failures=$((failures+1))
    done <<EOF
$(printf '%s\n' "$attempts" | grep -vE "$MARKER")
EOF
done

if [ "$failures" -gt 0 ]; then echo "$failures malformed escape marker(s)"; exit 1; fi
echo "Escape markers are well-formed."
