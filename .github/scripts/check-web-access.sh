#!/usr/bin/env bash
# ============================================================
# Checks that only the verifier agent can reach the web, and only through its allow-list (backlog #0-160,
# ADR-0037). The allow-list itself lives once, in .claude/hooks/webfetch-allowlist.sh (`--list`); the
# hook is tested by .claude/hooks/test-hooks.sh. What this checks is the wiring around it, which a change to
# one file could break without any test noticing:
#   check-web-access.sh [settings.autopilot.json] [settings.json] [agents dir] [hook] [init-firewall.sh]
#   1. every agent definition declares `tools:` (an agent without it inherits every tool, WebFetch included);
#      no agent but verifier.md names WebFetch, none names WebSearch; the verifier has only Read and
#      WebFetch, its Read is scoped by read-scope.sh to the gate's files ($READ_SCOPE) in a hook on Read (what it
#      can read is what it could send), and the prefixes its definition names are exactly the hook's list;
#   2. settings.autopilot.json runs the hook (no argument) on WebFetch, denies WebSearch, allows WebFetch only as
#      `WebFetch(domain:<host>)` with a host of the allow-list, and every host of the list has its rule (else
#      `dontAsk` refuses what the hook passed);
#   3. settings.json runs the hook with --interactive on WebFetch and allows no WebFetch at all (an allow rule
#      there would let every agent of the owner's sessions fetch without a prompt); both settings also scope the
#      verifier's Read session-wide (read-scope.sh --agent verifier $READ_SCOPE: frontmatter hooks are documented for
#      the Agent tool, not for Workflow agents, and never run in `claude -p`);
#   4. every host of the allow-list is open in the devcontainer firewall: in its ALLOWED_DOMAINS, or github.com
#      (opened from GitHub's meta).
# Run by .github/workflows/factory-guards.yml; tested by .github/scripts/test-web-access.sh.
# ============================================================
set -uo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "::error::not inside the repository"; exit 1; }
autopilot=${1:-$ROOT/.claude/settings.autopilot.json}
shared=${2:-$ROOT/.claude/settings.json}
agents=${3:-$ROOT/.claude/agents}
hook=${4:-$ROOT/.claude/hooks/webfetch-allowlist.sh}
firewall=${5:-$ROOT/.devcontainer/init-firewall.sh}

failures=0
fail() { echo "::error::$*"; failures=$((failures+1)); }
# The verifier's read scope, exactly: the files the gate writes, not the rest of .ai/runs/ (its logs and what the
# branch's own code writes there during the gate; review round 3 of #0-160).
READ_SCOPE="--name 'gate-r*.json' --name 'dependency-tree-r*.txt' .ai/runs/"
# ends_with <suffix> — the lines of stdin, a trailing `"` and blanks dropped, of which one ends with the suffix.
ends_with() {
    awk -v want="$1" '{ l = $0; sub(/"?[[:space:]]*$/, "", l)
        if (length(l) >= length(want) && substr(l, length(l) - length(want) + 1) == want) ok = 1 }
        END { exit !ok }'
}

list=$(bash "$hook" --list) && [ -n "$list" ] || { echo "::error::$hook --list printed no allow-list"; exit 1; }
hosts=$(printf '%s\n' "$list" | sed -n 's|/.*||p' | sort -u)

