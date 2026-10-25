#!/usr/bin/env bash
# ============================================================
# Checks that the copies of the protected paths that cannot read their one definition still cover it.
#   check-protected-paths.sh [settings.json] [hook] [CODEOWNERS]
# The list: .ai/rules/protected-paths.md (its "protected", "build-config" and "runtime" lists), read by
# scripts/factory/_protected.sh. For every entry:
#   - .claude/settings.autopilot.json denies both Edit and Write (a directory as "<dir>**");
#   - .claude/hooks/guard-protected-bash.sh blocks a shell write to it (`echo x > <path>`, under a
#     directory a file in it) — tested by running the hook, not by reading its regex;
#   - for the "protected" list: in .github/CODEOWNERS the rule GitHub applies — the LAST one that matches
#     (`*`, "/<path>", or "/<dir>/" above it) — names at least one owner, so a PR changing it needs one. A
#     later, narrower line without an owner would take the review away, and fails here. Patterns without a
#     leading "/" (match at any depth) are not interpreted; the file uses none. (The build-config list is
#     guarded on PRs by check-factory-guards.sh rule 6, which reads this list itself.)
# And the other way (backlog #0-168): every Edit(./…)/Write(./…) deny of the settings, and every alternative of
# the hook's `protected=` regex, is an entry of those lists. Before it, a copy could protect a path the list did
# not hold, and the code that reads the list (changed-paths.sh, next-item.sh, check-factory-guards.sh rule 6)
# would not apply it: .node-version was in both copies, and in the documents, before it was on the list. The
# hook's line is read as a flat alternation (one path per alternative, no group); anything else fails. The hook's
# separate block for mvnw/mvnw.cmd is not read: it can protect nothing but those two, both entries of the list.
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

entries=$(protected_paths protected && protected_paths build-config && protected_paths runtime) \
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

# The other way: nothing in a copy that the lists lack. An entry is compared as written (a directory with its
# slash), so a deny of `X/**` must be the entry `X/` and a deny of `X` the entry `X`.
while IFS= read -r rule; do
    [ -n "$rule" ] || continue
    path=${rule#*\(./}; path=${path%\)}
    case "$path" in *'/**') path=${path%'**'} ;; esac
    grep -qxF -- "$path" <<<"$entries" \
        || { echo "::error::$settings denies $rule, but $PROTECTED_PATHS_FILE lists no $path: add it to a list there, or drop the deny"; failures=$((failures+1)); }
done < <(grep -E '^(Edit|Write)\(\./.+\)$' <<<"$deny")
# A path deny in any other spelling (`Edit(.x)`, `Edit(/x)`, `Edit(**/x)`) would escape the comparison above: refused,
# so every Edit/Write deny of a path is written the one way this check reads (review round 1 of #0-168).
while IFS= read -r rule; do
    [ -n "$rule" ] || continue
    echo "::error::$settings denies $rule: write a path deny as Edit(./<path>) or Write(./<path>), the form this check compares with $PROTECTED_PATHS_FILE"; failures=$((failures+1))
done < <(grep -E '^(Edit|Write)\(' <<<"$deny" | grep -vE '^(Edit|Write)\(\./.+\)$')
line=$(grep -E "^protected='\(" "$hook" 2>/dev/null)
if [ "$(grep -c . <<<"$line")" -ne 1 ]; then
    echo "::error::$hook: expected exactly one line protected='(...)' (backlog #0-168)"; failures=$((failures+1))
else
    alts=${line#"protected='("}; alts=${alts%")'"}
    if [ "$alts" = "$line" ] || [[ $alts == *[\(\)]* ]]; then
        echo "::error::$hook: protected= must be one flat alternation in ( ), no group inside (backlog #0-168)"; failures=$((failures+1))
    else
        bare=$(sed -E 's:/$::' <<<"$entries")
        while IFS= read -r alt; do
            literal=$(sed -E 's/\\(.)/\1/g' <<<"$alt")
            grep -qxF -- "${literal%/}" <<<"$bare" \
                || { echo "::error::$hook protects $literal, but $PROTECTED_PATHS_FILE lists no ${literal%/} (file) or ${literal%/}/ (directory): add it to a list there, or drop it from the hook"; failures=$((failures+1)); }
        done < <(tr '|' '\n' <<<"$alts")
    fi
fi

if [ "$failures" -gt 0 ]; then echo "$failures protected-path copy problem(s)"; exit 1; fi
echo "Protected paths: settings.autopilot.json and guard-protected-bash.sh cover all $(grep -c . <<<"$entries") entries and protect nothing else, CODEOWNERS the $(grep -c . <<<"$owned") owner-only ones."
