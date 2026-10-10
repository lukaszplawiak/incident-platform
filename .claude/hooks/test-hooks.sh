#!/usr/bin/env bash
# ============================================================
# Tests for the hooks in .claude/hooks/. Each case feeds a tool call as JSON on stdin and checks the exit
# code: 0 = allowed, 2 = blocked. Runs in a temporary git repository for the path-based hooks.
#
# Run: .claude/hooks/test-hooks.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
set -uo pipefail

HOOKS=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0

bash_call() { printf '{"tool_name":"Bash","tool_input":{"command":%s}}' "$(printf '%s' "$1" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')"; }
file_call() { printf '{"tool_name":"%s","tool_input":{"file_path":"%s"}}' "$1" "$2"; }

# expect <0|2> <name> <hook and args...> -- <json>
expect() {
    local want=$1 name=$2; shift 2
    local args=()
    while [ "$1" != -- ]; do args+=("$1"); shift; done
    shift
    local json=$1 rc
    (cd "$WORK/repo" && printf '%s' "$json" | "${args[@]}" >/dev/null 2>&1); rc=$?
    if [ "$rc" -eq "$want" ]; then echo "  ok: $name"; else echo "::error::$name: expected exit $want, got $rc"; failures=$((failures+1)); fi
}

# A repository whose main has one migration; the working branch adds a second.
mkdir -p "$WORK/repo/svc/src/main/resources/db/migration"
cd "$WORK/repo"
git init -q -b main
git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init
echo "create table a();" > svc/src/main/resources/db/migration/V1__a.sql
git add . && git -c user.email=t@t -c user.name=t commit -q -m v1
git checkout -q -b feat/x
echo "create table b();" > svc/src/main/resources/db/migration/V2__b.sql
cd - >/dev/null

M="$HOOKS/guard-migrations.sh"
echo "guard-migrations"
expect 2 "edit a migration that is on main"        "$M" -- "$(file_call Edit svc/src/main/resources/db/migration/V1__a.sql)"
expect 2 "edit it by absolute path"                "$M" -- "$(file_call Edit "$WORK/repo/svc/src/main/resources/db/migration/V1__a.sql")"
ln -s "$WORK/repo" "$WORK/mlink"
expect 2 "edit it through a symlinked path"        "$M" -- "$(file_call Edit "$WORK/mlink/svc/src/main/resources/db/migration/V1__a.sql")"
expect 0 "edit a migration only on this branch"    "$M" -- "$(file_call Edit svc/src/main/resources/db/migration/V2__b.sql)"
expect 0 "write a new migration"                   "$M" -- "$(file_call Write svc/src/main/resources/db/migration/V3__c.sql)"
expect 0 "edit a Java file"                        "$M" -- "$(file_call Edit svc/src/main/java/A.java)"

T="$HOOKS/guard-tests.sh"
echo "guard-tests"
expect 0 "plain verify"                  "$T" -- "$(bash_call './mvnw -B verify -pl incident-service -am')"
expect 0 "one test class"                "$T" -- "$(bash_call './mvnw test -pl incident-service -Dtest=IncidentFsmTest')"
expect 2 "skipTests"                     "$T" -- "$(bash_call './mvnw verify -DskipTests')"
expect 2 "maven.test.skip"               "$T" -- "$(bash_call './mvnw install -Dmaven.test.skip=true')"
expect 2 "jacoco skip"                   "$T" -- "$(bash_call './mvnw verify -Djacoco.skip=true')"
expect 2 "failure ignore"                "$T" -- "$(bash_call './mvnw test -Dmaven.test.failure.ignore=true')"
expect 2 "fail never"                    "$T" -- "$(bash_call './mvnw verify -fn')"
expect 2 "commit --no-verify"            "$T" -- "$(bash_call 'git commit --no-verify -m x')"
expect 2 "commit -n"                     "$T" -- "$(bash_call 'git commit -n -m x')"
expect 0 "commit with message"           "$T" -- "$(bash_call 'git commit -m "fix(incident-service): x"')"

