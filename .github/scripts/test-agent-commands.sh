#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-agent-commands.rb (backlog #0-207 AC4): it passes on the repository's own definitions,
# and fails, with the message the case names, when a definition's command list holds a form Claude Code refuses
# whatever the rules say (a recursive cp, an absolute path, git -C, a pipe, a glob, ..), one no allow rule matches,
# one a deny rule matches, one a session hook refuses (a push of another branch than the lock's) or the agent's own
# frontmatter hook refuses (a commit by the read-only defect-seeder), when an agent with Bash has no list and no
# exemption, an exemption or an ALSO_FOR entry is stale, or a list line is not "- `<command>`"; and (backlog #0-235)
# when an allow rule of either settings file, or of a preloaded skill's allowed-tools, runs a program outside
# SAFE_PREFIXES (rm, find, python3, git clean, any git subcommand, a gh or git subcommand nobody judged, a bare Bash),
# holds a shell operator, or an EXEMPT_RULES entry is stale.
# Each case runs on a copy of .claude/ with one change.
# Needs ruby, jq and git.
# Run: .github/scripts/test-agent-commands.sh   (in Factory guards through run-agent-command-tests.sh, which skips it on
# a pull request that changes none of its inputs, backlog #0-241: a new input of these cases goes on its list)
# ============================================================
# The cases write Markdown list lines as literal text: a backtick in single quotes is meant.
# shellcheck disable=SC2016
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
CHECK=${CHECK:-$SRC/.github/scripts/check-agent-commands.rb}
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
# expect <0|1> <name> [<text the output must contain>] — starts the check on $root in the background and records what
# it must give; report reads every case in order once all have ended. Each case has its own root, which nothing changes
# after expect, so the cases run in parallel: one run of the check takes seconds (every hook for every listed form),
# and run one after another the cases took three minutes of Factory guards (#0-207, review round 1).
JOBS=${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}
case_roots=(); case_wants=(); case_names=(); case_texts=()
expect() {
    case_roots+=("$root"); case_wants+=("$1"); case_names+=("$2"); case_texts+=("${3:-}")
    # bash 3.2 (macOS) has no `wait -n`: poll for a free slot.
    while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do sleep 0.2; done
    ( ruby "$CHECK" "$root" >"$root.out" 2>&1; echo "$?" >"$root.rc" ) &
}
report() {
    local i want name text out rc
    wait
    for i in "${!case_roots[@]}"; do
        want=${case_wants[$i]} name=${case_names[$i]} text=${case_texts[$i]}
        out=$(cat "${case_roots[$i]}.out"); rc=$(cat "${case_roots[$i]}.rc" 2>/dev/null || echo missing)
        if [ "$rc" != "$want" ]; then
            echo "::error::$name: expected exit $want, got $rc"; printf '%s\n' "$out" | sed 's/^/    /'; failures=$((failures+1))
        elif [ -n "$text" ] && ! grep -qF -- "$text" <<<"$out"; then
            echo "::error::$name: the output does not say \"$text\""; printf '%s\n' "$out" | sed 's/^/    /'; failures=$((failures+1))
        else echo "  ok: $name"; fi
    done
}
# listed <agent> <line> — a fresh copy whose <agent>.md command list also holds <line>, as its first line.
listed() {
    fresh
    AGENT_FILE="$root/.claude/agents/$1.md" LINE="$2" ruby -e '
      f = ENV.fetch("AGENT_FILE"); s = File.read(f, encoding: "UTF-8")
      s.sub!("<!-- commands:start -->\n", "<!-- commands:start -->\n#{ENV.fetch("LINE")}\n") or abort "no list in #{f}"
      File.write(f, s)'
}

