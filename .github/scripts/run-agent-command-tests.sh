#!/usr/bin/env bash
# ============================================================
# Runs test-agent-commands.sh unless a pull request changes none of its inputs (backlog #0-241).
#
# Why: the tests are Factory guards' longest step, 265 of its ~7 minutes (run 43577025809, PR #628): 50 cases, each a
# whole run of check-agent-commands.rb that starts every hook for each command form of the agents' lists. Only a
# change to what the tests read can change their answer, and most pull requests change none of it. The check itself
# (the step "Agent commands run in an autopilot session", on the repository as it is) runs on every event: only its
# tests are skipped.
#
# Their inputs, taken from the code (2026-11-14), each case of the tests running check-agent-commands.rb on a copy of
# .claude/ only (`fresh` in test-agent-commands.sh), with CLAUDE_PROJECT_DIR set to that copy:
#   - .claude/                                      the agents, both settings files, the hooks, the skills it reads
#   - .github/scripts/check-agent-commands.rb       the check, and test-agent-commands.sh, its cases
#   - .github/scripts/run-agent-command-tests.sh    this script, which decides whether they run
#   - .github/workflows/factory-guards.yml          the job that runs them
#   - .ruby-version, .github/actions/setup-ruby/    the Ruby they run on (ADR-0040)
#   - .ai/rules/protected-paths.md, scripts/factory/  read or named by the hooks of the repository itself, not by the
#     copies the cases make today; listed so that a hook which starts reading them through its copy is still covered
# A path missing here lets a change that breaks the tests pass a pull request untested (main's push still runs them):
# a new input of the check or its tests is added here in the same change.
#
# Fails closed: the tests run unless this can show, for a pull request, that no input changed. So they run on a push,
# in a local run (ci-local.sh: no GITHUB_EVENT_NAME), and when the event's base or head cannot be read or diffed. It
# reads GitHub's default environment (GITHUB_EVENT_NAME, GITHUB_EVENT_PATH) rather than a `${{ }}` expression, which
# ci-local.sh cannot supply and would skip the whole step for. The diff is --no-renames: a file moved out of .claude/
# shows as its old path too; and -z, so a name git would quote (a byte outside ASCII) is read as it is. Its exit code is the tests' when they run, 0 when skipped.
#
#   run-agent-command-tests.sh        (Factory guards; tested by test-run-agent-command-tests.sh)
# ============================================================
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$here/../.." && pwd)

INPUTS=(
    .claude/
    .github/scripts/check-agent-commands.rb
    .github/scripts/test-agent-commands.sh
    .github/scripts/run-agent-command-tests.sh
    .github/workflows/factory-guards.yml
    .github/actions/setup-ruby/
    .ruby-version
    .ai/rules/protected-paths.md
    scripts/factory/
)

list=""
run() {
    echo "agent command check tests: run ($1)"
    [ -z "$list" ] || rm -f "$list"
    exec "$here/test-agent-commands.sh"
}

event=${GITHUB_EVENT_NAME:-}
case "$event" in
    pull_request | pull_request_review) ;;
    '') run "not a GitHub Actions run" ;;
    *) run "a $event event" ;;
esac
payload=${GITHUB_EVENT_PATH:-}
[ -n "$payload" ] && [ -r "$payload" ] || run "no event payload to read"
base=$(jq -r '.pull_request.base.sha // empty' "$payload" 2>/dev/null) || run "the event payload cannot be read"
head=$(jq -r '.pull_request.head.sha // empty' "$payload" 2>/dev/null) || run "the event payload cannot be read"
[[ $base =~ ^[0-9a-f]{40}$ && $head =~ ^[0-9a-f]{40}$ ]] || run "the event names no base or head commit"
# -z: without it git quotes a name holding a byte outside ASCII, a tab, a quote or a backslash
# (".claude/skills/\303\274/SKILL.md", core.quotepath), which then matches no input and would skip the tests: a NUL
# ends each name instead (review round 1 of #0-241). A shell variable cannot hold a NUL, so the list goes to a file.
list=$(mktemp) || run "no temporary file for the diff"
git -C "$root" diff -z --no-renames --name-only "$base...$head" -- >"$list" 2>/dev/null \
    || run "cannot diff ${base:0:12}...${head:0:12}"

while IFS= read -r -d '' file; do
    [ -n "$file" ] || continue
    for input in "${INPUTS[@]}"; do
        case "$input" in
            */) [[ $file == "$input"* ]] && run "$file changed" ;;
            *) [[ $file == "$input" ]] && run "$file changed" ;;
        esac
    done
done <"$list"
rm -f "$list"

echo "agent command check tests: skipped, no input of the agent command check changed in ${base:0:12}...${head:0:12} (backlog #0-241)"
