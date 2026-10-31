#!/usr/bin/env bash
# ============================================================
# Checks the seed branch after the defect-seeder, before the panel reviews it (backlog #0-173, review round 1). Prints
# one JSON object:
#   {"ok": true, "files": [...], "hunks": [{"file", "start", "end"}]}   or   {"ok": false, "reason"}
#
#   seed-check.sh <branch> <base> <head> <pattern> <rule> <dimension> [<file>:<line> ...]
#
# The seeder's answer names its branch, but its word is not what the panel runs on: the checked-out tree is. A seeder
# that switched or committed would have the reviewers run on another tree's hooks and agents, the failure #0-173 fixes.
# Since #0-172 the seeder commits nothing (its Bash is read-only, readonly-bash.sh; ADR-0047): it edits, and this
# checks the edit, uncommitted. So the tree must be on <branch> (`review/<8 hex>`, #0-177) at seed-branch.sh's <head>,
# itself the one commit on <base>, with no file git does not track, and the changes may touch only files the change
# brought back and kept (the diff of <base> to <head>, without the files it deleted: seed-branch.sh's `files`, and the
# scope write-scope.sh --seed held the seeder's Edit to). `files` are the files the seeder changed: the workflow checks
# the ground truth's file among them. Changes nothing: seed-fold.sh then makes the change and the edit one commit.
# ADR-0044.
# The seeder's <rule> and <dimension> must be its <pattern>'s, as the row of .ai/audit/benchmark/patterns.md on
# origin/main has them (review round 2 of #0-180's rule scope): a clean run counts a false alarm only under that rule, so
# a seeder naming another one would turn a false alarm into "raised", and a defect run would score its catch against the
# wrong dimension. Read from origin/main, as _protected.sh reads the Owner-only list: not from the branch the seeder
# just edited.
# `hunks` are the seeder's edit, as `git diff -U0 HEAD` gives it: the lines it added or changed, in the file as the
# panel reads it (a deletion is the line where it was). They are the run's ground truth, not the seeder's one line
# (backlog #0-181): a plant has as many lines as the edit, a method and its Javadoc (clean-c01-r2: performance noted
# the job at its Javadoc, seven lines above the line the seeder named). The optional <file>:<line> places are the
# half of a defect the seeder left unedited on purpose (P-17's stale Javadoc, P-18's missing README line): at most
# two, each a regular file at HEAD and a line it has.
# Run by the seed-bugs workflow through factory-ops. Tested by .github/scripts/test-seed-branch.sh.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"

refuse() { jq -n --arg r "$1" '{ok: false, reason: $r}'; exit 0; }

PATTERNS_FILE=.ai/audit/benchmark/patterns.md

