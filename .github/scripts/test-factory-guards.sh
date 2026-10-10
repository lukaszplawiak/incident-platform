#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-factory-guards.sh (backlog #0-113): one temporary repository per case, a
# base commit and a head commit, the checker run the way CI runs it.
# Run by .github/workflows/factory-guards.yml; locally: .github/scripts/test-factory-guards.sh
# ============================================================
set -euo pipefail

CHECKER=$(cd "$(dirname "$0")" && pwd)/check-factory-guards.sh
SRC=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0
G="git -c user.email=t@t -c user.name=t"

# case_ <expect: pass|fail> <name> <setup-on-base> <change-on-head>
case_() {
    local expect=$1 name=$2 setup=$3 change=$4
    local dir="$WORK/case$((n += 1))"
    mkdir -p "$dir" && cd "$dir"
    git init -q -b main
    mkdir -p svc/src/test/java svc/src/main/resources/db/migration
    printf 'class ATest { void t() { assertThat(1).isOne(); assertThat(2).isTwo(); } }\n' > svc/src/test/java/ATest.java
    printf 'create table a();\n' > svc/src/main/resources/db/migration/V1__a.sql
    printf '<project><dependencies></dependencies></project>\n' > pom.xml
    printf '### 0-1. Item\n**Type:** bug · **Priority:** Low · **Status:** Open\n' > BACKLOG.md
    mkdir -p .ai/rules && cp "$SRC/.ai/rules/protected-paths.md" .ai/rules/
    eval "$setup"
    git add -A && $G commit -qm base
    local b; b=$(git rev-parse HEAD)
    eval "$change"
    git add -A && $G commit -qm head --allow-empty
    local h; h=$(git rev-parse HEAD)
    local out rc
    set +e; out=$(bash "$CHECKER" "$b" "$h" 2>&1); rc=$?; set -e
    cd - >/dev/null
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ]; } || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -q "backlog #0-113" <<<"$out"; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

echo "Factory guards check"
case_ pass "a production change with a new test" ":" \
    "mkdir -p svc/src/main/java && echo 'class A {}' > svc/src/main/java/A.java && echo 'class BTest { void t() { assertThat(1).isOne(); } }' > svc/src/test/java/BTest.java"
case_ pass "a new migration" ":" "echo 'alter table a add b int;' > svc/src/main/resources/db/migration/V2__b.sql"
case_ pass "a test gains an assertion" ":" \
    "printf 'class ATest { void t() { assertThat(1).isOne(); assertThat(2).isTwo(); assertThat(3).isThree(); } }\n' > svc/src/test/java/ATest.java"
case_ pass "a version bump in an existing dependency" \
    "printf '<project><dependencies><dependency><version>1</version></dependency></dependencies></project>\n' > pom.xml" \
    "printf '<project><dependencies><dependency><version>2</version></dependency></dependencies></project>\n' > pom.xml"
case_ pass "a backlog item marked not-ready" ":" "printf '**Autopilot:** not-ready · **Risk:** low\n' >> BACKLOG.md"
case_ fail "a test file deleted" ":" "git rm -q svc/src/test/java/ATest.java"
case_ fail "@Disabled added" ":" \
    "printf 'class ATest { @Disabled void t() { assertThat(1).isOne(); assertThat(2).isTwo(); } }\n' > svc/src/test/java/ATest.java"
case_ fail "@Disabled on a test class, in column 0" ":" \
    "printf '@Disabled\nclass ATest { void t() { assertThat(1).isOne(); assertThat(2).isTwo(); } }\n' > svc/src/test/java/ATest.java"
case_ fail "@Disabled at the top of a very large test diff" ":" \
    "{ printf 'class BigTest {\n@Disabled void t() {}\n'; seq 1 40000 | sed 's/.*/  \/\/ filler line &/'; printf '}\n'; } > svc/src/test/java/BigTest.java"
case_ fail "a ready flag at the top of a very large backlog diff" ":" \
    "{ printf '**Autopilot:** ready · **Risk:** low\n'; seq 1 40000 | sed 's/.*/filler line &/'; } >> BACKLOG.md"
case_ fail "an assertion removed" ":" "printf 'class ATest { void t() { assertThat(1).isOne(); } }\n' > svc/src/test/java/ATest.java"
case_ fail "a plugin added" ":" "printf '<project><build><plugins><plugin><artifactId>x</artifactId></plugin></plugins></build></project>\n' > pom.xml"
case_ fail "a repository added" ":" "printf '<project><repositories><repository><url>http://evil</url></repository></repositories></project>\n' > pom.xml"
case_ fail "a dependency added in a module" "mkdir -p mod && echo '<project></project>' > mod/pom.xml" \
    "printf '<project><dependencies><dependency><artifactId>x</artifactId></dependency></dependencies></project>\n' > mod/pom.xml"
case_ fail "an applied migration edited" ":" "echo '-- x' >> svc/src/main/resources/db/migration/V1__a.sql"
case_ fail "an item marked ready" ":" "printf '**Autopilot:** ready · **Risk:** low\n' >> BACKLOG.md"
case_ fail "a test renamed out of the test pattern" ":" "git mv svc/src/test/java/ATest.java svc/src/test/java/ATest.java.off"
case_ fail "an applied migration deleted" ":" "git rm -q svc/src/main/resources/db/migration/V1__a.sql"
case_ fail "skipTests in a POM" ":" "printf '<project><properties><skipTests>true</skipTests></properties></project>\n' > pom.xml"
case_ fail "maven config changed" ":" "mkdir -p .mvn && echo '-DskipTests' > .mvn/maven.config"
case_ fail "Windows wrapper changed" "printf 'x\\n' > mvnw.cmd" "printf 'y\\n' > mvnw.cmd"
case_ fail "no build-config list on the base: fail closed" "rm .ai/rules/protected-paths.md" "echo x > notes.txt"
case_ fail "a PR cannot narrow the list for itself" ":" \
    "sed -i.bak '/^- \`mvnw\`/d' .ai/rules/protected-paths.md && rm .ai/rules/protected-paths.md.bak && printf 'y\\n' > mvnw"
J="printf '<project><build><plugins><plugin><executions><execution>\\n<goals>\\n<goal>check</goal>\\n</goals>\\n<minimum>0.60</minimum>\\n</execution></executions></plugin></plugins></build></project>\\n' > pom.xml"
case_ fail "coverage minimum lowered" "$J" "sed -i.bak 's/0.60/0.10/' pom.xml && rm pom.xml.bak"
case_ fail "coverage check goal removed" "$J" "sed -i.bak '/<goal>check<\\/goal>/d' pom.xml && rm pom.xml.bak"
case_ pass "jacoco version bump only" \
    "printf '<project><properties><jacoco.version>1</jacoco.version></properties></project>\\n' > pom.xml" \
    "printf '<project><properties><jacoco.version>2</jacoco.version></properties></project>\\n' > pom.xml"

if [ "$failures" -gt 0 ]; then echo "$failures case(s) failed"; exit 1; fi
echo "All factory guard cases passed."
