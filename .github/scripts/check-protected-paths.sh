#!/usr/bin/env bash
# ============================================================
# Checks that the copies of the protected paths that cannot read their one definition still cover it.
#   check-protected-paths.sh [settings.json] [hook] [CODEOWNERS]
# The list: .ai/rules/protected-paths.md (both its "protected" and "build-config" lists), read by
# scripts/factory/_protected.sh. For every entry:
#   - .claude/settings.autopilot.json denies both Edit and Write (a directory as "<dir>**");
#   - .claude/hooks/guard-protected-bash.sh blocks a shell write to it (`echo x > <path>`, under a
#     directory a file in it) — tested by running the hook, not by reading its regex;
#   - for the "protected" list: in .github/CODEOWNERS the rule GitHub applies — the LAST one that matches
#     (`*`, "/<path>", or "/<dir>/" above it) — names at least one owner, so a PR changing it needs one. A
#     later, narrower line without an owner would take the review away, and fails here. Patterns without a
#     leading "/" (match at any depth) are not interpreted; the file uses none. (The build-config list is
#     guarded on PRs by check-factory-guards.sh rule 6, which reads this list itself.)
# Those copies stay literal on purpose: the last line of defence must not depend on parsing a Markdown file.
# Fixed (backlog #0-122): the copies had drifted (the hook did not cover .ai/audit/decisions.md).
# Run by .github/workflows/factory-guards.yml; tested by .github/scripts/test-protected-paths.sh.
# ============================================================
set -uo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "::error::not inside the repository"; exit 1; }
settings=${1:-$ROOT/.claude/settings.autopilot.json}
hook=${2:-$ROOT/.claude/hooks/guard-protected-bash.sh}
codeowners=${3:-$ROOT/.github/CODEOWNERS}
cd "$ROOT" || exit 1
. scripts/factory/_protected.sh

entries=$(protected_paths protected && protected_paths build-config) \
    || { echo "::error::cannot read the lists of $PROTECTED_PATHS_FILE"; exit 1; }
deny=$(jq -r '.permissions.deny[]?' "$settings" 2>/dev/null) || { echo "::error::cannot read $settings"; exit 1; }
[ -n "$deny" ] || { echo "::error::$settings has no deny rules"; exit 1; }
owned=$(protected_paths protected) || { echo "::error::cannot read the protected list of $PROTECTED_PATHS_FILE"; exit 1; }
owner_rules=$(grep -vE '^[[:space:]]*(#|$)' "$codeowners" 2>/dev/null) \
    || { echo "::error::cannot read $codeowners"; exit 1; }

failures=0
while IFS= read -r p; do
    [ -n "$p" ] || continue
    case "$p" in */) rule="./$p**"; probe="${p}protected-paths-probe.txt" ;; *) rule="./$p"; probe=$p ;; esac
    for tool in Edit Write; do
        grep -qxF "$tool($rule)" <<<"$deny" || { echo "::error::$settings does not deny $tool($rule) ($PROTECTED_PATHS_FILE lists $p)"; failures=$((failures+1)); }
    done
    call=$(jq -cn --arg c "echo x > $probe" '{tool_name: "Bash", tool_input: {command: $c}}')
    printf '%s' "$call" | bash "$hook" >/dev/null 2>&1
    [ $? -eq 2 ] || { echo "::error::$hook lets a shell write to $probe through ($PROTECTED_PATHS_FILE lists $p)"; failures=$((failures+1)); }
done <<<"$entries"

# CODEOWNERS: GitHub applies the last matching line; it must name an owner. Probed for the entry itself and
# for every path under it that has a line of its own (a narrower line could take part of it away). The entry
# also needs a line of its own ("/<path>" or "/<dir>/" above it): the shadow-mode catch-all `*` (backlog
# #0-113) matches every path, so without this a deleted line would go unnoticed until phase 3 removes `*`.
set -f   # `*` is a CODEOWNERS pattern here, not a glob
last_owners() {   # last_owners <path> — prints "<pattern> <owner count> <own>" of the last line matching it;
                  # <own> is 1 when a line other than `*` matches it
    local path=$1 line pattern last="" owners=0 own=0
    while IFS= read -r line; do
        # Split into words on purpose, the pattern and its owners; no glob expansion (set -f above).
        # shellcheck disable=SC2086
        set -- $line
        pattern=${1:-}; shift || true
        case "$pattern" in
            '*'|"$path") ;;
            */) case "$path" in "$pattern"*) ;; *) continue ;; esac ;;
            *) continue ;;
        esac
        last=$pattern; owners=$#
        [ "$pattern" = '*' ] || own=1
    done <<<"$owner_rules"
    [ -n "$last" ] && echo "$last $owners $own"
}
while IFS= read -r p; do
    [ -n "$p" ] || continue
    probes="/$p"
    case "$p" in */)
        probes="$probes"$'\n'"$(awk -v d="/$p" 'index($1, d) == 1 && $1 != d {print $1}' <<<"$owner_rules")" ;;
    esac
    while IFS= read -r probe; do
        [ -n "$probe" ] || continue
        res=$(last_owners "$probe")
        read -r last owners own <<<"$res"
        if [ -z "$res" ] || [ "$own" != 1 ]; then
            echo "::error::$codeowners has no rule for $probe ($PROTECTED_PATHS_FILE lists $p; the shadow-mode '*' does not count)"; failures=$((failures+1))
        elif [ "$owners" -eq 0 ]; then
            echo "::error::$codeowners: the last rule matching $probe is '$last' with no owner ($PROTECTED_PATHS_FILE lists $p)"; failures=$((failures+1))
        fi
    done <<<"$probes"
done <<<"$owned"
set +f

if [ "$failures" -gt 0 ]; then echo "$failures protected-path copy problem(s)"; exit 1; fi
echo "Protected paths: settings.autopilot.json and guard-protected-bash.sh cover all $(grep -c . <<<"$entries") entries, CODEOWNERS the $(grep -c . <<<"$owned") owner-only ones."