[ $# -ge 6 ] && [ $# -le 8 ] || refuse "usage: seed-check.sh <branch> <base> <head> <pattern> <rule> <dimension> [<file>:<line> ...]"
branch=$1 base=$2 head=$3 pattern=$4 rule=$5 dimension=$6
shift 6   # what is left: the places, read below as "$@" (an empty array is unbound under set -u in bash 3.2)
[[ $branch =~ $SEED_BRANCH_RE ]] || refuse "not a seed branch name (review/<8 hex>): ${branch:0:40}"
[[ $base =~ ^[0-9a-f]{40}$ ]] || refuse "not a base commit: ${base:0:40}"
[[ $head =~ ^[0-9a-f]{40}$ ]] || refuse "not a head commit: ${head:0:40}"
[[ $pattern =~ ^[PC]-[0-9]+$ ]] || refuse "not a pattern id: ${pattern:0:40}"
[[ $rule =~ ^[A-Z][A-Z0-9]*-[A-Z]?[0-9]+$ ]] || refuse "not a rule id: ${rule:0:40}"
[[ $dimension =~ ^[a-z][a-z0-9]*$ ]] || refuse "not a dimension: ${dimension:0:40}"

patterns=$(git show "refs/remotes/origin/main:$PATTERNS_FILE" 2>/dev/null) || refuse "cannot read $PATTERNS_FILE on origin/main"
# The row's id, dimension and rule columns, trimmed; the id compared whole, never as a pattern.
row=$(awk -F'|' -v id="$pattern" '
    function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
    trim($2) == id { print trim($3) " " trim($4); n++ }
    END { exit n == 1 ? 0 : 1 }' <<<"$patterns") || refuse "$pattern is not one row of $PATTERNS_FILE on origin/main"
[ "$row" = "$dimension $rule" ] \
    || refuse "the seeder's truth ($dimension, $rule) is not $pattern's (${row/ /, }) in $PATTERNS_FILE"

on=$(git rev-parse --abbrev-ref HEAD 2>/dev/null) || refuse "cannot read HEAD"
[ "$on" = "$branch" ] || refuse "the tree is on $on, not on $branch"
[ "$(git rev-parse HEAD)" = "$head" ] || refuse "HEAD is not $head: the seeder committed (or moved the branch)"
[ "$(git rev-parse --verify --quiet "$head^1")" = "$base" ] || refuse "$head is not the commit on top of $base"
untracked=$(git ls-files --others --exclude-standard 2>/dev/null) || refuse "cannot list the untracked files"
[ -z "$untracked" ] || refuse "files git does not track: $(tr '\n' ' ' <<<"$untracked" | cut -c1-200)"

allowed=$(git diff --name-only --no-renames --diff-filter=d "$base" "$head") || refuse "cannot list the change's files"
planted=$(git diff --name-only --no-renames HEAD) || refuse "cannot list the seeder's files"
[ -n "$planted" ] || refuse "the seeder changed nothing"
# Every planted path must be one of the change's: compared line by line, never as a pattern.
outside=$(grep -vxFf <(printf '%s\n' "$allowed") <<<"$planted"); rc=$?
[ "$rc" -le 1 ] || refuse "cannot compare the seeder's files with the change's"
[ -z "$outside" ] || refuse "the seeder changed files outside the change: $(tr '\n' ' ' <<<"$outside" | cut -c1-200)"

# The places the seeder left unedited: a plain path (no `..`, no leading `/`), a regular file at HEAD (not a directory,
# nor a symlink, whose target awk would read from outside the tree: review round 1), and a line it has.
for place in "$@"; do
    [[ $place =~ ^([A-Za-z0-9._@+-]+(/[A-Za-z0-9._@+-]+)*):([1-9][0-9]*)$ ]] || refuse "not a <file>:<line>: ${place:0:80}"
    file=${BASH_REMATCH[1]} line=${BASH_REMATCH[3]}
    [[ /$file/ != */../* ]] || refuse "not a <file>:<line>: ${place:0:80}"
    mode=$(git ls-tree HEAD -- "$file" 2>/dev/null | cut -d' ' -f1)
    case "$mode" in 100644|100755) ;; '') refuse "$file is not a file the branch tracks" ;; *) refuse "$file is not a regular file (mode $mode)" ;; esac
    # Lines as an editor numbers them: a last line without a newline counts (wc -l would not count it).
    lines=$(awk 'END { print NR }' "$file" 2>/dev/null) || refuse "cannot read $file"
    [ "$line" -le "$lines" ] || refuse "$file has no line $line (it has $lines)"
done

# The seeder's edit, hunk by hunk: `+++ b/<path>` then `@@ -a,b +c,d @@` (no ,d: one line; d = 0: lines removed after
# line c). The text's own lines are marked `>` and `<`, not `+` and `-`: an added line whose text starts `++ ` showed
# as `+++ …` and was read as a header, giving the next hunks another file (review round 1). A path git quotes (a
# non-ASCII byte, a quote), one with a space (git leaves it unquoted and ends the header with a tab: review round 2)
# and a deleted file (`+++ /dev/null`) have no line to score: refused.
diff=$(git -c core.quotePath=true diff -U0 --no-renames --no-color --no-ext-diff \
    --output-indicator-new='>' --output-indicator-old='<' HEAD) || refuse "cannot read the seeder's edit"
hunks=$(awk '
    /^\+\+\+ / { f = substr($0, 5); if (f !~ /^b\// || f ~ /\t/) { bad = 1; exit } f = substr(f, 3); next }
    /^@@ / { split($3, p, ","); start = substr(p[1], 2) + 0; count = p[2] + 0
             end = (count == 0) ? start : start + count - 1; if (start < 1) start = 1; if (end < start) end = start
             printf "%s\t%d\t%d\n", f, start, end; seen = 1 }
    END { exit bad ? 2 : (seen ? 0 : 3) }' <<<"$diff"); rc=$?
[ "$rc" -ne 2 ] || refuse "the seeder deleted a file, or changed one whose path git quotes or ends with a tab: no line to score"
[ "$rc" -eq 0 ] || refuse "the seeder's edit has no hunk to score"
jq -n --arg f "$planted" --arg h "$hunks" '{ok: true, files: ($f | split("\n") | map(select(. != ""))),
    hunks: ($h | split("\n") | map(select(. != "") | split("\t") | {file: .[0], start: (.[1] | tonumber), end: (.[2] | tonumber)}))}'
