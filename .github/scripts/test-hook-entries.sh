#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-hook-entries.rb (backlog #0-162 PR 2): it passes on the repository's own files, and
# fails, with the message the case names, when an entry of either settings file or of the frontmatter of an agent,
# a skill or a command (subdirectories too) loses `onFailure: "block"` (or has it one indent off, or another value),
# runs in the background, is not a command hook, runs something other than an executable hook of .claude/hooks/
# with plain arguments, or a hook without a row in the fail-closed table of test-hooks.sh, and when a settings file
# sets disableAllHooks, or a file does not parse or repeats a key in one map. Each case runs on a copy of .claude/
# with one change; a flow-style entry that is right passes, so the check reads the YAML, not text.
# Needs ruby (with Psych, as on GitHub's runners) and jq.
# Run: .github/scripts/test-hook-entries.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
# The cases write YAML and commands as literal text: `$` in single quotes is meant.
# shellcheck disable=SC2016
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
CHECK=${CHECK:-$SRC/.github/scripts/check-hook-entries.rb}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0
root=""

# fresh — a copy of the repository's .claude/ (modes kept) in a new case root, in $root.
fresh() {
    n=$((n+1)); root="$WORK/case$n"
    mkdir -p "$root" && cp -Rp "$SRC/.claude" "$root/"
    rm -f "$root/.claude/settings.local.json"
}
# expect <0|1> <name> [<text the output must contain>] — runs the check on $root, in the C locale (Ruby then reads
# US-ASCII unless told otherwise, as in a bare container: test-hooks.sh holds non-ASCII text).
expect() {
    local want=$1 name=$2 text=${3:-} out rc
    out=$(LC_ALL=C LANG=C ruby "$CHECK" "$root" 2>&1); rc=$?
    if [ "$rc" -ne "$want" ]; then
        echo "::error::$name: expected exit $want, got $rc"; printf '%s\n' "$out" | sed 's/^/    /'; failures=$((failures+1))
    elif [ -n "$text" ] && ! grep -qF -- "$text" <<<"$out"; then
        echo "::error::$name: the output does not say \"$text\""; printf '%s\n' "$out" | sed 's/^/    /'; failures=$((failures+1))
    else echo "  ok: $name"; fi
}
# jqi <file under .claude/> <jq filter> — changes a settings file of the case in place.
jqi() { jq "$2" "$root/.claude/$1" > "$root/.claude/$1.tmp" && mv "$root/.claude/$1.tmp" "$root/.claude/$1"; }
# agent <name> <frontmatter hooks block> — an agent definition of the case with these hooks.
agent() {
    printf -- '---\nname: %s\ndescription: a test agent\ntools: Bash\n%s\n---\n\nBody.\n' "$1" "$2" > "$root/.claude/agents/$1.md"
}
H='"$CLAUDE_PROJECT_DIR"/.claude/hooks'

echo "the repository's own entries"
fresh; expect 0 "every entry of the repository blocks" "block when their hook fails"

echo "settings files"
fresh; jqi settings.json 'del(.hooks.PreToolUse[0].hooks[0].onFailure)'
expect 1 "settings.json: an entry without onFailure" 'settings.json: hooks.PreToolUse[0].hooks[0] has no "onFailure": "block"'
fresh; jqi settings.autopilot.json '.hooks.PreToolUse[0].hooks[2].onFailure = "warn"'
expect 1 "settings.autopilot.json: onFailure other than block" 'hooks.PreToolUse[0].hooks[2] has no "onFailure": "block" ("warn")'
fresh; jqi settings.autopilot.json '.hooks.PreToolUse[0].onFailure = "block" | del(.hooks.PreToolUse[0].hooks[1].onFailure)'
expect 1 "settings.autopilot.json: onFailure on the group, not the entry" 'hooks.PreToolUse[0].hooks[1] has no "onFailure"'
fresh; jqi settings.autopilot.json '.hooks.PostToolUse = [{matcher: "Bash", hooks: [{type: "command", command: "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/guard-tests.sh"}]}]'
expect 1 "another event than PreToolUse is checked too" 'hooks.PostToolUse[0].hooks[0] has no "onFailure"'
fresh; jqi settings.json '.hooks.PreToolUse[0].hooks[0].type = "prompt"'
expect 1 "a hook that is not a command" 'is a "prompt" hook'
fresh; jqi settings.json '.disableAllHooks = false'
expect 1 "disableAllHooks, whatever its value" 'settings.json sets disableAllHooks'
fresh; printf '{ "hooks": ' > "$root/.claude/settings.json"
expect 1 "settings that do not parse" 'cannot read .claude/settings.json'
fresh; jqi settings.json '.hooks = []'
expect 1 "hooks that are not a map of events" '`hooks` is not a map of events'