echo "check-agent-commands (backlog #0-207 AC4)"
fresh
expect 0 "the repository's own definitions pass"
listed picker '- `cp -r .ai/work/_template .ai/work/0-25`'
expect 1 "a recursive cp (the third case)"          "a recursive cp"
listed picker '- `mkdir -p /workspace/.ai/work/0-25`'
expect 1 "an absolute path"                         "an absolute or ~ path"
listed implementer '- `git -C incident-service status`'
expect 1 "git -C"                                   "git -C"
listed implementer '- `cd incident-service`'
expect 1 "cd"                                       "cd, which Claude Code"
listed picker '- `ls ~/.ai/work`'
expect 1 "a ~ path"                                 "an absolute or ~ path"
fresh
printf '\n<!-- commands:start -->\n- `git status --short`\n<!-- commands:end -->\n' >>"$root/.claude/agents/implementer-light.md"
expect 1 "an ALSO_FOR follower with its own list: stale" "ALSO_FOR names implementer-light for implementer, and implementer-light has a command list of its own"
listed picker '- `git show HEAD:BACKLOG.md | grep 0-25`'
expect 1 "a pipe"                                   "a separator, pipe, redirect or substitution outside quotes"
listed implementer '- `./mvnw test -pl shared > .ai/runs/out.log`'
expect 1 "a redirect"                               "a separator, pipe, redirect or substitution outside quotes"
listed implementer '- `ls incident-service/*.md`'
expect 1 "an unquoted glob"                         "an unquoted glob"
listed shipper '- `git add ../outside.txt`'
expect 1 "a path through .."                        "a path through .."
listed implementer '- `cat BACKLOG.md`'
expect 1 "no allow rule (cat)"                      "no allow rule of the two settings files matches it"
listed implementer '- `sed -i s/x/y/ BACKLOG.md`'
expect 1 "no allow rule (sed)"                      "no allow rule of the two settings files matches it"
listed shipper '- `git push -u origin main`'
expect 1 "a deny rule"                              "the deny rule Bash(git push -u origin main*) matches it"
listed shipper '- `git push -u origin fix/0-26-another-item`'
expect 1 "a session hook: another branch than the lock's" "refused by guard-git-push.sh"
listed implementer '- `./mvnw test -pl shared -DskipTests`'
expect 1 "a session hook: skipped verification"     "refused by guard-tests.sh"
listed defect-seeder '- `git commit -m "x"`'
expect 1 "the agent's frontmatter hook: the seeder commits" "refused by readonly-bash.sh"
listed implementer '- `cat BACKLOG.md`'
expect 1 "implementer-light is checked with implementer's list" "(implementer-light): cat BACKLOG.md"
listed picker 'git status'
expect 1 "a list line that is not - \`command\`"     "is not \"- \`<command>\`\""
fresh
ruby -e 'f = ARGV[0]; s = File.read(f, encoding: "UTF-8"); s.sub!(/<!-- commands:start -->.*?<!-- commands:end -->\n/m, "") or abort; File.write(f, s)' \
    "$root/.claude/agents/shipper.md"
expect 1 "an agent with Bash without a list"        "the definition of shipper runs Bash and has no command list"
fresh
printf -- '---\nname: new-agent\ndescription: x\ntools: Read, Bash\n---\n\nNew.\n' >"$root/.claude/agents/new-agent.md"
expect 1 "a new agent with Bash and no list"        "the definition of new-agent runs Bash and has no command list"
fresh
printf -- '---\nname: new-agent\ndescription: x\n---\n\nEvery tool.\n' >"$root/.claude/agents/new-agent.md"
expect 1 "a new agent with no tools: line holds Bash" "the definition of new-agent runs Bash"
fresh
printf -- '---\nname: new-agent\ndescription: x\ntools: Read, Write\n---\n\nNo Bash.\n' >"$root/.claude/agents/new-agent.md"
expect 0 "a new agent without Bash needs no list"
fresh
printf '\n<!-- commands:start -->\n- `git status --short`\n<!-- commands:end -->\n' >>"$root/.claude/agents/review-general.md"
expect 1 "an exempt agent with a list: stale"       "EXEMPT names review-general, which has a command list"
fresh
rm "$root/.claude/agents/factory-ops.md"
expect 1 "an exemption for no agent: stale"         "EXEMPT names factory-ops, which is no agent with Bash"
fresh
jq '.permissions.deny += ["Bash(git status --short)"]' "$SRC/.claude/settings.json" >"$root/.claude/settings.json"
expect 1 "a deny rule of settings.json alone"        "the deny rule Bash(git status --short) matches it"
fresh
AGENT_FILE="$root/.claude/agents/implementer-light.md" ruby -e '
  f = ENV.fetch("AGENT_FILE"); s = File.read(f, encoding: "UTF-8")
  s.sub!("foreground-bash.sh\"", "readonly-bash.sh\"") or abort "no hook in #{f}"
  File.write(f, s)'
expect 1 "implementer-light is checked with its own frontmatter hooks" "(implementer-light): ./mvnw -q test"
fresh
ruby -e 'f = ARGV[0]; s = File.read(f, encoding: "UTF-8"); s.sub!(/(<!-- commands:start -->\n).*?(<!-- commands:end -->)/m, "\\1\\2") or abort; File.write(f, s)' \
    "$root/.claude/agents/picker.md"
expect 1 "an empty list"                            "the command list names no command"

