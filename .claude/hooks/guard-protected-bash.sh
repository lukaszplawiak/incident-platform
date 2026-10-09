#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash), autopilot sessions only: refuse shell commands that could write to a human-owned
# path. The Edit/Write deny rules in settings.autopilot.json cover the file tools; a shell command
# (`sed -i`, `cp`, a redirect) goes around them. This is a heuristic second line: the real gates are
# CODEOWNERS on the PR and the autopilot's changed-paths check before review.
#
# Protected: .ai/rules/  .claude/  .github/  architecture-tests/  AGENTS.md  scripts/factory/ (and factory-admin/)
#            .devcontainer/  .mvn/  .git/  mvnw
# A command that only reads (git diff/log/show, cat, grep, ls, head, tail, wc) passes.
# Also refused, whatever the verb: commands naming a secret (docker/.env, docker/secrets, application-local,
# credentials files, gh/ssh config) and `git diff --no-index`, which reads any file around the Read denies.
# Fails closed without jq or python3.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

input=$(cat)
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
[ "$rc" -eq 3 ] && block "jq or python3 is required for the autopilot's hooks"
[ -n "$cmd" ] || exit 0

secrets='(docker/\.env|docker/secrets|application-local|\.credentials|\.claude\.json|config/gh|\.ssh/|\.netrc|\.git-credentials|settings-security\.xml|--no-index)'
if printf '%s' "$cmd" | grep -Eq -- "$secrets"; then
    block "the command names a secret file or reads files outside the repository's history (--no-index). Agents never read secrets."
fi
if printf '%s' "$cmd" | grep -Eq -- '(>|[[:space:]]tee[[:space:]]|sed[[:space:]]+(-[a-zA-Z]*i|--in-place)|(^|[[:space:];&|])(cp|mv|rm|chmod|ln)[[:space:]])[^;&|]*[[:space:]](\./)?mvnw(\.cmd)?([[:space:]]|$)'; then
    block "the command may write to the Maven wrapper (mvnw), which every verification runs"
fi

protected='(\.ai/rules|\.ai/plan/|\.claude/|\.github/|architecture-tests|AGENTS\.md|scripts/factory|\.devcontainer|\.mvn/|\.git/|\.ai/runs/(LOCK|state\.json)|\.ai/STOP)'
printf '%s' "$cmd" | grep -Eq -- "$protected" || exit 0

writes='(>|[[:space:]]tee[[:space:]]|sed[[:space:]]+(-[a-zA-Z]*i|--in-place)|perl[[:space:]]+-[a-zA-Z]*i|(^|[[:space:];&|])(cp|mv|rm|rmdir|ln|chmod|chown|truncate|install|touch|mkdir)[[:space:]]|git[[:space:]]+(checkout|restore|rm|mv|apply|am|stash|reset)|(python3?|ruby|node|perl)[[:space:]])'
if printf '%s' "$cmd" | grep -Eq -- "$writes"; then
    block "the command may write to a human-owned path (.ai/rules/, .ai/plan/, .claude/, .github/, architecture-tests/, AGENTS.md, scripts/factory*/, .devcontainer/, .mvn/, .git/). Agents never change these; if the item needs it, stop and report NEEDS_HUMAN."
fi
exit 0