echo "commands"
fresh; jqi settings.json '.hooks.PreToolUse[0].hooks[0].command = "/tmp/guard-migrations.sh"'
expect 1 "a hook outside .claude/hooks/" 'is not "$CLAUDE_PROJECT_DIR"/.claude/hooks/<name>.sh'
fresh; jqi settings.autopilot.json '.hooks.PreToolUse[0].hooks[0].command = "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/guard-tests.sh || true"'
expect 1 "a hook that can never fail (|| true)" 'with plain arguments'
fresh; jqi settings.autopilot.json '.hooks.PreToolUse[0].hooks[0].command = "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/guard-tests.sh; true"'
expect 1 "a second command after the hook" 'with plain arguments'
fresh; jqi settings.autopilot.json '.hooks.PreToolUse[0].hooks[0].command = "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/guard-tests.sh $(true)"'
expect 1 "a substitution in the arguments" 'with plain arguments'
fresh; agent t-glob "hooks:
  PreToolUse:
    - matcher: \"Write\"
      hooks:
        - type: command
          command: '$H/write-scope.sh .ai/*'
          onFailure: \"block\""
expect 1 "an unquoted * in the arguments (the shell would expand it)" 't-glob.md: hooks.PreToolUse[0].hooks[0]'
fresh; jqi settings.json '.hooks.PreToolUse[2].hooks[0].command = "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/read-scope.sh --agent verifier --name gate-r*.json .ai/runs/"'
expect 1 "the verifier's --name pattern unquoted" 'with plain arguments'
fresh; jqi settings.json '.hooks.PreToolUse[2].hooks[0].command = "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/read-scope.sh --agent verifier --name '"'"'it'"'"'s'"'"' .ai/runs/"'
expect 1 "an unbalanced single quote" 'with plain arguments'
fresh; jqi settings.json '.hooks.PreToolUse[0].hooks[0].command = "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/no-such-hook.sh"'
expect 1 "a hook that does not exist" 'no-such-hook.sh does not exist'
fresh; chmod -x "$root/.claude/hooks/guard-migrations.sh"
expect 1 "a hook that is not executable" 'guard-migrations.sh is not executable'
fresh; cp -p "$root/.claude/hooks/guard-tests.sh" "$root/.claude/hooks/new-guard.sh"
jqi settings.autopilot.json '.hooks.PreToolUse[0].hooks[0].command = "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/new-guard.sh"'
expect 1 "a hook without a row in FAILS_CLOSED" 'new-guard.sh has no row in FAILS_CLOSED'
fresh; sed -i.bak 's/^FAILS_CLOSED=(/NOT_THE_TABLE=(/' "$root/.claude/hooks/test-hooks.sh"
expect 1 "test-hooks.sh without the table" 'has no FAILS_CLOSED table'

echo "agents' frontmatter"
fresh; sed -i.bak '/onFailure/d' "$root/.claude/agents/review-general.md"
expect 1 "an agent's entry without onFailure" 'review-general.md: hooks.PreToolUse[0].hooks[0] has no "onFailure"'
fresh; agent t-indent "hooks:
  PreToolUse:
    - matcher: \"Bash\"
      onFailure: \"block\"
      hooks:
        - type: command
          command: '$H/readonly-bash.sh'"
expect 1 "onFailure one indent off (on the matcher)" 't-indent.md: hooks.PreToolUse[0].hooks[0] has no "onFailure"'
fresh; agent t-flow "hooks:
  PreToolUse:
    - {matcher: Bash, hooks: [{type: command, command: '$H/readonly-bash.sh', onFailure: block}]}"
expect 0 "a flow-style entry that is right passes"
fresh; agent t-flow "hooks:
  PreToolUse:
    - {matcher: Bash, hooks: [{type: command, command: '$H/readonly-bash.sh'}]}"
expect 1 "a flow-style entry without onFailure" 't-flow.md: hooks.PreToolUse[0].hooks[0] has no "onFailure"'
fresh; agent t-args "hooks:
  PreToolUse:
    - matcher: \"Bash\"
      hooks:
        - type: command
          command: '$H/write-scope.sh .ai/audit/ && true'
          onFailure: \"block\""
