#!/usr/bin/env bash
# ============================================================
# PreToolUse (Edit|Write|MultiEdit) in the frontmatter of agents that may write only under some paths:
#   write-scope.sh .ai/audit/                          (audit agents)
#   write-scope.sh .ai/decisions/ .ai/work/            (architect)
#   write-scope.sh --seed                              (defect-seeder) the files of the seed run's change, exactly
#   write-scope.sh --agent <names> <prefix>...         the same, at session level, for those agents' calls only
# A path outside every given prefix (relative to the repository root) is refused. Fails closed. The settings files
# run it again with `--agent` (backlog #0-164, ADR-0041): frontmatter hooks are not promised to a Workflow's agents.
# --seed (backlog #0-172, ADR-0047): the seeder's scope is a run's, not a role's, so no prefix fits it; the hook reads
# the run from .ai/runs/SEED (seed_record, _lib.sh) and allows the files the change kept, as `git diff --name-only
# --no-renames --diff-filter=d <base> <head>` lists them: the list scripts/factory/seed-check.sh holds the seeder's
# edit to afterwards, so the check before the edit and the one after it say the same. No record for the checked-out
# branch, one that cannot be read, or a git that cannot list the change: refused. .ai/runs/ itself is never a change's
# file, so the seeder cannot write a run's row or the record.
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap 'case $? in 0|2) ;; *) echo "BLOCKED by ${0##*/}: the guard failed (exit $?), so the call is refused" >&2; exit 2 ;; esac' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }

only=""
if [ "${1:-}" = --agent ]; then
    [ "$#" -ge 2 ] && [ -n "$2" ] || block "write-scope.sh --agent needs agent names"
    only=$2
    shift 2
fi
[ "$#" -gt 0 ] || block "write-scope.sh needs at least one allowed path prefix"
seed=0
if [ "$1" = --seed ]; then
    [ "$#" -eq 1 ] || block "write-scope.sh --seed takes no prefix: the seed run names its files"
    seed=1
fi
input=$(cat)
if [ -n "$only" ] && ! applies_to_agent "$only" "$input"; then exit 0; fi
file=$(json_get "$input" '.tool_input.file_path'); rc=$?
json_checked "$rc" "this agent's write guard"
if [ -z "$file" ]; then
    file=$(json_get "$input" '.tool_input.notebook_path'); rc=$?
    json_checked "$rc" "this agent's write guard"
fi
if [ "$seed" = 0 ]; then
    require_in_repo "$file" "$@"
    exit 0
fi
require_in_repo "$file"
rel=$(repo_rel "$file") || block "not in a git repository"
run=$(seed_record) || block "the seed record (.ai/runs/SEED) cannot be read, so the write is refused"
[ -n "$run" ] || block "no seed run on the checked-out branch (.ai/runs/SEED), so the seeder may write nothing"
base=${run%% *} head=${run#* }
files=$(git -C "$(git rev-parse --show-toplevel)" diff --name-only --no-renames --diff-filter=d "$base" "$head") \
    || block "git cannot list the seed run's change, so the write is refused"
# Compared as whole strings, never as a pattern (a `*` in a name must not match another file).
while IFS= read -r f; do
    [ "$f" = "$rel" ] && exit 0
done <<<"$files"
block "the seeder may write only the files of the change it plants in: $(tr '\n' ' ' <<<"$files" | cut -c1-300)(tried $rel)"
