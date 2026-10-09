#!/usr/bin/env bash
# Shared helpers for the PreToolUse hooks in this directory. Sourced, not run.
#
# A hook gets the tool call as JSON on stdin (tool_name, tool_input.command / file_path, cwd). Exit 0 lets
# the call go through the normal permission flow, exit 2 blocks it and shows stderr to the agent.

# json_get <json> <jq path> — prints the value or nothing. Uses jq, falls back to python3.
json_get() {
    local json=$1 path=$2
    if command -v jq >/dev/null 2>&1; then
        printf '%s' "$json" | jq -r "$path // empty" 2>/dev/null
    elif command -v python3 >/dev/null 2>&1; then
        printf '%s' "$json" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for key in sys.argv[1].lstrip(".").split("."):
    d = d.get(key) if isinstance(d, dict) else None
print("" if d is None else d)' "$path" 2>/dev/null
    else
        return 3
    fi
}

block() {
    echo "BLOCKED by $(basename "$0"): $*" >&2
    exit 2
}

# repo_rel <path> — the path relative to the repository root, or the absolute path when it is outside.
# Symlinks are resolved on both sides first: on macOS /var is /private/var, and a project can sit under a
# symlinked directory; git reports the resolved root, a tool may pass the unresolved path. Comparing them
# as strings would put a file of this repository "outside" it — a guard keyed on the path would then let
# it through (fail open) or refuse a legitimate write. Works for files that do not exist yet: the nearest
# existing directory is resolved, the rest is kept as given. Returns 1 outside a git repository.
repo_rel() {
    local f=$1 root d rest=""
    root=$(git rev-parse --show-toplevel 2>/dev/null) || return 1
    root=$(cd "$root" 2>/dev/null && pwd -P) || return 1
    case "$f" in
        /*) ;;
        *) f="$(pwd -P)/${f#./}" ;;
    esac
    d=$f
    while [ ! -d "$d" ]; do rest="/$(basename "$d")$rest"; d=$(dirname "$d"); done
    d=$(cd "$d" 2>/dev/null && pwd -P) || return 1
    [ "$d" = / ] && d=""
    f="$d$rest"
    case "$f" in
        "$root"/*) printf '%s\n' "${f#"$root"/}" ;;
        *) printf '%s\n' "$f" ;;
    esac
}

# The base an autopilot run compares against is the commit the preflight recorded in .ai/runs/LOCK
# (`<runId> <sha>`), not a ref: a ref can be moved by the session (`git fetch . HEAD:refs/remotes/origin/main`,
# a local branch named origin/main), a recorded sha cannot. Outside a run: $FACTORY_BASE_REF, origin/main, main.
base_ref() {
    local root lock sha
    root=$(git rev-parse --show-toplevel 2>/dev/null) || root=.
    lock="$root/.ai/runs/LOCK"
    if [ -f "$lock" ]; then
        sha=$(awk '{print $2}' "$lock" 2>/dev/null)
        case "$sha" in [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) echo "$sha"; return ;; esac
    fi
    if [ -n "${FACTORY_BASE_REF:-}" ]; then echo "$FACTORY_BASE_REF"; return; fi
    if git rev-parse --verify --quiet refs/remotes/origin/main >/dev/null 2>&1; then echo refs/remotes/origin/main; else echo refs/heads/main; fi
}

# The factory's own scripts must be exactly what the base branch has: the item branch is untrusted code,
# and a gate that the branch can rewrite is no gate. Prints a reason and returns 1 when they differ.
factory_scripts_intact() {
    local base; base=$(base_ref)
    git rev-parse --verify --quiet "$base" >/dev/null 2>&1 || { echo "base ref $base not found"; return 1; }
    if ! git diff --quiet "$base" -- scripts/factory 2>/dev/null; then echo "scripts/factory differs from $base"; return 1; fi
    if [ -n "$(git ls-files --others --exclude-standard -- scripts/factory 2>/dev/null)" ]; then echo "untracked files in scripts/factory"; return 1; fi
    return 0
}
