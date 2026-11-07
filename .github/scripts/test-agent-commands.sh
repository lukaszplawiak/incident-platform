#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-agent-commands.rb (backlog #0-207 AC4): it passes on the repository's own definitions,
# and fails, with the message the case names, when a definition's command list holds a form Claude Code refuses
# whatever the rules say (a recursive cp, an absolute path, git -C, a pipe, a glob, ..), one no allow rule matches,
# one a deny rule matches, one a session hook refuses (a push of another branch than the lock's) or the agent's own
# frontmatter hook refuses (a commit by the read-only defect-seeder), when an agent with Bash has no list and no
# exemption, an exemption or an ALSO_FOR entry is stale, or a list line is not "- `<command>`". Each case runs on a
# copy of .claude/ with one change.
# Needs ruby, jq and git.
# Run: .github/scripts/test-agent-commands.sh   (also run by .github/workflows/factory-guards.yml)
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

report
if [ "$failures" -gt 0 ]; then echo "$failures agent command check test(s) failed."; exit 1; fi
echo "All agent command check tests passed."