P="$HOOKS/guard-protected-bash.sh"
echo "guard-protected-bash"
expect 0 "read a rule"                   "$P" -- "$(bash_call 'cat .ai/rules/review/security.md')"
expect 0 "diff a workflow"               "$P" -- "$(bash_call 'git diff main -- .github/workflows/ci.yml')"
expect 2 "sed -i a rule"                 "$P" -- "$(bash_call 'sed -i s/a/b/ .ai/rules/review/security.md')"
expect 2 "redirect into settings"        "$P" -- "$(bash_call 'echo {} > .claude/settings.json')"
expect 2 "copy over an agent"            "$P" -- "$(bash_call 'cp /tmp/x .claude/agents/review-security.md')"
expect 2 "git checkout a workflow"       "$P" -- "$(bash_call 'git checkout HEAD~1 -- .github/workflows/ci.yml')"
expect 2 "python writes AGENTS.md"       "$P" -- "$(bash_call 'python3 -c "open(\"AGENTS.md\",\"w\")"')"
expect 2 "redirect into audit decisions" "$P" -- "$(bash_call 'echo accept >> .ai/audit/decisions.md')"
expect 0 "read audit decisions"          "$P" -- "$(bash_call 'cat .ai/audit/decisions.md')"
expect 0 "unrelated write"               "$P" -- "$(bash_call 'echo x > /tmp/y')"

R="$HOOKS/readonly-bash.sh"
echo "readonly-bash"
expect 0 "git diff range"                "$R" -- "$(bash_call 'git diff main...HEAD')"
expect 0 "git log"                       "$R" -- "$(bash_call 'git log --oneline -5')"
expect 0 "git branch list"               "$R" -- "$(bash_call 'git branch')"
expect 2 "git branch delete"             "$R" -- "$(bash_call 'git branch -D feat/x')"
expect 2 "git diff --output"             "$R" -- "$(bash_call 'git diff --output=/tmp/x')"
expect 2 "pipe"                          "$R" -- "$(bash_call 'git diff | head')"
expect 2 "chaining"                      "$R" -- "$(bash_call 'git log && rm -rf x')"
expect 2 "maven"                         "$R" -- "$(bash_call './mvnw test')"
expect 2 "kubectl without --k8s"         "$R" -- "$(bash_call 'kubectl kustomize k8s/overlays/dev')"
expect 0 "kubectl with --k8s"            "$R" --k8s -- "$(bash_call 'kubectl kustomize k8s/overlays/dev')"
expect 2 "gh without --gh-read"          "$R" -- "$(bash_call 'gh pr list --label autopilot')"
expect 0 "gh pr list with --gh-read"     "$R" --gh-read -- "$(bash_call 'gh pr list --label autopilot --state merged')"
expect 2 "gh api is not allowed"         "$R" --gh-read -- "$(bash_call 'gh api repos/o/r/pulls')"
expect 2 "gh pr merge"                   "$R" --gh-read -- "$(bash_call 'gh pr merge 1')"

W="$HOOKS/write-scope.sh"
echo "write-scope"
expect 0 "audit report"                  "$W" .ai/audit/ -- "$(file_call Write .ai/audit/2026-11-03.md)"
expect 2 "rules from an auditor"         "$W" .ai/audit/ -- "$(file_call Edit .ai/rules/review/security.md)"
expect 2 "dot-dot escape"                "$W" .ai/audit/ -- "$(file_call Write .ai/audit/../rules/x.md)"
expect 0 "architect ADR"                 "$W" .ai/decisions/ .ai/work/ -- "$(file_call Write .ai/decisions/0023-x.md)"
expect 0 "architect progress"            "$W" .ai/decisions/ .ai/work/ -- "$(file_call Edit "$WORK/repo/.ai/work/0-58/progress.md")"
# The repository reached through a symlink (macOS: /var -> /private/var; a project under a linked folder).
ln -s "$WORK/repo" "$WORK/link"
expect 0 "architect progress via a symlink"  "$W" .ai/decisions/ .ai/work/ -- "$(file_call Edit "$WORK/link/.ai/work/0-58/progress.md")"
expect 2 "architect code via a symlink"      "$W" .ai/decisions/ .ai/work/ -- "$(file_call Edit "$WORK/link/svc/src/main/java/A.java")"
expect 2 "outside the repository"            "$W" .ai/decisions/ .ai/work/ -- "$(file_call Write "/tmp/.ai/work/x.md")"
expect 2 "architect code"                "$W" .ai/decisions/ .ai/work/ -- "$(file_call Edit svc/src/main/java/A.java)"

O="$HOOKS/factory-ops-bash.sh"
mkdir -p "$WORK/repo/scripts/factory" && printf '#!/bin/sh\n' > "$WORK/repo/scripts/factory/preflight.sh"
echo "factory-ops-bash"
expect 2 "an untracked factory script"   "$O" -- "$(bash_call 'scripts/factory/preflight.sh --json')"
expect 2 "a missing factory script"      "$O" -- "$(bash_call 'scripts/factory/nope.sh')"
expect 2 "anything else"                 "$O" -- "$(bash_call 'git push origin main')"
expect 2 "chained"                       "$O" -- "$(bash_call 'scripts/factory/preflight.sh; rm -rf /')"

GP="$HOOKS/guard-git-push.sh"
echo "guard-git-push"
expect 0 "push a feature branch"          "$GP" -- "$(bash_call 'git push -u origin fix/0-25-notification-version')"
expect 0 "branch name containing main"    "$GP" -- "$(bash_call 'git push -u origin fix/0-25-maintain-x')"
expect 2 "bare git push"                  "$GP" -- "$(bash_call 'git push')"
expect 2 "push main"                      "$GP" -- "$(bash_call 'git push -u origin main')"
expect 2 "refspec to main"                "$GP" -- "$(bash_call 'git push -u origin fix/x:main')"
expect 2 "refspec to refs/heads/main"     "$GP" -- "$(bash_call 'git push -u origin HEAD:refs/heads/main')"
expect 2 "force after branch"             "$GP" -- "$(bash_call 'git push -u origin fix/x --force')"
expect 2 "plus refspec"                   "$GP" -- "$(bash_call 'git push -u origin +fix/x')"
expect 2 "other remote"                   "$GP" -- "$(bash_call 'git push -u evil fix/x')"
expect 0 "not a push"                     "$GP" -- "$(bash_call 'git status')"
expect 0 "fetch origin main"              "$GP" -- "$(bash_call 'git fetch origin main')"
expect 2 "fetch moving origin/main"       "$GP" -- "$(bash_call 'git fetch . HEAD:refs/remotes/origin/main')"
expect 2 "fetch from another remote"      "$GP" -- "$(bash_call 'git fetch evil main')"
expect 2 "write the lock"                 "$P" -- "$(bash_call 'echo x > .ai/runs/LOCK')"
expect 2 "rewrite the queue"              "$P" -- "$(bash_call 'sed -i s/A/B/ .ai/plan/queue.md')"
expect 2 "delete STOP"                    "$P" -- "$(bash_call 'rm .ai/STOP')"

FS="$HOOKS/guard-factory-scripts.sh"
echo "guard-factory-scripts"
(cd "$WORK/repo" && mkdir -p scripts/factory && printf '#!/bin/sh\necho ok\n' > scripts/factory/preflight.sh && git add -A && git -c user.email=t@t -c user.name=t commit -q -m factory && git branch -f main HEAD)
expect 0 "intact factory script"          "$FS" -- "$(bash_call 'scripts/factory/preflight.sh')"
expect 0 "unrelated command"              "$FS" -- "$(bash_call 'git status')"
(cd "$WORK/repo" && echo "echo pass" >> scripts/factory/preflight.sh)
expect 2 "modified factory script"        "$FS" -- "$(bash_call 'scripts/factory/preflight.sh')"
expect 2 "factory-ops on modified script" "$HOOKS/factory-ops-bash.sh" -- "$(bash_call 'scripts/factory/preflight.sh')"
(cd "$WORK/repo" && git checkout -q -- scripts/factory/preflight.sh && printf '#!/bin/sh\n' > scripts/factory/evil.sh)
expect 2 "untracked factory script"       "$FS" -- "$(bash_call 'scripts/factory/evil.sh')"
(cd "$WORK/repo" && rm scripts/factory/evil.sh)
# The base is the sha in .ai/runs/LOCK, whatever the refs say.
# LOCK pins a commit before the script existed; refs still point at the commit that has it.
(cd "$WORK/repo" && mkdir -p .ai/runs && echo "R1 $(git rev-parse HEAD~1)" > .ai/runs/LOCK)
expect 2 "base from LOCK older than the script" "$FS" -- "$(bash_call 'scripts/factory/preflight.sh')"
# LOCK pins the current commit; moving main elsewhere does not matter.
(cd "$WORK/repo" && echo "R1 $(git rev-parse HEAD)" > .ai/runs/LOCK && git branch -f main HEAD~1)
expect 0 "base from LOCK, main moved back"  "$FS" -- "$(bash_call 'scripts/factory/preflight.sh')"
(cd "$WORK/repo" && git branch -f main feat/x && rm .ai/runs/LOCK)
expect 0 "factory-ops with quoted args"   "$HOOKS/factory-ops-bash.sh" -- "$(bash_call "scripts/factory/preflight.sh '0-25' 'origin/main'")"
expect 2 "factory-ops with a comment"     "$HOOKS/factory-ops-bash.sh" -- "$(bash_call 'scripts/factory/preflight.sh #0-25')"

echo "more guards"
expect 2 "mvnw exec plugin"               "$T" -- "$(bash_call './mvnw -q exec:exec -Dexec.executable=sh')"
expect 2 "mvnw other settings.xml"        "$T" -- "$(bash_call './mvnw -s /tmp/settings.xml verify')"
expect 2 "read a secret"                  "$P" -- "$(bash_call 'cat docker/.env')"
expect 2 "git diff --no-index"            "$P" -- "$(bash_call 'git diff --no-index /dev/null docker/secrets/x')"
expect 2 "write the maven wrapper"        "$P" -- "$(bash_call 'cp /tmp/x ./mvnw')"
expect 0 "run the maven wrapper"          "$P" -- "$(bash_call './mvnw -B verify -pl shared')"
expect 2 "write a factory script"         "$P" -- "$(bash_call 'sed -i s/x/y/ scripts/factory/run-tests.sh')"
expect 2 "reviewer --no-index"            "$R" -- "$(bash_call 'git diff --no-index a b')"
expect 0 "architect stages an ADR"        "$R" --architect -- "$(bash_call 'git add .ai/decisions/0023-x.md .ai/work/0-25/progress.md')"
expect 2 "architect stages code"          "$R" --architect -- "$(bash_call 'git add svc/src/main/java/A.java')"
expect 0 "architect commits"              "$R" --architect -- "$(bash_call 'git commit -m "docs(factory): plan backlog 0-25"')"
expect 2 "architect amends"               "$R" --architect -- "$(bash_call 'git commit --amend -m x')"
expect 2 "reviewer cannot commit"         "$R" -- "$(bash_call 'git commit -m x')"
expect 0 "auditor gh pr diff"             "$R" --gh-read -- "$(bash_call 'gh pr diff 12')"

echo "git grep (the agents' only search: no Grep/Glob tool, plain grep -r would read gitignored secrets)"
expect 0 "reviewer git grep"              "$R" -- "$(bash_call 'git grep -n TenantContext -- *.java')"
expect 0 "reviewer git grep in a revision" "$R" -- "$(bash_call 'git grep -n -e foo HEAD~1 -- shared')"
expect 0 "reviewer git grep fixed string" "$R" -- "$(bash_call 'git grep -F -n x.y')"
expect 2 "reviewer git grep -O"           "$R" -- "$(bash_call 'git grep -Osh foo')"
expect 2 "reviewer git grep bundled -nO"  "$R" -- "$(bash_call 'git grep -nOvim foo')"
expect 2 "reviewer git grep pager"        "$R" -- "$(bash_call 'git grep --open-files-in-pager=sh foo')"
expect 2 "reviewer git grep --no-index"   "$R" -- "$(bash_call 'git grep --no-index foo')"
expect 2 "reviewer git grep ignored files" "$R" -- "$(bash_call 'git grep --untracked --no-exclude-standard PASSWORD')"
expect 2 "reviewer git grep -f"           "$R" -- "$(bash_call 'git grep -f docker/x foo')"
expect 2 "reviewer git grep bundled -nf"  "$R" -- "$(bash_call 'git grep -nf patterns.txt')"
expect 2 "reviewer git grep --file="      "$R" -- "$(bash_call 'git grep --file=patterns.txt')"
expect 2 "reviewer git grep piped"        "$R" -- "$(bash_call 'git grep foo | head')"
expect 0 "implementer git grep"           "$P" -- "$(bash_call 'git grep -n TenantContext -- *.java')"
expect 0 "implementer git grep a rule"    "$P" -- "$(bash_call 'git grep -n SEC-03 -- .ai/rules')"
expect 2 "implementer git grep -O"        "$P" -- "$(bash_call 'git grep -O foo')"
expect 2 "implementer git grep untracked" "$P" -- "$(bash_call 'git grep --untracked --no-exclude-standard secret')"
expect 0 "plain grep is not git grep"     "$P" -- "$(bash_call 'cat README.md')"

echo "git grep: allow-list, bash word splitting (review of the factory hardening, sec-4c1e)"
expect 2 "abbreviated --untracked"        "$R" -- "$(bash_call 'git grep --untr --no-exclude-st -h -i secret')"
expect 2 "abbreviated --no-index"         "$R" -- "$(bash_call 'git grep --no-ind foo')"
expect 2 "abbreviated pager"              "$R" -- "$(bash_call 'git grep --open=sh -l x -- f')"
expect 2 "abbreviated --count too"        "$R" -- "$(bash_call 'git grep --cou foo')"
expect 2 "quoted -O"                      "$R" -- "$(bash_call "git grep '-Osh' foo")"
expect 2 "double-quoted --untracked"      "$R" -- "$(bash_call 'git grep "--untracked" foo')"
expect 2 "option split by quotes"         "$R" -- "$(bash_call "git grep -''-untracked foo")"
expect 2 "option behind a backslash"      "$R" -- "$(bash_call 'git grep \--untracked foo')"
expect 2 "variable"                       "$R" -- "$(bash_call 'git grep $OPT foo')"
expect 2 "variable in double quotes"      "$R" -- "$(bash_call 'git grep "$OPT" foo')"
expect 2 "ANSI-C quoting"                 "$R" -- "$(bash_call "git grep \$'\\x2d-untracked' foo")"
expect 2 "brace expansion"                "$R" -- "$(bash_call 'git grep {--untracked,x} foo')"
expect 2 "unquoted glob before --"        "$R" -- "$(bash_call 'git grep -n foo *.java')"
expect 2 "unterminated quote"             "$R" -- "$(bash_call "git grep 'foo")"
expect 0 "glob after --"                  "$R" -- "$(bash_call 'git grep -n foo -- *.java')"
expect 0 "quoted glob pattern"            "$R" -- "$(bash_call "git grep -n -E 'Tenant.*Id' -- shared")"
expect 0 "pattern starting with a dash"   "$R" -- "$(bash_call 'git grep -n -e -foo')"
expect 0 "context options"                "$R" -- "$(bash_call 'git grep -n -A3 -B 2 --context=4 foo')"
expect 0 "long safe options"              "$R" -- "$(bash_call 'git grep --count --ignore-case --fixed-strings foo')"
expect 0 "bundled safe short options"     "$R" -- "$(bash_call 'git grep -niw foo')"
expect 0 "git log --grep is not git grep" "$R" -- "$(bash_call 'git log --oneline --grep=backlog')"
expect 2 "implementer abbreviated option" "$P" -- "$(bash_call 'git grep --untr --no-exclude-st secret')"
expect 2 "implementer quoted option"      "$P" -- "$(bash_call 'git grep "--no-index" foo')"
expect 0 "implementer plain search"       "$P" -- "$(bash_call "git grep -n 'TenantContext.set' -- shared")"

echo "git grep: only a git grep command is checked, and it must be one (review round 2)"
expect 0 "commit message mentioning git grep"      "$P" -- "$(bash_call 'git commit -m "docs: use git grep here"')"
expect 0 "commit message ending in git grep"       "$P" -- "$(bash_call 'git commit -m "docs: search with git grep"')"
expect 0 "git log --grep"                          "$P" -- "$(bash_call 'git log --oneline --grep=backlog')"
expect 2 "git grep after &&"                       "$P" -- "$(bash_call 'ls && git grep --untr --no-exclude-st secret')"
expect 2 "git grep after ;"                        "$P" -- "$(bash_call 'ls; git grep --no-ind secret')"
expect 2 "grep behind a backslash"                 "$P" -- "$(bash_call 'git gr\ep --untr secret')"
expect 2 "grep in quotes"                          "$P" -- "$(bash_call 'git "grep" --untr secret')"
expect 2 "git options before grep"                 "$P" -- "$(bash_call 'git -C . grep --untr secret')"
expect 2 "reviewer: git options before grep"       "$R" -- "$(bash_call 'git -c core.quotepath=off grep foo')"

echo "line continuation (review round 2, sec-b7d2)"
expect 2 "grep split by a continuation"            "$P" -- "$(bash_call $'git gr\\\nep --untr secret')"
expect 2 "git split by a continuation"             "$P" -- "$(bash_call $'g\\\nit grep --untr secret')"
expect 2 "a secret split by a continuation"        "$P" -- "$(bash_call $'cat docker/.e\\\nnv')"
expect 2 "continuation inside double quotes"       "$P" -- "$(bash_call $'git grep "--untr\\\nacked" secret')"
expect 0 "multi-line commit message in quotes"     "$P" -- "$(bash_call $'git commit -m "fix: x\n\nbody line"')"
# The same, one layer down: git_grep_unsafe alone, without guard-protected-bash's own continuation check.
lib_refuses() {
    local name=$1 out
    out=$(bash -c '. "$1"/_lib.sh; git_grep_unsafe "$2"' _ "$HOOKS" "$2")
    if [ -n "$out" ]; then echo "  ok: $name"; else echo "::error::$name: git_grep_unsafe let it through"; failures=$((failures+1)); fi
}
lib_refuses "git_grep_unsafe: grep split by a continuation" $'git gr\\\nep --untr secret'
lib_refuses "git_grep_unsafe: git split by a continuation"  $'g\\\nit grep --untr secret'

echo "write guards: one helper (review of the factory hardening, arc-5e1d)"
expect 2 "migration via nonexistent dir and .." "$M" -- "$(file_call Edit nosuchdir/../svc/src/main/resources/db/migration/V1__a.sql)"
expect 2 "write-scope: .. through a nonexistent dir" "$W" .ai/audit/ -- "$(file_call Write .ai/audit/nosuch/../../rules/x.md)"
expect 2 "write-scope: notebook outside its scope" "$W" .ai/audit/ -- '{"tool_name":"NotebookEdit","tool_input":{"notebook_path":"svc/n.ipynb"}}'
(cd "$WORK/repo" && ln -s "$HOME/.mavenrc" link-to-mavenrc)

WR="$HOOKS/guard-write-in-repo.sh"
expect 2 ".. through a nonexistent directory" "$WR" -- "$(file_call Write nosuchdir/../../outside.txt)"
expect 2 "deep .. to the home directory"  "$WR" -- "$(file_call Write nosuchdir/../../../../../../../../.claude/settings.json)"
expect 2 "a symlink pointing outside"     "$WR" -- "$(file_call Write link-to-mavenrc)"
echo "guard-write-in-repo"
expect 0 "write a source file"            "$WR" -- "$(file_call Write svc/src/main/java/A.java)"
expect 0 "edit by absolute path"          "$WR" -- "$(file_call Edit "$WORK/repo/svc/src/main/java/A.java")"
expect 0 "edit through a symlinked path"  "$WR" -- "$(file_call Edit "$WORK/link/svc/src/main/java/A.java")"
expect 0 "a new file in a new directory"  "$WR" -- "$(file_call Write .ai/work/0-25/handoff.md)"
expect 2 "user settings of Claude Code"   "$WR" -- "$(file_call Write "$HOME/.claude/settings.json")"
expect 2 "maven settings"                 "$WR" -- "$(file_call Write "$HOME/.m2/settings.xml")"
expect 2 "mavenrc"                        "$WR" -- "$(file_call Edit "$HOME/.mavenrc")"
expect 2 "tmp"                            "$WR" -- "$(file_call Write /tmp/x)"
expect 2 "dot-dot out of the repository"  "$WR" -- "$(file_call Write ../outside.txt)"
expect 2 "notebook outside"               "$WR" -- '{"tool_name":"NotebookEdit","tool_input":{"notebook_path":"/tmp/n.ipynb"}}'
expect 2 "no path"                        "$WR" -- '{"tool_name":"Write","tool_input":{}}'

if [ "$failures" -gt 0 ]; then echo "$failures hook test(s) failed"; exit 1; fi
echo "All hook tests passed."
