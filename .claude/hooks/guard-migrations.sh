#!/usr/bin/env bash
# ============================================================
# PreToolUse (Edit|Write|MultiEdit): refuse to change a Flyway migration that already exists on the base
# branch. Flyway checksums every applied migration; an edited one fails validation on every database
# where it ran (rule MIG-02). A migration that exists only on the current branch may still be edited.
#
# Registered in .claude/settings.json (every session) and .claude/settings.autopilot.json.
# Base: the commit recorded by the autopilot's preflight (.ai/runs/LOCK), else $FACTORY_BASE_REF, else
# origin/main, else main.
# Fails open with a warning if neither jq nor python3 is installed (interactive sessions must not break);
# the autopilot's devcontainer has jq.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

input=$(cat)
file=$(json_get "$input" '.tool_input.file_path'); rc=$?
if [ "$rc" -eq 3 ]; then
    echo "guard-migrations: neither jq nor python3 found, migration guard not applied" >&2
    exit 0
fi
[ -n "$file" ] || exit 0

case "$file" in
    */src/main/resources/db/migration/V*) ;;
    *) exit 0 ;;
esac

root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
rel=$(repo_rel "$file") || exit 0
case "$rel" in /*) exit 0 ;; esac          # outside this repository

base=$(cd "$root" && base_ref)

if git -C "$root" cat-file -e "$base:$rel" 2>/dev/null; then
    block "$rel exists on $base, so it may already be applied somewhere. Never edit an applied Flyway migration: add a new V<n> migration in the same service instead (rule MIG-02, .ai/rules/review/migration.md)."
fi
exit 0
