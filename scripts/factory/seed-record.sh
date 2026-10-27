#!/usr/bin/env bash
# ============================================================
# Appends one /seed-bugs run's row to .ai/runs/benchmark/results.md (backlog #0-177). Prints one JSON object:
#   {"appended": true, "file"}   or   {"appended": false, "reason"}
#
#   seed-record.sh <run id> <pattern> <rule> <expected> <caught yes|no> <blocking yes|no> <caught by> <no answer>
#   <caught by>, <no answer>: dimensions joined by commas, or - for none
#
# The row used to go to the tracked .ai/audit/benchmark/results.md, on main: seed-branch.sh then refused the next run
# until the owner had committed it, and main takes changes only through a pull request, so a series meant a merge
# between every two runs (2026-10-25: the P-10 row parked in a scratchpad while P-11 ran). The rows of a series now
# gather in the gitignored .ai/runs/ (beside the gate's artifacts), where they never dirty main, and reach the tracked
# file in one pull request after the series (docs/ai-factory.md). The audit reads only the tracked file.
# A script, not an agent: the row was written by audit-synthesis, whose answer nothing checked (#0-171); the workflow
# now reads `appended`. A run id already in either file is refused, so a rerun cannot count twice.
# Run by the seed-bugs workflow through factory-ops, back on main. Tested by .github/scripts/test-seed-branch.sh.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"

refuse() { jq -n --arg r "$1" '{appended: false, reason: $r}'; exit 0; }
FILE="$RUNS_DIR/benchmark/results.md"
TRACKED=.ai/audit/benchmark/results.md
DIMENSIONS=" general architecture security performance migration docs k8s "

# dimensions <csv | -> — the list as the table writes it ("a, b", or — for none); refuses anything but known dimensions
dimensions() {
    local csv=$1 out="" d parts
    [ "$csv" = - ] && { printf '%s' '—'; return 0; }
    [[ $csv =~ ^[a-z][a-z0-9]*(,[a-z][a-z0-9]*)*$ ]] || return 1
    IFS=, read -r -a parts <<<"$csv"
    for d in "${parts[@]}"; do
        case "$DIMENSIONS" in *" $d "*) ;; *) return 1 ;; esac
        out="${out:+$out, }$d"
    done
    printf '%s' "$out"
}

[ $# -eq 8 ] || refuse "usage: seed-record.sh <run id> <pattern> <rule> <expected> <caught yes|no> <blocking yes|no> <caught by> <no answer>"
run=$1 pattern=$2 rule=$3 expected=$4 caught=$5 blocking=$6 by=$7 none=$8
[[ $run =~ ^[A-Za-z0-9._-]+$ ]] || refuse "not a run id: ${run:0:40}"
[[ $pattern =~ ^P-[0-9]+$ ]] || refuse "not a pattern id: ${pattern:0:40}"
[[ $rule =~ ^[A-Z][A-Z0-9]*-[0-9]+$ ]] || refuse "not a rule id: ${rule:0:40}"
case "$DIMENSIONS" in *" $expected "*) ;; *) refuse "not a dimension: ${expected:0:40}" ;; esac
for v in "$caught" "$blocking"; do case "$v" in yes|no) ;; *) refuse "not yes or no: ${v:0:40}" ;; esac; done
[[ $by =~ ^(-|[a-z][a-z0-9]*(,[a-z][a-z0-9]*)*)$ ]] || refuse "not a list of dimensions: ${by:0:80}"
# "Also caught by": the dimensions that caught it besides the expected one.
IFS=, read -r -a caught_by <<<"$by"
also=$(printf '%s\n' "${caught_by[@]}" | grep -vxF -e "$expected" -e - | paste -sd, -)
also=$(dimensions "${also:--}") || refuse "not a list of dimensions: ${by:0:80}"
none=$(dimensions "$none") || refuse "not a list of dimensions: ${none:0:80}"

# The run id is the first column; matched literally there only (review round 1: a run id that is also a dimension, a
# pattern or yes/no matched another column and was refused).
for f in "$FILE" "$TRACKED"; do
    [ -f "$f" ] || continue
    # Read whole, then searched: in `cut | grep -q` under pipefail, grep's early exit gives cut a SIGPIPE, and the
    # pipeline's failure read as "no such id" (review round 3).
    ids=$(cut -d'|' -f2 "$f") || refuse "cannot read $f"
    grep -qxF -e " $run " <<<"$ids" && refuse "the run id $run is in $f already"
done
mkdir -p "$(dirname "$FILE")" || refuse "cannot make $(dirname "$FILE")"
if [ ! -s "$FILE" ]; then
    printf '%s\n%s\n' '| Run | Pattern | Rule | Expected | Caught by expected | Blocking | Also caught by | No answer |' \
        '|---|---|---|---|---|---|---|---|' > "$FILE" || refuse "cannot write $FILE"
fi
printf '| %s | %s | %s | %s | %s | %s | %s | %s |\n' "$run" "$pattern" "$rule" "$expected" "$caught" "$blocking" "$also" "$none" \
    >> "$FILE" || refuse "cannot write $FILE"
jq -n --arg f "$FILE" '{appended: true, file: $f}'
