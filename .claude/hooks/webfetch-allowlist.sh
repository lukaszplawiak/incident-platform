#!/usr/bin/env bash
# ============================================================
# PreToolUse (WebFetch), session-level: only the verifier agent fetches, and only from an allow-list
# (backlog #0-160, ADR-0037).
#
#   webfetch-allowlist.sh                 (settings.autopilot.json) every WebFetch is checked: an agent other
#                                         than the verifier, and the session itself, are refused
#   webfetch-allowlist.sh --interactive   (settings.json) only the verifier's WebFetch is checked; the owner's
#                                         session and its other agents keep Claude Code's own prompts
#   webfetch-allowlist.sh --list          prints the allow-list, one prefix per line (read by
#                                         .github/scripts/check-web-access.sh), and exits
#
# Why a hook and not permission rules: a bare `deny: WebFetch` removes the tool for the whole session, subagents
# included, and no allow rule or hook can give it back to one agent; a subagent has no permission rules of its
# own; and a `WebFetch(domain:…)` rule matches the host only, for every agent, so `github.com/search?q=<data>`
# would pass it. Session-level hooks run in subagents too, and their input carries `agent_type` (the agent's
# name, also for an agent the Workflow runtime spawns: checked 2026-10-23, the spike of #0-160; the session's
# own calls carry none). The allow rules in settings.autopilot.json only let `dontAsk` run what this hook passed.
#
# A URL passes only when: https, a lowercase host, no user info, port, query string or fragment, no `%`, `..`,
# `//` or backslash in the path (a server may decode or normalise them into another path), and host + path
# start with an entry below. A path can still carry data to an allowed host: the verifier reads only
# .ai/runs/ (read-scope.sh in its definition), so it has little to send (ADR-0037).
# Every refusal says what the agent can do instead (backlog #0-123). Fails closed.
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap 'case $? in 0|2) ;; *) echo "BLOCKED by ${0##*/}: the guard failed (exit $?), so the call is refused" >&2; exit 2 ;; esac' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }
# (Here first, in review round 2 of #0-160: the autopilot's WebFetch allow rules are by host, so this hook
# failing open would let any agent reach the six hosts.)

# Host + path prefix. OSV aggregates GHSA, NVD and vendor advisories with fixed-version ranges; Maven Central
# answers "does this version exist"; the vendor pages by path prefix only. A host other than github.com and
# repo1.maven.org must also be in .devcontainer/init-firewall.sh (check-web-access.sh checks both).
ALLOW_LIST='api.osv.dev/v1/
osv.dev/vulnerability/
github.com/advisories/
repo1.maven.org/maven2/
tomcat.apache.org/security-
spring.io/security/'

VERIFIER=verifier
HINT="Fetch only https://<one of these prefixes><id>, with no query string: $(printf '%s' "$ALLOW_LIST" | tr '\n' ' ')(for example https://osv.dev/vulnerability/GHSA-xxxx-xxxx-xxxx or https://repo1.maven.org/maven2/<group path>/<artifact>/maven-metadata.xml). If none of them can settle the claim, answer it as unverifiable."

mode=${1:-}
case "$mode" in
    --list) printf '%s\n' "$ALLOW_LIST"; exit 0 ;;
    ''|--interactive) ;;
    *) block "unknown option: $mode" ;;
esac

input=$(cat)
agent=$(json_get "$input" '.agent_type'); rc=$?
json_checked "$rc" "the WebFetch guard"
if [ "$agent" != "$VERIFIER" ]; then
    [ "$mode" = --interactive ] && exit 0
    block "WebFetch is for the $VERIFIER agent only (this is ${agent:-the session itself}): an agent that reads untrusted text must not be able to send data out. Mark the claim unverified instead; the verifier checks it after the panel."
fi

url=$(json_get "$input" '.tool_input.url'); rc=$?
json_checked "$rc" "the WebFetch guard"
[ -n "$url" ] || block "no url in the WebFetch call. $HINT"
case "$url" in
    https://*) ;;
    *) block "https only: $url. $HINT" ;;
esac
rest=${url#https://}
host=${rest%%/*}
[ "$host" != "$rest" ] || block "no path in $url. $HINT"
path=/${rest#*/}
case "$host" in
    *[!a-z0-9.-]*|'') block "the host must be a plain lowercase name, without user info or port: $host. $HINT" ;;
esac
# '\' is one literal backslash, matched as a character (backlog #0-149).
# shellcheck disable=SC1003
case "$path" in
    *'?'*|*'#'*) block "no query string or fragment: a query can carry data out. $HINT" ;;
    *'%'*|*'..'*|*'//'*|*'\'*) block "no %-encoding, '..', '//' or backslash in the path. $HINT" ;;
    *[!A-Za-z0-9._~/+-]*) block "the path holds a character outside letters, digits and . _ ~ / + -: $url. $HINT" ;;
esac
while IFS= read -r prefix; do
    [ -n "$prefix" ] || continue
    case "$host$path" in "$prefix"*) exit 0 ;; esac
done <<<"$ALLOW_LIST"
block "not on the allow-list: $host$path. $HINT"
