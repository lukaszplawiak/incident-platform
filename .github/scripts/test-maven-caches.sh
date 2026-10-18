#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-maven-caches.rb (backlog #0-145): one temporary directory per case, holding a
# copy of the repository's two Maven cache actions and a workflow that calls them, which the case then
# breaks (or extends), and the checker run on it. Every failing case names the message it must give, so a
# case cannot pass on another rule's refusal.
#
# Run by the build-and-test job in .github/workflows/ci.yml, before the checker; locally:
#   .github/scripts/test-maven-caches.sh
# ============================================================
# The cases hand the checker a workflow's text: `~` and `$` in single quotes are literal on purpose, the
# checker reads them as written (backlog #0-149).
# shellcheck disable=SC2016,SC2088
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
CHECKER=$HERE/check-maven-caches.rb
RESTORE=.github/actions/restore-maven-caches/action.yml
SAVE=.github/actions/save-maven-caches/action.yml
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0
dir=""

# The case's layout: the real actions, and a workflow whose job restores and saves through them.
layout() {
    mkdir -p "$dir/.github/workflows" "$dir/.github/actions/restore-maven-caches" "$dir/.github/actions/save-maven-caches"
    cp "$REPO/$RESTORE" "$dir/$RESTORE"
    cp "$REPO/$SAVE" "$dir/$SAVE"
    cat > "$dir/.github/workflows/ci.yml" <<'EOF'
name: CI
on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4.4.0
      - uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3 # v4.9.1
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Restore the Maven caches
        id: maven-caches
        uses: ./.github/actions/restore-maven-caches
      - run: ./mvnw verify
      - name: Save the Maven caches
        uses: ./.github/actions/save-maven-caches
        with:
          wrapper-cache-hit: ${{ steps.maven-caches.outputs.wrapper-cache-hit }}
          wrapper-key: ${{ steps.maven-caches.outputs.wrapper-key }}
          repository-cache-hit: ${{ steps.maven-caches.outputs.repository-cache-hit }}
          repository-key: ${{ steps.maven-caches.outputs.repository-key }}
EOF
}

# workflow <file> <steps...>: another workflow, one job, the given steps (YAML, indented by the caller).
workflow() {
    local file=$1; shift
    { printf 'name: W\non: push\njobs:\n  scan:\n    runs-on: ubuntu-latest\n    steps:\n'; printf '%s\n' "$@"; } \
        > "$dir/.github/workflows/$file"
}

# edit <file> <perl expression>: the same edit on GNU and BSD.
edit() {
    perl -0pi -e "$2" "$dir/$1"
}

# [MSG=<regex>] case_ <expect: pass|fail> <name> <setup command...>
case_() {
    local expect=$1 name=$2; shift 2
    dir="$WORK/case$((n += 1))"
    mkdir -p "$dir"
    layout
    "$@"
    local out rc
    set +e
    out=$(ruby "$CHECKER" "$dir" 2>&1)
    rc=$?
    set -e
    # A failure must be the checker's own verdict, with the message of the rule the case breaks.
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ]; } \
       || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -qE "backlog #0-145" <<<"$out" \
            && grep -qE "$MSG" <<<"$out"; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

as_is() { :; }
owasp_db() {
    workflow owasp.yml '      - uses: actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9' \
        '        with:' '          path: ~/.m2/repository/org/owasp/dependency-check-data' '          key: owasp-db'
}
other_cache_glob() {
    workflow pip.yml '      - uses: actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9' \
        '        with:' '          path: ~/.cache/*' '          key: pip'
}
setup_java_gradle() {
    workflow g.yml '      - uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3' \
        '        with:' "          java-version: '21'" '          cache: gradle'
}

