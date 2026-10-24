#!/usr/bin/env bash
# ============================================================
# Tests for .github/scripts/check-web-access.sh: it passes on the repository's own files and fails when an
# agent other than the verifier gets WebFetch (or any agent WebSearch, or one declares no tools), when the
# verifier gets more than Read and WebFetch or loses its read scope or has it widened past the gate's files, when
# the autopilot's settings lose the hook, allow a host off the list or a bare WebFetch, when the owner's settings
# allow WebFetch or lose the hook, and when the firewall does not open a host of the list (backlog #0-160).
# Run: .github/scripts/test-web-access.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
CHECK="$SRC/.github/scripts/check-web-access.sh"
AP="$SRC/.claude/settings.autopilot.json"
SH="$SRC/.claude/settings.json"
AG="$SRC/.claude/agents"
HOOK="$SRC/.claude/hooks/webfetch-allowlist.sh"
FW="$SRC/.devcontainer/init-firewall.sh"

# expect <0|1> <name> [settings.autopilot.json] [settings.json] [agents dir] [hook] [firewall]
expect() {
    local want=$1 name=$2; shift 2
    local rc
    (cd "$SRC" && "$CHECK" "${1:-$AP}" "${2:-$SH}" "${3:-$AG}" "${4:-$HOOK}" "${5:-$FW}" >/dev/null 2>&1); rc=$?
    if [ "$rc" -eq "$want" ]; then echo "  ok: $name"; else echo "::error::$name: expected exit $want, got $rc"; failures=$((failures+1)); fi
}
# agents_with <file> <sed expression> — a copy of the agents directory with one definition changed.
agents_with() {
    local dir; dir=$(mktemp -d "$WORK/agents.XXXX")
    cp "$AG"/*.md "$dir/"
    sed -i.bak "$2" "$dir/$1" && rm -f "$dir/$1.bak"
    printf '%s' "$dir"
}

echo "check-web-access"
expect 0 "the repository's own files"

expect 1 "a reviewer with WebFetch"            "$AP" "$SH" "$(agents_with review-security.md 's/^tools: .*/tools: Read, Grep, Glob, Bash, WebFetch/')"
expect 1 "an agent with WebSearch"             "$AP" "$SH" "$(agents_with planner.md 's/^tools: .*/&, WebSearch/')"
expect 1 "an agent without tools:"             "$AP" "$SH" "$(agents_with shipper.md '/^tools:/d')"
expect 1 "the verifier with Bash"              "$AP" "$SH" "$(agents_with verifier.md 's/^tools: .*/tools: Read, WebFetch, Bash/')"
expect 1 "the verifier with WebSearch"         "$AP" "$SH" "$(agents_with verifier.md 's/^tools: .*/tools: Read, WebFetch, WebSearch/')"
expect 1 "the verifier without its read scope" "$AP" "$SH" "$(agents_with verifier.md 's/ \.ai\/runs\/"$/ ."/')"
expect 1 "the verifier's read scope widened to .ai/runs/" "$AP" "$SH" "$(agents_with verifier.md "s/ --name '[^']*'//g")"
expect 1 "the verifier's read scope with another name" "$AP" "$SH" "$(agents_with verifier.md "s/'dependency-tree-r\*\.txt'/'*'/")"
expect 1 "the verifier's read scope on a Write hook" "$AP" "$SH" "$(agents_with verifier.md 's/matcher: "Read"/matcher: "Write"/')"
expect 0 "the verifier's read scope on Read among others" "$AP" "$SH" "$(agents_with verifier.md 's/matcher: "Read"/matcher: "Glob|Read"/')"
# The backticks are Markdown's code quotes in the sed expressions, not command substitutions (backlog #0-149).
# shellcheck disable=SC2016
expect 1 "the verifier names a prefix the hook lacks" "$AP" "$SH" "$(agents_with verifier.md 's/`spring.io\/security\/`/`spring.io\/security\/`, `example.com\/x\/`/')"
# shellcheck disable=SC2016
expect 1 "the verifier omits a prefix of the hook"    "$AP" "$SH" "$(agents_with verifier.md 's/, `spring.io\/security\/`//')"
expect 1 "the verifier missing"                "$AP" "$SH" "$(d=$(agents_with verifier.md 's/x/x/'); rm "$d/verifier.md"; printf '%s' "$d")"

