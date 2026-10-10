#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash), autopilot sessions only: refuse shell commands that could write to a human-owned
# path. The Edit/Write deny rules in settings.autopilot.json cover the file tools; a shell command
# (`sed -i`, `cp`, a redirect) goes around them. This is a heuristic second line: the real gates are
# CODEOWNERS on the PR and the autopilot's changed-paths check before review.
#
# Protected: the paths of .ai/rules/protected-paths.md (their one list), plus the run's own state (.git/,
#            .ai/runs/LOCK and state.json, .ai/STOP). This copy stays a literal regex, so the last line does not
#            depend on parsing that file; .github/scripts/check-protected-paths.sh fails CI when it misses an
#            entry. Fixed (backlog #0-122): .ai/audit/decisions.md was denied to Edit/Write but not covered here.
# A command that only reads (git diff/log/show, cat, grep, ls, head, tail, wc) passes.
# Also refused, whatever the verb: commands naming a secret (docker/.env, docker/secrets, application-local,
# credentials files, gh/ssh config) and `git diff --no-index`, which reads any file around the Read denies.
# `git grep` options that run a program or read files git does not track (the secrets) are refused too.
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
# A line continuation (backslash-newline) is joined by bash before it runs the command, so a pattern
# split across it (`git gr\<newline>ep`, `doc\<newline>ker/.env`) is seen by bash and not by these checks.
# No command an agent needs is written that way (review round 2, sec-b7d2).
case "$cmd" in *\\$'\n'*) block "a line continuation (backslash-newline) is not allowed: write the command on one line" ;; esac
why=$(git_grep_unsafe "$cmd"); [ -z "$why" ] || block "$why"
if printf '%s' "$cmd" | grep -Eq -- '(>|[[:space:]]tee[[:space:]]|sed[[:space:]]+(-[a-zA-Z]*i|--in-place)|(^|[[:space:];&|])(cp|mv|rm|chmod|ln)[[:space:]])[^;&|]*[[:space:]](\./)?mvnw(\.cmd)?([[:space:]]|$)'; then
    block "the command may write to the Maven wrapper (mvnw), which every verification runs"
fi

protected='(\.ai/rules|\.ai/plan/|\.ai/audit/decisions\.md|\.claude/|\.github/|architecture-tests|AGENTS\.md|scripts/factory|\.devcontainer|\.mvn/|\.git/|\.ai/runs/(LOCK|state\.json)|\.ai/STOP)'
printf '%s' "$cmd" | grep -Eq -- "$protected" || exit 0

writes='(>|[[:space:]]tee[[:space:]]|sed[[:space:]]+(-[a-zA-Z]*i|--in-place)|perl[[:space:]]+-[a-zA-Z]*i|(^|[[:space:];&|])(cp|mv|rm|rmdir|ln|chmod|chown|truncate|install|touch|mkdir)[[:space:]]|git[[:space:]]+(checkout|restore|rm|mv|apply|am|stash|reset)|(python3?|ruby|node|perl)[[:space:]])'
if printf '%s' "$cmd" | grep -Eq -- "$writes"; then
    block "the command may write to a human-owned path (.ai/rules/protected-paths.md lists them; also .git/ and the run state). Agents never change these; if the item needs it, stop and report NEEDS_HUMAN."
fi
exit 0
