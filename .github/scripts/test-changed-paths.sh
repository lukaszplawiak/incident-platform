#!/usr/bin/env bash
# ============================================================
# Tests for scripts/factory/changed-paths.sh — the path gate of the autopilot, the areas a diff reaches
# (compared with Touches and the architect's plan) and the rule files it calls for (the implementer's
# self-check). Each case starts a branch from main in a temporary repository, commits files, and checks
# the JSON. Run: .github/scripts/test-changed-paths.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0

mkdir -p "$WORK/repo/scripts/factory"
cp "$SRC/scripts/factory/_common.sh" "$SRC/scripts/factory/changed-paths.sh" "$WORK/repo/scripts/factory/"
cd "$WORK/repo" || exit 1
git init -q -b main
printf '.ai/runs/\n' > .gitignore
mkdir -p notification-service/src/main/java/n notification-service/src/main/resources/db/migration
echo 'class A {}' > notification-service/src/main/java/n/A.java
echo 'create table a();' > notification-service/src/main/resources/db/migration/V1__a.sql
echo '# readme' > README.md
git add -A && git -c user.email=t@t -c user.name=t commit -q -m base

# case <name> <jq expression> -- <path=content> ...   (a branch from main with those files committed)
case_() {
    local name=$1 expr=$2; shift 3
    git checkout -q -B feat/0-1-t main
    for spec in "$@"; do
        local path=${spec%%=*} content=${spec#*=}
        mkdir -p "$(dirname "$path")"; printf '%s\n' "$content" > "$path"
    done
    git add -A && git -c user.email=t@t -c user.name=t commit -q -m t
    local out; out=$(scripts/factory/changed-paths.sh main)
    if printf '%s' "$out" | jq -e "$expr" >/dev/null 2>&1; then echo "  ok: $name"
    else echo "::error::$name: '$expr' is false for: $(printf '%s' "$out" | jq -c '{areas, ruleFiles, needsHuman, protectedTouched}')"; failures=$((failures+1)); fi
}
core='["architecture","general","security"]'

echo "changed-paths: areas and rule files"
case_ "plain Java change"          ".areas == [\"notification-service\"] and (.ruleFiles | sort) == $core" -- \
      'notification-service/src/main/java/n/A.java=class A { int x; }'
case_ "Kafka listener → performance" '.ruleFiles | index("performance")' -- \
      'notification-service/src/main/java/n/B.java=class B { @KafkaListener(topics = "t") void on() {} }'
case_ "repository → performance"   '.ruleFiles | index("performance")' -- \
      'notification-service/src/main/java/n/R.java=interface R extends JpaRepository<A, Long> {}'
case_ "new migration → migration"  '(.ruleFiles | index("migration")) and .migrationTouched and (.needsHuman | not)' -- \
      'notification-service/src/main/resources/db/migration/V2__b.sql=create table b();'
case_ "entity → migration"         '.ruleFiles | index("migration")' -- \
      'notification-service/src/main/java/n/E.java=@Entity class E { @Version Long v; }'
case_ "k8s manifest → k8s"         '(.ruleFiles | index("k8s")) and .areas == ["k8s"]' -- \
      'k8s/base/a.yaml=kind: Deployment'
case_ "README → docs, root"        '(.ruleFiles | index("docs")) and .areas == ["root"]' -- \
      'README.md=# readme, changed'
big=$(printf 'class K { @KafkaListener(topics = "t") void on() {}\n'; seq 1 40000 | sed 's/.*/  \/\/ filler line &/'; printf '}')
case_ "Kafka listener in a very large diff → performance" '.ruleFiles | index("performance")' -- \
      "notification-service/src/main/java/n/K.java=$big"
case_ "endpoint → docs"            '.ruleFiles | index("docs")' -- \
      'notification-service/src/main/java/n/C.java=@RestController class C { @GetMapping("/x") String x() { return ""; } }'
case_ "process files only"         ".areas == [] and (.ruleFiles | sort) == $core" -- \
      '.ai/work/0-1/progress.md=- [picker] picked' 'BACKLOG.md=# Backlog' '.ai/decisions/0099-x.md=# ADR'
case_ ".ai/context is root"        '.areas == ["root"]' -- \
      '.ai/context/security.md=# security'
case_ "docs/ and docker/"          '.areas == ["docker","docs"]' -- \
      'docs/x.md=x' 'docker/compose.yml=services: {}'

echo "changed-paths: gate"
case_ "queue is human-owned"       '.needsHuman and (.protectedTouched | index(".ai/plan/queue.md"))' -- \
      '.ai/plan/queue.md=x'
case_ "workflow file is ci and human-owned" '.needsHuman and .areas == ["ci"]' -- \
      '.github/workflows/x.yml=on: push'
git checkout -q -B feat/0-1-t main
echo 'create table a(x int);' > notification-service/src/main/resources/db/migration/V1__a.sql
git -c user.email=t@t -c user.name=t commit -qam edit
out=$(scripts/factory/changed-paths.sh main)
if printf '%s' "$out" | jq -e '.needsHuman and (.editedAppliedMigrations | length == 1)' >/dev/null; then echo "  ok: edited applied migration"
else echo "::error::edited applied migration not flagged"; failures=$((failures+1)); fi

if [ "$failures" -gt 0 ]; then echo "$failures changed-paths test(s) failed"; exit 1; fi
echo "All changed-paths tests passed."
