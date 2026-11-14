#!/usr/bin/env bash
# ============================================================
# Tests for .github/scripts/run-agent-command-tests.sh (backlog #0-241): in a temporary repository holding a copy of
# it beside a stub test-agent-commands.sh, the stub runs on every event but a pull request that changes none of the
# inputs; for each input class, a pull request that changes one runs it (AC2), and so do a rename or a deletion out of
# .claude/, a name git would quote, a push, a run outside GitHub Actions and an event whose base or head cannot be read or diffed (fail
# closed). The tests' exit code is the script's when they run.
# Needs git and jq.
# Run: .github/scripts/test-run-agent-command-tests.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }

REPO=$WORK/repo
git init -q -b main "$REPO"
g() { git -C "$REPO" -c user.email=t@t -c user.name=t "$@"; }
mkdir -p "$REPO/.github/scripts" "$REPO/.claude/agents"
cp "$SRC/.github/scripts/run-agent-command-tests.sh" "$REPO/.github/scripts/"
# The stub: says it ran, exits with STUB_EXIT (0 by default).
# shellcheck disable=SC2016 # the stub's own text: ${STUB_EXIT:-0} is expanded when the stub runs, not here
printf '#!/usr/bin/env bash\necho STUB-RAN\nexit "${STUB_EXIT:-0}"\n' > "$REPO/.github/scripts/test-agent-commands.sh"
chmod +x "$REPO/.github/scripts/"*.sh
echo agent > "$REPO/.claude/agents/picker.md"
echo readme > "$REPO/README.md"
g add -A && g commit -q -m base
BASE=$(g rev-parse HEAD)

# change <path…> — on a branch from BASE, change (or create) each path; prints the head commit.
change() {
    g checkout -q -B case "$BASE"
    local p
    for p in "$@"; do mkdir -p "$REPO/$(dirname "$p")"; echo "$RANDOM" >> "$REPO/$p"; done
    g add -A && g commit -q -m case && g rev-parse HEAD
}
# payload <event> <base> <head> — writes an event payload, prints its path.
payload() {
    local f=$WORK/event-$RANDOM.json
    jq -n --arg b "$2" --arg h "$3" '{pull_request: {base: {sha: $b}, head: {sha: $h}}}' > "$f"
    echo "$f"
}
# expect <name> <ran|skipped> <wanted exit> <env assignments…> — runs the copy with the env given.
expect() {
    local name=$1 want=$2 code=$3 out rc
    shift 3
    out=$(env -i PATH="$PATH" HOME="$HOME" "$@" "$REPO/.github/scripts/run-agent-command-tests.sh" 2>&1); rc=$?
    if [ "$rc" -ne "$code" ]; then fail "$name: exit $rc, wanted $code ($out)"; return; fi
    case "$want" in
        ran)
            if grep -q '^STUB-RAN$' <<<"$out" && grep -q '^agent command check tests: run (' <<<"$out"; then ok "$name"
            else fail "$name: the tests did not run ($out)"; fi ;;
        skipped)
            if ! grep -q 'STUB-RAN' <<<"$out" && grep -q 'skipped, no input of the agent command check changed' <<<"$out"; then
                ok "$name"
            else fail "$name: the tests were not skipped ($out)"; fi ;;
    esac
}

echo "run-agent-command-tests.sh: when nothing it reads changed"
H=$(change README.md docs/x.md)
expect "a pull request changing no input skips the tests"            skipped 0 GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$(payload pull_request "$BASE" "$H")"
expect "a review of such a pull request skips them too"             skipped 0 GITHUB_EVENT_NAME=pull_request_review GITHUB_EVENT_PATH="$(payload pull_request_review "$BASE" "$H")"
expect "a path that only starts like an input (.claudex/) is no input" skipped 0 GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$(payload pull_request "$BASE" "$(change .claudex/a .ruby-version-old)")"

echo "run-agent-command-tests.sh: each input class runs them (AC2)"
for p in .claude/agents/picker.md .claude/settings.json .claude/hooks/guard.sh .claude/skills/s/SKILL.md \
         .github/scripts/check-agent-commands.rb .github/scripts/test-agent-commands.sh \
         .github/scripts/run-agent-command-tests.sh .github/workflows/factory-guards.yml \
         .github/actions/setup-ruby/action.yml .ruby-version .ai/rules/protected-paths.md scripts/factory/start-item.sh; do
    # change appends a line to the path: to the stub or the script copy that line comes after their exit or exec,
    # so both still run as before.
    H=$(change "$p")
    expect "a change to $p" ran 0 GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$(payload pull_request "$BASE" "$H")"
done
g checkout -q -B case "$BASE"
g mv .claude/agents/picker.md README-picker.md && g commit -q -m rename
expect "a rename out of .claude/ (the old path counts)" ran 0 GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$(payload pull_request "$BASE" "$(g rev-parse HEAD)")"
H=$(change '.claude/skills/ü/SKILL.md')
expect "a name git would quote (a byte outside ASCII)" ran 0 GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$(payload pull_request "$BASE" "$H")"
g checkout -q -B case "$BASE"
g rm -q .claude/agents/picker.md && g commit -q -m delete
expect "a deletion in .claude/"                        ran 0 GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$(payload pull_request "$BASE" "$(g rev-parse HEAD)")"

echo "run-agent-command-tests.sh: fails closed"
H=$(change README.md)
expect "outside GitHub Actions (ci-local.sh)"          ran 0
expect "a push"                                        ran 0 GITHUB_EVENT_NAME=push GITHUB_EVENT_PATH="$(payload push "$BASE" "$H")"
expect "no payload file"                               ran 0 GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$WORK/none.json"
echo 'not json' > "$WORK/bad.json"
expect "a payload that is no JSON"                     ran 0 GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$WORK/bad.json"
expect "a payload without a base"                      ran 0 GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$(payload pull_request "" "$H")"
expect "a base that is no commit of the clone"         ran 0 GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$(payload pull_request "$(printf 'a%.0s' {1..40})" "$H")"

echo "run-agent-command-tests.sh: the tests' exit code"
H=$(change .claude/agents/picker.md)
expect "a failing run of the tests fails the step"     ran 3 STUB_EXIT=3 GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$(payload pull_request "$BASE" "$H")"

if [ "$failures" -gt 0 ]; then echo "$failures run-agent-command-tests case(s) failed."; exit 1; fi
echo "All run-agent-command-tests cases passed."
