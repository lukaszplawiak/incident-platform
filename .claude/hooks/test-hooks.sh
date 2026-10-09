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

if [ "$failures" -gt 0 ]; then echo "$failures hook test(s) failed"; exit 1; fi
echo "All hook tests passed."