# Rule 4 (backlog #0-235): an allow rule that lets a program remove or move a path it does not name.
# allowing <settings file> <rule> — a fresh copy whose <settings file> also allows <rule>.
allowing() {
    fresh
    jq --arg r "$2" '.permissions.allow = ((.permissions.allow // []) + [$r])' "$SRC/.claude/$1" >"$root/.claude/$1"
}
allowing settings.autopilot.json 'Bash(rm *)'
expect 1 "an allow rule for rm"                     "the allow rule Bash(rm *): it runs rm, which is not in SAFE_PREFIXES"
allowing settings.autopilot.json 'Bash(find .ai/runs *)'
expect 1 "an allow rule for find"                   "it runs find .ai/runs, which is not in SAFE_PREFIXES"
allowing settings.autopilot.json 'Bash(python3 *)'
expect 1 "an allow rule for an interpreter"         "it runs python3, which is not in SAFE_PREFIXES"
allowing settings.autopilot.json 'Bash(git clean -fdx)'
expect 1 "git clean (removes ignored files)"        "it runs git clean -fdx, which is not in SAFE_PREFIXES"
allowing settings.autopilot.json 'Bash(git *)'
expect 1 "any git subcommand"                       "the allow rule Bash(git *): it runs git, which is not in SAFE_PREFIXES"
allowing settings.autopilot.json 'Bash(env rm -rf .ai/runs)'
expect 1 "a wrapper (env)"                          "it runs env rm -rf .ai/runs, which is not in SAFE_PREFIXES"
allowing settings.autopilot.json 'Bash(*)'
expect 1 "every command (Bash(*))"                  "it runs any program"
allowing settings.autopilot.json 'Bash'
expect 1 "every command (a bare Bash)"              "the allow rule Bash: it allows every Bash command"
allowing settings.autopilot.json 'Bash(rm:*)'
expect 1 "a prefix rule (rm:*)"                     "the allow rule Bash(rm:*): it runs rm"
allowing settings.json 'Bash(rm *)'
expect 1 "an allow rule of settings.json alone"     ".claude/settings.json: the allow rule Bash(rm *)"
allowing settings.autopilot.json 'Bash(git status *)'
expect 0 "an allow rule with a safe prefix passes"
# Review round 1 of #0-235: a prefix is as narrow as the rules it was judged on, and a rule's own text carries no
# shell operator.
allowing settings.autopilot.json 'Bash(gh pr checkout *)'
expect 1 "a gh subcommand nobody judged (checkout)" "it runs gh pr checkout, which is not in SAFE_PREFIXES"
allowing settings.autopilot.json 'Bash(git fetch *)'
expect 1 "a wider fetch than the one judged"        "the allow rule Bash(git fetch *): it runs git fetch, which is not in SAFE_PREFIXES"
allowing settings.autopilot.json 'Bash(git status && rm -rf .ai/runs)'
expect 1 "a shell operator in the rule"             'its text holds a shell operator ("&")'
allowing settings.autopilot.json 'Bash(git status $(rm -rf x))'
expect 1 "a substitution in the rule"               'its text holds a shell operator ("$(")'
allowing settings.autopilot.json "$(printf 'Bash(git status\nrm -rf .ai/runs)')"
expect 1 "a newline in the rule"                    'its text holds a shell operator ("\n")'
fresh
jq '.permissions.allow -= ["Bash(git mv *)"]' "$SRC/.claude/settings.autopilot.json" >"$root/.claude/settings.autopilot.json"
expect 1 "a git exemption no longer allowed: stale" "EXEMPT_RULES names Bash(git mv *), which no allow rule holds"
fresh
jq '.permissions.allow -= ["Bash(./mvnw *)"]' "$SRC/.claude/settings.autopilot.json" >"$root/.claude/settings.autopilot.json"
expect 1 "an exempt rule no longer allowed: stale"  "EXEMPT_RULES names Bash(./mvnw *), which no allow rule holds"
fresh
ruby -e 'f = ARGV[0]; s = File.read(f, encoding: "UTF-8"); s.sub!(/\A---\n/, "---\nallowed-tools: Read Bash(rm -rf *)\n") or abort; File.write(f, s)' \
    "$root/.claude/skills/ship-record/SKILL.md"
expect 1 "a preloaded skill's allowed-tools"        "ship-record/SKILL.md (allowed-tools, preloaded by shipper): the allow rule Bash(rm -rf *)"
fresh
ruby -e 'f = ARGV[0]; s = File.read(f, encoding: "UTF-8"); s.sub!(/^allowed-tools:.*$/, "allowed-tools: Bash(rm -rf *)") or abort; File.write(f, s)' \
    "$root/.claude/skills/ship/SKILL.md"
expect 0 "a skill no autopilot agent preloads is not read"
fresh
ruby -e 'f = ARGV[0]; s = File.read(f, encoding: "UTF-8"); s.sub!(/\A---\n/, "---\nallowed-tools:\n  - Read\n  - Bash(find .ai/runs -delete)\n") or abort; File.write(f, s)' \
    "$root/.claude/skills/ship-record/SKILL.md"
expect 1 "a preloaded skill's allowed-tools as a YAML list" "the allow rule Bash(find .ai/runs -delete)"
fresh
AGENT_FILE="$root/.claude/agents/shipper.md" ruby -e '
  f = ENV.fetch("AGENT_FILE"); s = File.read(f, encoding: "UTF-8")
  s.sub!("  - ship-record\n", "  - ship-record\n  - no-such-skill\n") or abort "no skills in #{f}"
  File.write(f, s)'
expect 1 "a preloaded skill that does not exist"    "shipper preloads the skill no-such-skill"

report
if [ "$failures" -gt 0 ]; then echo "$failures agent command check test(s) failed."; exit 1; fi
echo "All agent command check tests passed."
