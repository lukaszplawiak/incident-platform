#!/usr/bin/env bash
# ============================================================
# PreToolUse (Edit|Write|MultiEdit): refuse to change a Flyway migration that already exists on the base
# branch. Flyway checksums every applied migration; an edited one fails validation on every database
# where it ran (rule MIG-02). A migration that exists only on the current branch may still be edited.
#
# Registered in .claude/settings.json (every session) and .claude/settings.autopilot.json.
# Base: the commit recorded by the autopilot's preflight (.ai/runs/LOCK), else on a /seed-bugs run's branch the run's
# base (.ai/runs/SEED, backlog #0-172: migration_base_ref), else $FACTORY_BASE_REF, else origin/main, else main.
# Fails open with a warning if neither jq nor python3 is installed (interactive sessions must not break);
# the autopilot's devcontainer has jq; the same for a base ref that does not exist (below). Every other
# failure refuses (backlog #0-162): a parser that fails, a git that fails, a crash, a helper that does not load.
# Both exceptions are named in FAC-S04 (.ai/rules/review/factory.md).
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap 'case $? in 0|2) ;; *) echo "BLOCKED by ${0##*/}: the guard failed (exit $?), so the call is refused" >&2; exit 2 ;; esac' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }

input=$(cat)
file=$(json_get "$input" '.tool_input.file_path'); rc=$?
if [ "$rc" -eq 3 ]; then
    echo "guard-migrations: neither jq nor python3 found, migration guard not applied" >&2
    exit 0
fi
json_checked "$rc" "the migration guard"
[ -n "$file" ] || exit 0

case "$file" in
    */src/main/resources/db/migration/V*) ;;
    *) exit 0 ;;
esac

# Only a migration's path gets here, so a git that fails refuses the edit rather than skipping the check
# (backlog #0-162, security review: these two read `|| exit 0`).
root=$(git rev-parse --show-toplevel 2>/dev/null) || block "git cannot find the repository, so the edit of a migration is refused"
rel=$(repo_rel "$file") || block "cannot resolve $file in the repository, so the edit of a migration is refused"
case "$rel" in /*) exit 0 ;; esac          # outside this repository

base=$(cd "$root" && migration_base_ref)
# A base that does not exist (a clone without main or origin/main) cannot say what is applied: the edit goes
# through with a warning, as without jq above; CI's edited-migration check (check-factory-guards.sh rule 4)
# compares with the PR's base.
if ! git -C "$root" rev-parse --verify --quiet "$base^{commit}" >/dev/null 2>&1; then
    echo "guard-migrations: base $base not found, migration guard not applied" >&2
    exit 0
fi

# ls-tree tells the three answers apart, where `cat-file -e` read any failure (a damaged object, an I/O error)
# as "not on the base" (security review round 2 of #0-162): the path printed = on the base, nothing = not on it,
# a non-zero status = git failed, refused. Literal pathspecs and an exact comparison: a `*` or `?` in a file name
# must not match another file.
on_base=$(git -C "$root" --literal-pathspecs ls-tree --name-only "$base" -- "$rel" 2>/dev/null) \
    || block "git cannot list $base, so the edit of a migration is refused"
if [ "$on_base" = "$rel" ]; then
    block "$rel exists on $base, so it may already be applied somewhere. Never edit an applied Flyway migration: add a new V<n> migration in the same service instead (rule MIG-02, .ai/rules/review/migration.md)."
fi
exit 0