jq '.hooks.PreToolUse |= map(select(.matcher != "WebFetch"))' "$AP" > "$WORK/ap-no-hook.json"
expect 1 "autopilot: the hook removed"         "$WORK/ap-no-hook.json"
jq '(.hooks.PreToolUse[] | select(.matcher == "WebFetch") | .hooks[0].command) |= . + " --interactive"' "$AP" > "$WORK/ap-interactive.json"
expect 1 "autopilot: the hook in --interactive" "$WORK/ap-interactive.json"
jq '.permissions.allow += ["WebFetch"]' "$AP" > "$WORK/ap-bare.json"
expect 1 "autopilot: a bare WebFetch allow"    "$WORK/ap-bare.json"
jq '.permissions.allow += ["WebFetch(domain:example.com)"]' "$AP" > "$WORK/ap-host.json"
expect 1 "autopilot: a host off the list"      "$WORK/ap-host.json"
jq '.permissions.allow += ["WebFetch(domain:*.osv.dev)"]' "$AP" > "$WORK/ap-wild.json"
expect 1 "autopilot: a wildcard host"          "$WORK/ap-wild.json"
jq '.permissions.allow |= map(select(. != "WebFetch(domain:osv.dev)"))' "$AP" > "$WORK/ap-missing.json"
expect 1 "autopilot: a host of the list not allowed" "$WORK/ap-missing.json"
jq '.permissions.deny |= map(select(. != "WebSearch"))' "$AP" > "$WORK/ap-search.json"
expect 1 "autopilot: WebSearch not denied"     "$WORK/ap-search.json"
jq '(.hooks.PreToolUse[] | select(.matcher == "WebFetch") | .matcher) = "Read|WebFetch"' "$AP" > "$WORK/ap-matcher.json"
expect 0 "autopilot: a matcher naming WebFetch among others" "$WORK/ap-matcher.json"

jq '.hooks.PreToolUse |= map(select(.matcher != "Read"))' "$AP" > "$WORK/ap-no-read.json"
expect 1 "autopilot: the verifier's session read scope removed" "$WORK/ap-no-read.json"
jq '(.hooks.PreToolUse[] | select(.matcher == "Read") | .hooks[0].command) |= sub(" --agent verifier"; "")' "$AP" > "$WORK/ap-read-all.json"
expect 1 "autopilot: the read scope without --agent verifier" "$WORK/ap-read-all.json"
jq --arg names " --name '[^']*'" '(.hooks.PreToolUse[] | select(.matcher == "Read") | .hooks[0].command) |= gsub($names; "")' "$AP" > "$WORK/ap-read-runs.json"
expect 1 "autopilot: the read scope widened to .ai/runs/" "$WORK/ap-read-runs.json"
jq --arg names " --name '[^']*'" '(.hooks.PreToolUse[] | select(.matcher == "Read") | .hooks[0].command) |= gsub($names; "")' "$SH" > "$WORK/sh-read-runs.json"
expect 1 "owner: the read scope widened to .ai/runs/" "$AP" "$WORK/sh-read-runs.json"
jq '.hooks.PreToolUse |= map(select(.matcher != "Read"))' "$SH" > "$WORK/sh-no-read.json"
expect 1 "owner: the verifier's session read scope removed" "$AP" "$WORK/sh-no-read.json"
jq '.hooks.PreToolUse |= map(select(.matcher != "WebFetch"))' "$SH" > "$WORK/sh-no-hook.json"
expect 1 "owner: the hook removed"             "$AP" "$WORK/sh-no-hook.json"
jq '.permissions.allow = ["WebFetch(domain:osv.dev)"]' "$SH" > "$WORK/sh-allow.json"
expect 1 "owner: a WebFetch allow rule"        "$AP" "$WORK/sh-allow.json"

sed 's/ api\.osv\.dev / /' "$FW" > "$WORK/fw-missing.sh"
expect 1 "firewall: a host of the list not open" "$AP" "$SH" "$AG" "$HOOK" "$WORK/fw-missing.sh"
printf 'echo nothing\n' > "$WORK/fw-none.sh"
expect 1 "firewall: no ALLOWED_DOMAINS"        "$AP" "$SH" "$AG" "$HOOK" "$WORK/fw-none.sh"

printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/hook-empty.sh"
expect 1 "a hook with no allow-list"           "$AP" "$SH" "$AG" "$WORK/hook-empty.sh"

if [ "$failures" -gt 0 ]; then echo "$failures web access test(s) failed"; exit 1; fi
echo "All web access tests passed."
