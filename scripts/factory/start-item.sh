#!/usr/bin/env bash
# ============================================================
# Starts one autopilot item on its own branch (backlog #0-209). Prints one JSON object:
#   {"ok": true, "branch", "workDir", "head"}   or   {"ok": false, "reasons": [...]}
#
#   start-item.sh <base sha> <item> <branch> <run id>      e.g. start-item.sh <40 hex> 0-25 fix/0-25-queue-version 20261109-120000
#
# On a clean main at <base sha> (the commit the preflight recorded), and only for an item that BACKLOG.md on that
# commit holds once, `**Autopilot:** ready` and with a status that starts with Open (read as next-item.sh reads them,
# backlog_json), with no branch <branch> and no .ai/work/<item> yet:
#   1. `git switch -c <branch> <base sha>`;
#   2. .ai/work/<item>/ as a copy of the whole of .ai/work/_template/ (a file added to the template is copied too);
#      progress.md gets the item's title line and the start's entry (`- [start] …`), `NEXT: architect`;
#   3. the item's `**Status:** Open` becomes `**Status:** In progress — autopilot run <run id>` (that field only);
#   4. one commit, `chore(factory): start backlog #<item>`, body `Autopilot run <run id>.`: code wrote it, so it has no
#      Co-Authored-By trailer (which run and which agent wrote a factory commit is #0-212).
# On any refusal after the branch exists, the tree goes back to main and the branch and the work directory are gone, so
# a failed start leaves nothing that locks the item for the next run (next-item.sh skips an item whose branch exists);
# if that cleanup itself fails, the reason names the branch left. A script killed half-way (factory-ops stopped after
# `git switch -c`) cleans up nothing: the workflow's stop then says "no answer", and the branch is the owner's to delete.
#
# Fixed (backlog #0-209): the picker did these steps itself, with commands it composed under allow rules
# (`Bash(mkdir -p .ai/work/*)`, `Bash(cp .ai/work/_template/* .ai/work/*)`), and each refusal cost a turn: run
# 20261106-203806 was stopped by a `cp -r` Claude Code refuses whatever a rule says (#0-207), and a refusal after
# `git switch -c` left the item's branch behind (#0-206). The steps have one right result, so they are code; the picker
# only checks the item and names the branch, and the two rules are gone. The model of seed-branch.sh (ADR-0044).
# Run by the backlog-autopilot workflow through factory-ops, after the picker's answer and before `state.sh branch`.
# Tested by .github/scripts/test-start-item.sh.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"
. "$(dirname "$0")/_backlog.sh"
. "$(dirname "$0")/_protected.sh"

refuse() { jq -n --arg r "$1" '{ok: false, reasons: [$r]}'; exit 0; }
made=""      # the branch, once this script created it
undo() {     # undo <reason>: back to main, the half-made branch and work directory gone, then refuse
    git reset --quiet --hard >/dev/null 2>&1
    # Still on the item's branch, nothing is deleted (the directory would be tracked files there, and git refuses to
    # delete the branch it is on): the reason names the branch, which locks the item until the owner deletes it.
    git switch --quiet main >/dev/null 2>&1 \
        || refuse "$1; the branch $made was left, as git switch main failed: delete it (git branch -D $made)"
    if [ -n "$made" ]; then
        # Untracked after the switch back (it was never on main): the copy of a template that failed half-way.
        rm -rf ".ai/work/$item"
        git branch -D --quiet "$made" >/dev/null 2>&1 \
            || refuse "$1; the branch $made was left, as git branch -D failed: delete it (git branch -D $made)"
    fi
    refuse "$1"
}