wf_cache() { # wf_cache <uses> <path line(s)...>: a workflow step caching the given paths
    local uses=$1; shift
    local lines=('      - name: Own cache' "        uses: $uses" '        with:')
    if [ "$#" -eq 1 ]; then
        lines+=("          path: $1")
    else
        lines+=('          path: |')
        for p in "$@"; do lines+=("            $p"); done
    fi
    lines+=('          key: own')
    workflow own.yml "${lines[@]}"
}
wf_repository_subdir() { wf_cache actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 '~/.m2/repository/org/springframework'; }
wf_repository() { wf_cache actions/cache/restore@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 '~/.m2/repository'; }
wf_wrapper_second_line() { wf_cache actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 '~/.npm' '~/.m2/wrapper'; }
wf_home_var() { wf_cache actions/cache/restore@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 '$HOME/.m2/wrapper'; }
wf_home_braces() { wf_cache actions/cache/restore@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 '${{ env.HOME }}/.m2/repository'; }
wf_runner_home() { wf_cache actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 '/home/runner/.m2'; }
wf_save() { wf_cache actions/cache/save@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 '~/.m2/repository'; }
wf_trailing_slash() { wf_cache actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 '~/.m2/'; }
wf_other_version() { wf_cache actions/cache@v3 '~/.m2/repository'; }
wf_glob_home() { wf_cache actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 '~/.m*'; }
wf_yaml_extension() { wf_repository; mv "$dir/.github/workflows/own.yml" "$dir/.github/workflows/own.yaml"; }
wf_setup_java_maven() {
    workflow sj.yml '      - uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3' \
        '        with:' "          java-version: '21'" '          cache: maven'
}
third_action() {
    mkdir -p "$dir/.github/actions/mine"
    cat > "$dir/.github/actions/mine/action.yml" <<'EOF'
name: Mine
runs:
  using: composite
  steps:
    - uses: actions/cache/restore@55cc8345863c7cc4c66a329aec7e433d2d1c52a9
      with:
        path: ~/.m2/repository
        key: mine
EOF
}
wrapper_restore_keys() {
    edit "$RESTORE" 's{(\n        path: ~/\.m2/wrapper\n        key: [^\n]*\n)}{$1        restore-keys: |\n          maven-wrapper-\n}'
}
wrapper_with_repository() {
    edit "$RESTORE" 's{\n        path: ~/\.m2/wrapper\n}{\n        path: |\n          ~/.m2/wrapper\n          ~/.m2/repository\n}'
}
repository_is_m2() { edit "$RESTORE" 's{path: ~/\.m2/repository\n}{path: ~/.m2\n}'; edit "$SAVE" 's{path: ~/\.m2/repository\n}{path: ~/.m2\n}'; }
repository_glob() { edit "$RESTORE" 's{path: ~/\.m2/repository\n}{path: ~/.m2/*\n}'; }
save_other_path() {
    edit "$SAVE" 's{\n        path: ~/\.m2/repository\n}{\n        path: |\n          ~/.m2/repository\n          ~/.gradle\n}'
}
save_no_wrapper() { edit "$SAVE" 's{\n        path: ~/\.m2/wrapper\n}{\n        path: ~/.gradle\n}'; }
restore_twice() {
    edit "$RESTORE" 's{(    - name: Restore the Maven repository\n.*?restore-keys: \|\n[^\n]*\n)}{$1$1}s'
}
no_restore_action() { rm "$dir/$RESTORE"; }
key_without_save_action() { edit "$RESTORE" "s{, '\\.github/actions/save-maven-caches/action\\.yml'}{}"; }
key_without_checker() { edit "$RESTORE" "s{'\\.github/scripts/check-maven-wrapper-sum\\.sh', }{}"; }
key_without_restore_action() { edit "$RESTORE" "s{'\\.github/actions/restore-maven-caches/action\\.yml', }{}"; }
key_without_properties() { edit "$RESTORE" "s{'\\.mvn/wrapper/maven-wrapper\\.properties', }{}"; }
broken_yaml() { printf 'name: [unclosed\n' > "$dir/.github/workflows/broken.yml"; }
no_workflows() { rm -r "$dir/.github/workflows"; }

echo "check-maven-caches.rb:"
case_ pass "the repository's two actions and a workflow calling them" as_is
case_ pass "OWASP Dependency-Check's database under ~/.m2/repository/org/owasp" owasp_db
case_ pass "another tool's cache with a glob (~/.cache/*)" other_cache_glob
case_ pass "setup-java caching gradle" setup_java_gradle

