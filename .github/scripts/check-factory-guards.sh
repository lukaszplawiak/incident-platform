#!/usr/bin/env bash
# ============================================================
# AI factory guards (backlog #0-113): changes that an unattended agent must not be able to merge
# without the owner's approval, whatever the reviewers said. Detects, between <base> and <head>:
#   1. a deleted test file, or a new @Disabled in a test;
#   2. a pre-existing test file with fewer assertion lines than before (assert/assertThat/verify/expect);
#   3. a POM that adds a <plugin>, <repository>, <pluginRepository> or <dependency>;
#   4. a Flyway migration that exists in <base> and was modified or deleted;
#   5. a backlog item whose `**Autopilot:** ready` line was added (only the owner marks items ready);
#   6. build configuration that decides what verification runs: a path of the build-config list of
#      .ai/rules/protected-paths.md as it is on the merge base (`.mvn/`, `mvnw`, `mvnw.cmd`; read through
#      scripts/factory/_protected.sh, not copied here — backlog #0-122; an unreadable list is a finding),
#      a POM line that skips or excludes tests or coverage, or any added or removed line of the coverage
#      check's configuration (<minimum>, <rule>, <limit>, the `check` goal, a <phase>, an <include>...;
#      not the plugin's version).
# Renames are not followed (--no-renames): a test renamed out of src/test/**/*.java counts as deleted.
# Prints one ::error line per finding and exits 1 when there is any, 0 otherwise. Whether an approval
# overrides the result is decided by .github/workflows/factory-guards.yml, not here.
#
# Usage (from the repository root): check-factory-guards.sh <base-sha> <head-sha>
# Tests: test-factory-guards.sh. Portable to bash 3.2.
# ============================================================
set -euo pipefail

base=${1:?base sha}; head=${2:?head sha}
mb=$(git merge-base "$base" "$head")
found=0
flag() { echo "::error file=$1::$2 (backlog #0-113: needs the owner's approval)"; found=$((found + 1)); }

# 1. deleted tests, new @Disabled
while IFS= read -r f; do
    [ -n "$f" ] && flag "$f" "test file deleted"
done < <(git diff --no-renames --name-only --diff-filter=D "$mb" "$head" -- '*/src/test/*')
while IFS= read -r f; do
    [ -n "$f" ] || continue
    # Never `git diff | grep -q` under pipefail: grep exits at the first match, git gets SIGPIPE and the
    # pipeline "fails" — on a large diff the check would silently pass. Capture, then match.
    # Added lines only (not the "+++ b/<file>" header); @Disabled may start the line (a class annotation).
    added_test=$({ git diff "$mb" "$head" -- "$f" | grep -E '^\+' | grep -vE '^\+\+\+ ' || true; })
    if grep -qE '@Disabled' <<<"$added_test"; then flag "$f" "@Disabled added"; fi
done < <(git diff --no-renames --name-only --diff-filter=AM "$mb" "$head" -- '*/src/test/*.java')

# 2. fewer assertions in a test file that existed before
count_asserts() { { grep -oE '\b(assert[A-Za-z]*|verify|expect[A-Za-z]*)[[:space:]]*\(' || true; } | wc -l | tr -d ' '; }
while IFS= read -r f; do
    [ -n "$f" ] || continue
    before=$(git show "$mb:$f" 2>/dev/null | count_asserts)
    after=$(git show "$head:$f" 2>/dev/null | count_asserts)
    if [ "${after:-0}" -lt "${before:-0}" ]; then flag "$f" "assertions went from $before to $after"; fi
done < <(git diff --no-renames --name-only --diff-filter=M "$mb" "$head" -- '*/src/test/*.java')

# 3. POM supply chain
while IFS= read -r f; do
    [ -n "$f" ] || continue
    # Net count per element, so a version bump (a removed and an added line) is not a new element.
    added=$({ git diff "$mb" "$head" -- "$f" | grep -E '^\+' | grep -vE '^\+\+\+' | grep -oE '<(plugin|repository|pluginRepository|dependency)>' || true; } | wc -l | tr -d ' ')
    removed=$({ git diff "$mb" "$head" -- "$f" | grep -E '^-' | grep -vE '^---' | grep -oE '<(plugin|repository|pluginRepository|dependency)>' || true; } | wc -l | tr -d ' ')
    if [ "$added" -gt "$removed" ]; then flag "$f" "adds $((added - removed)) plugin/repository/dependency element(s)"; fi
done < <(git diff --no-renames --name-only "$mb" "$head" -- 'pom.xml' '*/pom.xml')

# 4. applied migration edited
while IFS= read -r f; do
    [ -n "$f" ] || continue
    if git cat-file -e "$mb:$f" 2>/dev/null; then flag "$f" "Flyway migration that already exists on the base was modified or deleted"; fi
done < <(git diff --no-renames --name-only --diff-filter=DM "$mb" "$head" -- '*/src/main/resources/db/migration/*')

# 5. ready flag set
added_backlog=$({ git diff "$mb" "$head" -- BACKLOG.md | grep -E '^\+' | grep -vE '^\+\+\+' || true; })
if grep -qE '\*\*Autopilot:\*\*[[:space:]]*ready([^a-z-]|$)' <<<"$added_backlog"; then
    flag BACKLOG.md "an item was marked **Autopilot:** ready"
fi

# 6. build configuration — the list as it is on the merge base, so editing the list does not narrow it for
# the same PR. The parser (_protected.sh) is this checkout's, like this script itself: a PR that changes
# either can weaken the rule, which is why both are owner paths (CODEOWNERS, protected-paths.md).
. "$(dirname "$0")/../../scripts/factory/_protected.sh"
build_paths=()
while IFS= read -r p; do [ -n "$p" ] && build_paths+=("$p"); done < <(protected_paths build-config "$mb" || true)
if [ "${#build_paths[@]}" -eq 0 ]; then
    flag .ai/rules/protected-paths.md "the build-config list cannot be read on the merge base, so build configuration cannot be checked"
else
    while IFS= read -r f; do
        [ -n "$f" ] && flag "$f" "build configuration changed (decides what verification runs)"
    done < <(git diff --no-renames --name-only "$mb" "$head" -- "${build_paths[@]}")
fi
while IFS= read -r f; do
    [ -n "$f" ] || continue
    added_pom=$({ git diff "$mb" "$head" -- "$f" | grep -E '^\+' | grep -vE '^\+\+\+' || true; })
    if grep -qE 'skipTests|maven\.test\.skip|testFailureIgnore|<skip>[[:space:]]*true|haltOnFailure|jacoco\.skip|<excludes?>' <<<"$added_pom"; then
        flag "$f" "adds a property that skips or excludes tests or coverage"
    fi
    # Same patterns as scripts/factory/changed-paths.sh: a lowered <minimum> or a deleted rule weakens the
    # coverage check as much as a skip property.
    changed_pom=$({ git diff "$mb" "$head" -- "$f" | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' || true; })
    if grep -qE '<(minimum|maximum|counter|value|element|limits?|rules?|phase|includes?)([[:space:]/>])|<goal>[[:space:]]*check[[:space:]]*</goal>' <<<"$changed_pom"; then
        flag "$f" "changes the coverage check's configuration (threshold, rule, goal or phase)"
    fi
done < <(git diff --no-renames --name-only "$mb" "$head" -- 'pom.xml' '*/pom.xml')

if [ "$found" -gt 0 ]; then
    echo "$found change(s) need the owner's approval."
    exit 1
fi
echo "No change needs the owner's approval."