[ $# -eq 4 ] || refuse "usage: start-item.sh <base sha> <item> <branch> <run id>"
base=$1 item=$2 branch=$3 run=$4
[[ $base =~ ^[0-9a-f]{40}$ ]] || refuse "not a commit sha (40 hex): ${base:0:48}"
[[ $item =~ ^0-[0-9]+$ ]] || refuse "not an item id (0-<n>): ${item:0:40}"
# The workflow's own rule for the picker's branch (backlog-autopilot.js), held here too: the script is the one that
# creates it.
[[ $branch =~ ^[a-z]+/${item}-[A-Za-z0-9._-]+$ ]] || refuse "not a branch of #$item (<type>/$item-<slug>): ${branch:0:80}"
[[ $run =~ ^[0-9]{8}-[0-9]{6}$ ]] || refuse "not a run id (preflight.sh's yyyymmdd-hhmmss): ${run:0:40}"

[ "$(git rev-parse --abbrev-ref HEAD 2>/dev/null)" = main ] || refuse "not on main: an item starts from main"
dirty=$(git status --porcelain --untracked-files=all 2>/dev/null) || refuse "cannot read the working tree's status"
[ -z "$dirty" ] || refuse "the working tree is not clean"
[ "$(git rev-parse HEAD 2>/dev/null)" = "$base" ] || refuse "main is not at the base commit $base the preflight recorded"
git show-ref --verify --quiet "refs/heads/$branch" && refuse "the branch $branch exists already"
[ ! -e ".ai/work/$item" ] || refuse ".ai/work/$item exists already: a directory left by an earlier run is the owner's to delete"
[ -f .ai/work/_template/progress.md ] || refuse "no .ai/work/_template/progress.md"

# The item as next-item.sh reads it (backlog_json, _backlog.sh), from the base commit: one parser of the backlog, so
# the start checks the same fields the choice did, and text in an item that quotes `**Status:** Open` or
# `**Autopilot:** ready` counts for nothing (review round 1 of #0-209: a parser of its own here refused #0-209 itself).
# The protected-path regex is what backlog_json needs to fill `protected`; next-item.sh judges that field, not this.
item_re=$({ protected_paths protected "$base" && protected_paths build-config "$base"; } | paths_regex free) \
    || refuse "cannot read the lists of .ai/rules/protected-paths.md on the base commit"
entries=$(git show "$base:BACKLOG.md" | backlog_json "$item_re" | jq -c --arg id "$item" '[.[] | select(.id == $id)]') \
    || refuse "cannot read BACKLOG.md on the base commit"
count=$(jq length <<<"$entries")
[ "$count" = 1 ] || refuse "BACKLOG.md has $count sections ### $item. on the base commit, not one"
autopilot=$(jq -r '.[0].autopilot' <<<"$entries") status=$(jq -r '.[0].status' <<<"$entries")
title=$(jq -r '.[0].title' <<<"$entries")
[ "$autopilot" = ready ] || refuse "#$item is not **Autopilot:** ready on the base commit (${autopilot:-none})"
# next-item.sh skips an item whose status is not Open, for the same reason: the two must agree, or the item is chosen,
# refused here and chosen again on every run, until the breaker stops the autopilot (review round 1 of #0-209).
case "$status" in Open*) ;; *) refuse "#$item's status is not Open on the base commit: ${status:0:80}" ;; esac

# A switch that fails leaves no branch (a name git refuses, e.g. one holding `..`, which the pattern above lets
# through): nothing to undo. `made` is set only once the branch exists, so a refusal never names a branch that is not
# there (review round 2 of #0-209).
git switch --quiet -c "$branch" "$base" 2>/dev/null || refuse "cannot create the branch $branch"
made=$branch
mkdir -p ".ai/work/$item" 2>/dev/null || undo "cannot create .ai/work/$item"
cp -R .ai/work/_template/. ".ai/work/$item/" 2>/dev/null || undo "cannot copy .ai/work/_template to .ai/work/$item"

progress=".ai/work/$item/progress.md"
tmp=$(mktemp) || undo "cannot make a temporary file"
trap 'rm -f "$tmp"' EXIT
# The template's first line is its title placeholder (`# <item id> <item title>`); the rest stays as it is.
if ! { printf '# #%s %s\n' "$item" "$title" && tail -n +2 "$progress" &&
        printf -- "- [start] started by start-item.sh; branch \`%s\`; base \`%s\`\nNEXT: architect\n" "$branch" "$base"; } >"$tmp"; then
    undo "cannot write $progress"
fi
cat "$tmp" >"$progress" || undo "cannot write $progress"

# Only the item's own status field, on the line backlog_json reads it from and as it reads it: the first `**Type:**`
# line of its section, which ends where backlog_json ends it (the next `### 0-<n>.` or `## ` heading, so a sub-heading
# of the item's own does not), with any spacing before the value (backlog_json trims it). Its value starts with Open
# (checked above). Read differently here, an item next-item.sh chooses would be refused on every run (review round 2).
LC_ALL=C awk -v h="### $item. " -v to="**Status:** In progress — autopilot run $run" '
    index($0, h) == 1 { f = 1; print; next }
    f && (/^### 0-[0-9]+\./ || /^## /) { f = 0 }
    f && !done && /^\*\*Type:\*\*/ {
        if (!match($0, /\*\*Status:\*\*[ \t]*Open/)) exit 3
        $0 = substr($0, 1, RSTART - 1) to substr($0, RSTART + RLENGTH); done = 1
    }
    { print }
    END { if (!done) exit 3 }' BACKLOG.md >"$tmp" || undo "cannot set the status of #$item in BACKLOG.md"
cat "$tmp" >BACKLOG.md || undo "cannot write BACKLOG.md"
changed=$(git diff --numstat -- BACKLOG.md) || undo "cannot read the change to BACKLOG.md"
[ "$changed" = "$(printf '1\t1\tBACKLOG.md')" ] || undo "the status change touched more than one line of BACKLOG.md"

git add BACKLOG.md ".ai/work/$item" || undo "cannot stage the start of #$item"
git commit --quiet -m "chore(factory): start backlog #$item" -m "Autopilot run $run." || undo "cannot commit the start of #$item"
head=$(git rev-parse HEAD) || undo "cannot read the start commit"
jq -n --arg b "$branch" --arg w ".ai/work/$item" --arg h "$head" '{ok: true, branch: $b, workDir: $w, head: $h}'