MSG="'Own cache' caches ~/\.m2/repository: the Maven caches go through" case_ fail "a workflow restoring ~/.m2/repository itself" wf_repository
MSG="caches ~/\.m2/repository/org/springframework: the Maven caches go through" case_ fail "a workflow caching a subdirectory of the repository other than OWASP's" wf_repository_subdir
MSG="caches ~/\.m2/wrapper: the Maven caches go through" case_ fail "~/.m2/wrapper on the second line of a workflow's path list" wf_wrapper_second_line
MSG="caches ~/\.m2/wrapper: the Maven caches go through" case_ fail "\$HOME/.m2/wrapper in a workflow" wf_home_var
MSG="caches ~/\.m2/repository: the Maven caches go through" case_ fail "\${{ env.HOME }}/.m2/repository in a workflow" wf_home_braces
MSG="caches ~/\.m2: the Maven caches go through" case_ fail "/home/runner/.m2 in a workflow" wf_runner_home
MSG="caches ~/\.m2/repository: the Maven caches go through" case_ fail "a workflow saving ~/.m2/repository" wf_save
MSG="caches ~/\.m2: the Maven caches go through" case_ fail "~/.m2/ with a trailing slash in a workflow" wf_trailing_slash
MSG="caches ~/\.m2/repository: the Maven caches go through" case_ fail "an actions/cache by tag in a workflow" wf_other_version
MSG="caches ~/\.m\*: the Maven caches go through" case_ fail "a glob in a workflow that can match ~/.m2 (~/.m*)" wf_glob_home
MSG="caches ~/\.m2/repository: the Maven caches go through" case_ fail "a .yaml workflow" wf_yaml_extension
MSG="setup-java's .cache: maven." case_ fail "setup-java with cache: maven" wf_setup_java_maven
MSG="mine/action\.yml::step .* caches ~/\.m2/repository" case_ fail "a third action caching ~/.m2/repository" third_action
MSG="restores ~/\.m2/wrapper with restore-keys" case_ fail "restore-keys on the Maven distribution" wrapper_restore_keys
MSG="path list must be exactly ~/\.m2/wrapper" case_ fail "the Maven distribution cached with the repository" wrapper_with_repository
MSG="caches ~/\.m2: that takes ~/\.m2/wrapper with it" case_ fail "the repository cache widened to ~/.m2" repository_is_m2
MSG="caches ~/\.m2/\*: that takes ~/\.m2/wrapper with it" case_ fail "a glob in the repository cache (~/.m2/*)" repository_glob
MSG="the Maven repository is saved with path ~/\.m2/repository, ~/\.gradle but restored with ~/\.m2/repository" case_ fail "save and restore paths differ" save_other_path
MSG="saves the Maven distribution 0 times, expected once" case_ fail "the save action does not save the distribution" save_no_wrapper
MSG="restores the Maven repository 2 times, expected once" case_ fail "the repository restored twice" restore_twice
MSG="restore-maven-caches/action\.yml is missing" case_ fail "no restore action" no_restore_action
MSG="does not hash \.github/actions/save-maven-caches/action\.yml" case_ fail "the distribution's key leaves out the save action" key_without_save_action
MSG="does not hash \.mvn/wrapper/maven-wrapper\.properties" case_ fail "the distribution's key leaves out the properties" key_without_properties
MSG="does not hash \.github/scripts/check-maven-wrapper-sum\.sh" case_ fail "the distribution's key leaves out the checker" key_without_checker
MSG="does not hash \.github/actions/restore-maven-caches/action\.yml" case_ fail "the distribution's key leaves out the restore action" key_without_restore_action
MSG="broken\.yml cannot be read as YAML" case_ fail "a workflow that is not YAML" broken_yaml
MSG="no workflow found" case_ fail "no workflows (not run from the root)" no_workflows

if [ "$failures" -gt 0 ]; then
    echo "::error::$failures of $n cases failed (backlog #0-145)"
    exit 1
fi
echo "All $n cases passed."
