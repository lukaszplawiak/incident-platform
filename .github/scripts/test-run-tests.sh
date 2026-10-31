#!/usr/bin/env bash
# ============================================================
# Tests for scripts/factory/run-tests.sh — the autopilot's test gate and its artifact (backlog #0-160):
# what the reviewers read instead of "the gate passed". In a temporary repository with two modules and a
# stub ./mvnw that writes surefire and JaCoCo reports (or stale, broken or no reports) as $MVN_MODE says.
# The point of most cases: a report the gate cannot read, or one from an earlier build, stops the gate —
# an artifact with missing numbers would tell a reviewer "0 tests".
# Run: .github/scripts/test-run-tests.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }

mkdir -p "$WORK/repo/scripts/factory" "$WORK/repo/.ai/rules"
cp "$SRC"/scripts/factory/_common.sh "$SRC"/scripts/factory/_protected.sh "$SRC"/scripts/factory/changed-paths.sh \
   "$SRC"/scripts/factory/run-tests.sh "$SRC"/scripts/factory/maven-settings.xml "$WORK/repo/scripts/factory/"
cp "$SRC/.ai/rules/protected-paths.md" "$WORK/repo/.ai/rules/"

# The stub Maven: `verify` writes one surefire report and a jacoco.csv per module of -pl (MVN_MODE changes
# what it writes); `dependency:tree` writes -DoutputFile.
cat > "$WORK/repo/mvnw" <<'STUB'
#!/usr/bin/env bash
goal="" mods="" out=""
for a in "$@"; do
    case "$a" in
        verify|dependency:tree) goal=$a ;;
        -DoutputFile=*) out=${a#-DoutputFile=} ;;
    esac
done
while [ $# -gt 0 ]; do [ "$1" = -pl ] && mods=$2; shift; done
mode=${MVN_MODE:-ok}
if [ "$goal" = dependency:tree ]; then
    [ "$mode" = treefail ] && { echo "[ERROR] tree"; exit 1; }
    for m in $(echo "$mods" | tr ',' ' '); do echo "com.example:$m:jar:1.0" >> "$out"; done
    exit 0
fi
echo "[INFO] stub verify $mods"
# The gate's Maven call is _common.sh's factory_mvn (backlog #0-181): the environment's options never reach Maven.
[ -n "${MAVEN_OPTS:-}${JAVA_TOOL_OPTIONS:-}" ] && { echo "[ERROR] the environment's options reached Maven"; exit 1; }
[ "$mode" = nodocker ] && { echo "[ERROR] Could not find a valid Docker environment"; echo "BUILD FAILURE"; exit 1; }
[ "$mode" = fail ] && { echo "[ERROR] Tests run: 3, Failures: 1"; echo "BUILD FAILURE"; exit 1; }
[ "$mode" = stale ] && exit 0
[ "$mode" = touch ] && echo changed >> README.md
[ "$mode" = untracked ] && echo new > generated.txt
for m in $(echo "$mods" | tr ',' ' '); do
    mkdir -p "$m/target/surefire-reports" "$m/target/site/jacoco"
    if [ "$mode" = nosuite ]; then
        printf '<?xml version="1.0"?>\n<testrun name="A">\n</testrun>\n' > "$m/target/surefire-reports/TEST-A.xml"
    else
        # As surefire writes a class with @Nested tests: its tests="4" undercounts the five <testcase>
        # elements (seen on oncall-service: tests="30" over 37). The captured output holds tags as text.
        cat > "$m/target/surefire-reports/TEST-A.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite xmlns:xsi="x" name="A" time="1" tests="4" errors="0" skipped="1" failures="0">
  <properties><property name="p" value="v"/></properties>
  <testcase name="t1" classname="A" time="0"/>
  <testcase name="t2" classname="A" time="0">
    <system-out><![CDATA[log line <testcase name="fake"> <failure message="not real"/>
second line ]]></system-out>
  </testcase>
  <testcase name="t3" classname="A" time="0"><skipped/></testcase>
  <testcase name="n1" classname="A$Nested" time="0"/>
  <testcase name="n2" classname="A$Nested" time="0"/>
</testsuite>
XML
        if [ "$mode" = ignored ]; then
            printf '<?xml version="1.0"?>\n<testsuite name="B" tests="2" errors="1" skipped="0" failures="1">\n<testcase name="b1"><failure message="x" type="AssertionError">trace</failure></testcase>\n<testcase name="b2"><error message="y"/></testcase>\n</testsuite>\n' > "$m/target/surefire-reports/TEST-B.xml"
        else
            printf '<?xml version="1.0"?>\n<testsuite name="B" tests="2" errors="0" skipped="0" failures="0">\n<testcase name="b1"/>\n<testcase name="b2"/>\n</testsuite>\n' > "$m/target/surefire-reports/TEST-B.xml"
        fi
    fi
    if [ "$mode" = nocsv ]; then
        :
    elif [ "$mode" = badcsv ]; then
        printf 'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED\n' > "$m/target/site/jacoco/jacoco.csv"
    else
        printf 'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED\ng,p,A,1,9,0,0,10,30,0,0\ng,p,B,0,5,0,0,0,10,0,0\n' > "$m/target/site/jacoco/jacoco.csv"
    fi
done
echo "BUILD SUCCESS"
STUB
chmod +x "$WORK/repo/mvnw"

cd "$WORK/repo" || exit 1
git init -q -b main
printf '.ai/runs/\n.ai/STOP\ntarget/\n' > .gitignore
for m in notification-service oncall-service; do
    mkdir -p "$m/src/main/java/x" "$m/src/test/java/x"
    echo 'class A {}' > "$m/src/main/java/x/A.java"; echo 'class ATest {}' > "$m/src/test/java/x/ATest.java"
done
echo '# readme' > README.md
git add -A && git -c user.email=t@t -c user.name=t commit -q -m base

# branch <path> ... — a fresh branch from main with one line appended to each path, committed
branch() {
    git checkout -q -f main; git clean -qfdx -e .ai/runs >/dev/null
    git checkout -q -B feat/0-1-t main
    for p in "$@"; do echo "// change" >> "$p"; done
    git add -A && git -c user.email=t@t -c user.name=t commit -q -m t
}

# expect <name> <jq expression on the gate's JSON> <mode> [run-tests args...] — the gate's output must satisfy it
expect() {
    local name=$1 expr=$2 mode=$3; shift 3
    local out; out=$(MVN_MODE=$mode FACTORY_BASE_REF=main scripts/factory/run-tests.sh "$@")
    if printf '%s' "$out" | jq -e "$expr" >/dev/null 2>&1; then ok "$name"
    else fail "$name: '$expr' is false for: $(printf '%s' "$out" | head -c 600)"; fi
    LAST=$out
}
# artifact <name> <jq expression on the artifact file of the last run>
artifact() {
    local path; path=$(printf '%s' "$LAST" | jq -r '.artifact // empty')
    if [ -n "$path" ] && jq -e "$2" "$path" >/dev/null 2>&1; then ok "$1"
    else fail "$1: '$2' is false for the artifact '$path': $( [ -n "$path" ] && head -c 600 "$path")"; fi
}

echo "run-tests.sh: the gate's artifact"
branch notification-service/src/main/java/x/A.java
expect "a green build passes and names its artifact" '.status == "pass" and (.artifact | endswith("gate-r1-0.json"))' ok 0-1 1-0
artifact "the artifact holds the tested HEAD, clean" ".head == \"$(git rev-parse HEAD)\" and .dirty == false and .modules == \"notification-service\""
artifact "tests are the <testcase> elements, not surefire's tests= (undercounts @Nested), outside CDATA" '.tests["notification-service"] == {suites:2, tests:7, failures:0, errors:0, skipped:1}'
artifact "totals are the sum of the modules" '.totals == {tests:7, failures:0, errors:0, skipped:1}'
artifact "line coverage per module from jacoco.csv" '.coverage["notification-service"] == {lineMissed:10, lineCovered:40, ratio:0.8}'
artifact "a module outside the build is not reported" '.tests | has("oncall-service") | not'
artifact "built lists the modules the numbers come from" '.built == ["notification-service"]'
artifact "the dependency tree was written and is named" '.dependencyTree != null'
tree=$(jq -r '.dependencyTree' "$(printf '%s' "$LAST" | jq -r .artifact)")
if grep -q 'notification-service' "$tree"; then ok "the dependency tree file holds the modules"; else fail "dependency tree file '$tree' is empty or wrong"; fi

LAST_OPTS=$(MAVEN_OPTS=-DskipTests JAVA_TOOL_OPTIONS=-Dx MVN_MODE=ok FACTORY_BASE_REF=main scripts/factory/run-tests.sh 0-1 1-10)
if jq -e '.status == "pass"' <<<"$LAST_OPTS" >/dev/null; then ok "MAVEN_OPTS and JAVA_TOOL_OPTIONS do not reach Maven (factory_mvn, #0-181)"
else fail "the environment reached Maven: $LAST_OPTS"; fi
expect "no Docker: stop for a human, not fixable (_common.sh's MAVEN_ENV_ERRORS)" '.status == "stop" and .artifact == null' nodocker 0-1 1-11
expect "a green build whose reports hold failures (testFailureIgnore) passes" '.status == "pass"' ignored 0-1 1-9
artifact "the artifact reports the failures from the reports, not the exit code" '.totals == {tests:7, failures:1, errors:1, skipped:1}'

echo "run-tests.sh: what stops the gate"
expect "reports from an earlier build only: stop, never 0 tests" \
    '.status == "stop" and .artifact == null and (.summary[0] | test("no test report from this run for notification-service"))' stale 0-1 1-1
if [ ! -e .ai/runs/0-1/gate-r1-1.json ]; then ok "no artifact file is left after a stop"; else fail "a stopped gate left an artifact"; fi
expect "a report without a <testsuite>: stop" '.status == "stop" and (.summary[0] | test("cannot read the counts"))' nosuite 0-1 1-2
expect "no fresh jacoco.csv for a module with tests: stop" '.status == "stop" and (.summary[0] | test("no jacoco.csv from this run for notification-service"))' nocsv 0-1 1-7
expect "jacoco.csv with other columns: stop" '.status == "stop" and (.summary[0] | test("unexpected columns"))' badcsv 0-1 1-3
expect "dependency:tree failing after a green verify: stop" '.status == "stop" and (.summary[0] | test("dependency:tree failed"))' treefail 0-1 1-4
expect "a round that is not letters, digits and dashes: stop" '.status == "stop" and (.summary[0] | test("invalid round"))' ok 0-1 '../x'
expect "a red build is fixable, with no artifact" '.status == "fixable" and .artifact == null and .exitCode == 1' fail 0-1 1-5
expect "a build that changes a tracked file: stop" '.status == "stop" and (.summary[0] | test("changed the working tree"))' touch 0-1 1-6

echo "run-tests.sh: the clean tree and --working-tree"
branch notification-service/src/main/java/x/A.java
echo "// uncommitted" >> notification-service/src/main/java/x/A.java
expect "a dirty tree without --working-tree: stop" '.status == "stop" and (.summary[0] | test("not clean"))' ok 0-1 2-0
expect "--working-tree tests the dirty tree" '.status == "pass"' ok --working-tree manual 1
artifact "--working-tree records dirty: true" '.dirty == true and .totals.tests == 7'
expect "--working-tree: a build that adds an untracked file: stop" '.status == "stop" and (.summary[0] | test("changed the working tree"))' untracked --working-tree manual 2
rm -f generated.txt
: > .ai/runs/LOCK
expect "--working-tree while an autopilot run holds the lock: stop" '.status == "stop" and (.summary[0] | test("manual /review only"))' ok --working-tree manual 3
rm -f .ai/runs/LOCK
echo "// uncommitted" >> oncall-service/src/main/java/x/A.java
expect "--working-tree builds the modules of uncommitted changes too" '.status == "pass" and .modules == "notification-service,oncall-service"' ok --working-tree manual 4
branch README.md
echo "// uncommitted" >> oncall-service/src/main/java/x/A.java
expect "--working-tree: a module changed only in the working tree is built" '.status == "pass" and .modules == "oncall-service"' ok --working-tree manual 5
echo "<project/>" > pom.xml
expect "--working-tree: an uncommitted root POM builds every module" '.modules | startswith("shared,service-parent,")' stale --working-tree manual 6
rm -f pom.xml

echo "run-tests.sh: nothing to build"
branch README.md
expect "no Maven module changed: pass with an artifact" '.status == "pass" and .artifact != null' ok 0-1 3-0
artifact "the artifact says nothing was built" '.modules == "" and .built == [] and .totals.tests == 0 and (.note | test("nothing to build"))'

if [ "$failures" -gt 0 ]; then echo "$failures case(s) failed"; exit 1; fi
echo "All run-tests.sh cases passed"