# 1. Agent definitions. The frontmatter is the block between the first two `---` lines.
front() { awk '/^---[[:space:]]*$/ {n++; next} n == 1' "$1"; }
found_verifier=0
for f in "$agents"/*.md; do
    [ -f "$f" ] || continue
    name=$(basename "$f" .md)
    tools=$(front "$f" | sed -n 's/^tools:[[:space:]]*//p')
    [ -n "$tools" ] || { fail "$f declares no tools: (an agent without it gets every tool, WebFetch included)"; continue; }
    words=$(printf '%s' "$tools" | tr ',' '\n' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//; s/(.*//')
    if [ "$name" = verifier ]; then
        found_verifier=1
        extra=$(printf '%s\n' "$words" | grep -vxE 'Read|WebFetch' | tr '\n' ' ')
        [ -z "$extra" ] || fail "$f: the verifier may have only Read and WebFetch, not: $extra"
        # The scope counts only as the command of a hook whose matcher names Read (review round 1: a text match
        # passed with the matcher changed to Write, which leaves Read unscoped).
        front "$f" | awk -v want="/.claude/hooks/read-scope.sh $READ_SCOPE" '
            /^[[:space:]]*-?[[:space:]]*matcher:/ { m = $0; sub(/^[^:]*:[[:space:]]*/, "", m); gsub(/"/, "", m); next }
            { l = $0; sub(/"?[[:space:]]*$/, "", l)
              if (length(l) >= length(want) && substr(l, length(l) - length(want) + 1) == want) {
                  n = split(m, a, "|"); for (i = 1; i <= n; i++) if (a[i] == "Read") ok = 1 } }
            END { exit !ok }' \
            || fail "$f: the verifier's Read must be scoped by a Read hook running read-scope.sh $READ_SCOPE"
        # Its prose names the allow-list for the agent: the same entries as the hook, no other (review round 1).
        # The backticks are Markdown's code quotes, matched as text (backlog #0-149).
        # shellcheck disable=SC2016
        named=$(grep -oE '`[a-z0-9.-]+\.[a-z]+/[^`]*`' "$f" | tr -d '`' | sort -u)
        [ "$named" = "$(printf '%s\n' "$list" | sort -u)" ] \
            || fail "$f names the prefixes ($(tr '\n' ' ' <<<"$named")), not the hook's list ($(tr '\n' ' ' <<<"$list"))"
    else
        printf '%s\n' "$words" | grep -qx WebFetch && fail "$f: only the verifier may have WebFetch"
    fi
    printf '%s\n' "$words" | grep -qx WebSearch && fail "$f: no agent may have WebSearch"
done
[ "$found_verifier" = 1 ] || fail "$agents has no verifier.md"

# Prints the command of every PreToolUse hook whose matcher names the tool ($2, WebFetch by default).
fetch_hooks() {
    jq -r --arg t "${2:-WebFetch}" '.hooks.PreToolUse[]? | select(.matcher | split("|") | index($t)) | .hooks[]?.command' "$1"
}
read_scoped() {   # read_scoped <settings> — the verifier's session-level read scope is there, on a Read hook
    fetch_hooks "$1" Read | ends_with "/.claude/hooks/read-scope.sh --agent verifier $READ_SCOPE" \
        || fail "$1 does not run read-scope.sh --agent verifier $READ_SCOPE on Read"
}

# 2. The autopilot's settings.
jq -e . "$autopilot" >/dev/null 2>&1 || { echo "::error::cannot read $autopilot"; exit 1; }
fetch_hooks "$autopilot" | grep -qE '/\.claude/hooks/webfetch-allowlist\.sh"?$' \
    || fail "$autopilot does not run webfetch-allowlist.sh (without --interactive) on WebFetch"
jq -r '.permissions.deny[]?' "$autopilot" | grep -qx WebSearch || fail "$autopilot does not deny WebSearch"
read_scoped "$autopilot"
allowed=$(jq -r '.permissions.allow[]? | select(startswith("WebFetch"))' "$autopilot")
while IFS= read -r rule; do
    [ -n "$rule" ] || continue
    h=$(printf '%s' "$rule" | sed -n 's/^WebFetch(domain:\([a-z0-9.-]*\))$/\1/p')
    if [ -z "$h" ]; then fail "$autopilot allows $rule: only WebFetch(domain:<host>) of the allow-list"
    elif ! grep -qxF "$h" <<<"$hosts"; then fail "$autopilot allows WebFetch(domain:$h), a host not on the allow-list of $hook"
    fi
done <<<"$allowed"
while IFS= read -r h; do
    grep -qxF "WebFetch(domain:$h)" <<<"$allowed" || fail "$autopilot has no WebFetch(domain:$h): dontAsk would refuse what the hook allows"
done <<<"$hosts"

# 3. The shared settings.
jq -e . "$shared" >/dev/null 2>&1 || { echo "::error::cannot read $shared"; exit 1; }
fetch_hooks "$shared" | grep -qE '/\.claude/hooks/webfetch-allowlist\.sh"? --interactive$' \
    || fail "$shared does not run webfetch-allowlist.sh --interactive on WebFetch"
read_scoped "$shared"
extra=$(jq -r '.permissions.allow[]? | select(startswith("WebFetch"))' "$shared" | tr '\n' ' ')
[ -z "$extra" ] || fail "$shared allows $extra: no WebFetch allow rule in the owner's sessions"

# 4. The devcontainer firewall.
domains=$(awk '/^ALLOWED_DOMAINS=\(/ {on=1; next} on && /^\)/ {exit} on' "$firewall" | tr -s ' \t' '\n' | grep .)
[ -n "$domains" ] || { echo "::error::cannot read ALLOWED_DOMAINS of $firewall"; exit 1; }
while IFS= read -r h; do
    [ "$h" = github.com ] && continue
    grep -qxF "$h" <<<"$domains" || fail "$firewall does not open $h, which the verifier's allow-list names"
done <<<"$hosts"

if [ "$failures" -gt 0 ]; then echo "$failures web access problem(s)"; exit 1; fi
echo "Web access: only the verifier fetches, from $(grep -c . <<<"$hosts") hosts, each allowed in the autopilot's settings and open in the firewall."