expect 1 "an agent's command with shell syntax" 't-args.md: hooks.PreToolUse[0].hooks[0]'
fresh; printf -- '---\nname: t-open\ntools: Bash\n' > "$root/.claude/agents/t-open.md"
expect 1 "a frontmatter that does not close" 't-open.md: no frontmatter between two --- lines'
fresh; printf 'An agent with no frontmatter at all.\n' > "$root/.claude/agents/t-none.md"
expect 1 "an agent without frontmatter" 't-none.md: no frontmatter between two --- lines'
fresh; printf -- '---\nname: [t-bad\n---\n' > "$root/.claude/agents/t-bad.md"
expect 1 "a frontmatter that is not YAML" 't-bad.md: the frontmatter is not YAML'
fresh; agent t-nolist "hooks:
  PreToolUse:
    - matcher: \"Bash\""
expect 1 "a group without a list of hooks" 't-nolist.md: hooks.PreToolUse[0] has no list of hooks'

fresh; jqi settings.autopilot.json '.hooks.PreToolUse[0].hooks[0].async = true'
expect 1 "a hook in the background (async)" 'sets async'
fresh; jqi settings.json '.hooks.PreToolUse[0].hooks[0].asyncRewake = true'
expect 1 "a hook in the background (asyncRewake)" 'sets asyncRewake'

echo "agents in subdirectories, skills and commands (review round 1)"
fresh; mkdir -p "$root/.claude/agents/sub"; sed '/onFailure/d' "$root/.claude/agents/review-general.md" > "$root/.claude/agents/sub/nested.md"
expect 1 "an agent in a subdirectory of .claude/agents/" 'agents/sub/nested.md: hooks.PreToolUse[0].hooks[0] has no "onFailure"'
fresh; printf -- '---\nname: s\ndescription: x\nhooks:\n  PreToolUse:\n    - matcher: Bash\n      hooks:\n        - type: command\n          command: %s\n---\n\nBody.\n' "'$H/readonly-bash.sh'" > "$root/.claude/skills/review/SKILL.md"
expect 1 "a skill's hook without onFailure" 'skills/review/SKILL.md: hooks.PreToolUse[0].hooks[0] has no "onFailure"'
fresh; mkdir -p "$root/.claude/commands/ops"; printf -- '---\ndescription: x\nhooks:\n  PreToolUse:\n    - matcher: Bash\n      hooks:\n        - type: command\n          command: %s\n---\n\nBody.\n' "'$H/readonly-bash.sh'" > "$root/.claude/commands/ops/run.md"
expect 1 "a command's hook without onFailure" 'commands/ops/run.md: hooks.PreToolUse[0].hooks[0] has no "onFailure"'
fresh; mkdir -p "$root/.claude/commands"; printf 'Just a prompt, no frontmatter.\n' > "$root/.claude/commands/plain.md"
expect 0 "a command without frontmatter has no hooks"
fresh; printf -- '---\ndescription: x\n' > "$root/.claude/skills/review/SKILL.md"
expect 1 "a skill whose frontmatter does not close" 'skills/review/SKILL.md: no frontmatter between two --- lines'

echo "a key repeated in one map (review round 2: which value a parser keeps is not documented)"
fresh; perl -pi -e 's/"onFailure": "block" \}/"onFailure": "continue", "onFailure": "block" }/' "$root/.claude/settings.json"
expect 1 "settings.json: onFailure twice in one entry" 'cannot read .claude/settings.json: repeats the key "onFailure"'
fresh; jqi settings.autopilot.json '.' && perl -0pi -e 's/\A\{\n/{\n  "disableAllHooks": false,\n  "disableAllHooks": false,\n/' "$root/.claude/settings.autopilot.json"
expect 1 "settings.autopilot.json: a top-level key twice" 'repeats the key "disableAllHooks"'
fresh; agent t-twice "hooks:
  PreToolUse:
    - matcher: \"Bash\"
      hooks:
        - type: command
          command: '$H/readonly-bash.sh'
          onFailure: \"continue\"
          onFailure: \"block\""
expect 1 "an agent's entry with onFailure twice (the last is block)" 't-twice.md: the frontmatter repeats the key(s) hooks.PreToolUse[0].hooks[0].onFailure'
fresh; agent t-hooks2 "hooks:
  PreToolUse: []
hooks:
  PreToolUse:
    - matcher: \"Bash\"
      hooks:
        - type: command
          command: '$H/readonly-bash.sh'
          onFailure: \"block\""
expect 1 "an agent with hooks: twice" 't-hooks2.md: the frontmatter repeats the key(s) hooks'

if [ "$failures" -gt 0 ]; then echo "$failures hook entry test(s) failed"; exit 1; fi
echo "All hook entry tests passed."
