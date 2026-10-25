#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-runtime-setup.rb (backlog #0-166, ADR-0040; Node: backlog #0-163, ADR-0043): one
# temporary directory per case, holding a workflow whose job sets Ruby and Node up and then runs a .rb check and a
# Node test, the setup-ruby and setup-node actions and a script that runs Ruby, which the case then breaks (or
# extends), and the checker run on it. Every failing case names the message it must give, and the backlog item of
# its runtime, so a case cannot pass on another rule's refusal.
#
# Run by the lint-workflows job in .github/workflows/ci.yml, before the checker; locally:
#   .github/scripts/test-runtime-setup.sh
# ============================================================
# The cases write workflows as literal text: `$` in single quotes is meant.
# shellcheck disable=SC2016
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
CHECKER=${CHECKER:-$HERE/check-runtime-setup.rb}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0
dir=""

# put <file> <line...>: writes the lines into the case's file.
put() {
    local file=$1; shift
    mkdir -p "$(dirname "$dir/$file")"
    printf '%s\n' "$@" > "$dir/$file"
}

# job <step line...>: a workflow with one job, checkout first, then the given step lines (from line 8).
job() {
    put .github/workflows/w.yml 'name: W' 'on: push' 'jobs:' '  lint:' '    runs-on: ubuntu-latest' '    steps:' \
        '      - uses: actions/checkout@0000000000000000000000000000000000000000 # v4' "$@"
}

# The case's layout: a job that sets Ruby and Node up and runs a check, a test script that runs Ruby and a Node test,
# both actions, and scripts that do and do not run Ruby (one only mentions it in a comment).
layout() {
    put .github/workflows/ci.yml 'name: CI' 'on: push' 'jobs:' '  lint:' '    runs-on: ubuntu-latest' '    steps:' \
        '      - uses: actions/checkout@0000000000000000000000000000000000000000 # v4' \
        '      - name: Set up Ruby' '        uses: ./.github/actions/setup-ruby' \
        '      - name: Check' '        run: |' '          .github/scripts/test-it.sh' '          ruby .github/scripts/check-it.rb' \
        '      # ruby in a YAML comment runs nothing' \
        '      - name: Plain' '        run: |' '          # needs ruby? no: this comment runs nothing' '          .github/scripts/plain.sh' \
        '          echo "RUBYOPT, ruby/setup-ruby, .ruby-version and a ruby-version input are words, not Ruby"' \
        '          echo "setup-node, node_modules, .node-version, a node-version input, .github/actions/node and index.js are not Node either"' \
        '      - name: Set up Node' '        uses: ./.github/actions/setup-node' \
        '      - name: Node test' '        run: node .github/scripts/test-it.mjs'
    put .github/actions/setup-ruby/action.yml 'name: Set up Ruby' 'description: d' 'runs:' '  using: composite' '  steps:' \
        '    - uses: ruby/setup-ruby@0000000000000000000000000000000000000000 # v1' \
        '    - name: Versions' '      shell: bash' '      run: ruby --version'
    put .github/actions/setup-node/action.yml 'name: Set up Node' 'description: d' 'runs:' '  using: composite' '  steps:' \
        '    - uses: actions/setup-node@0000000000000000000000000000000000000000 # v7' \
        '    - name: Version' '      shell: bash' '      run: node --version'
    put .github/scripts/test-it.sh '#!/bin/sh' 'out=$(ruby "$(dirname "$0")/check-it.rb")'
    put .github/scripts/plain.sh '#!/bin/sh' '# Needs ruby in CI? No: only this comment says so.' 'echo plain'
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
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ] && grep -qE "${MSG:-come after}" <<<"$out"; } \
       || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -qE "${WHY:-backlog #0-166}" <<<"$out" \
            && grep -qE "$MSG" <<<"$out"; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

as_is() { :; }
no_setup() { job '      - name: Check' '        run: ruby .github/scripts/check-it.rb'; }
setup_after() {
    job '      - name: Check' '        run: ruby .github/scripts/check-it.rb' \
        '      - name: Set up Ruby' '        uses: ./.github/actions/setup-ruby'
}
script_only() { job '      - name: Tests' '        run: .github/scripts/test-it.sh'; }
script_dot_slash() { job '      - name: Tests' '        run: ./.github/scripts/test-it.sh --verbose'; }
rb_file_only() { job '      - name: Check' '        run: bundle exec .github/scripts/check-it.rb'; }
hook_script() {
    put .claude/hooks/h.sh '#!/bin/sh' 'ruby -e 1'
    job '      - name: Hook' '        run: .claude/hooks/h.sh'
}
other_job() {   # the setup in one job does not reach another
    put .github/workflows/w.yml 'name: W' 'on: push' 'jobs:' '  a:' '    runs-on: ubuntu-latest' '    steps:' \
        '      - uses: ./.github/actions/setup-ruby' '  b:' '    runs-on: ubuntu-latest' '    steps:' \
        '      - name: Check' '        run: ruby .github/scripts/check-it.rb'
}
upstream_in_workflow() {
    job '      - name: Ruby' '        uses: ruby/setup-ruby@0000000000000000000000000000000000000000 # v1' \
        '      - name: Check' '        run: ruby .github/scripts/check-it.rb'
}
version_input() {
    put .github/actions/setup-ruby/action.yml 'name: Set up Ruby' 'description: d' 'runs:' '  using: composite' '  steps:' \
        '    - uses: ruby/setup-ruby@0000000000000000000000000000000000000000 # v1' '      with:' "        ruby-version: '3.2'" \
        '    - name: Versions' '      shell: bash' '      run: ruby --version'
}
action_order() {
    put .github/actions/setup-ruby/action.yml 'name: Set up Ruby' 'description: d' 'runs:' '  using: composite' '  steps:' \
        '    - name: Versions' '      shell: bash' '      run: ruby --version' \
        '    - uses: ruby/setup-ruby@0000000000000000000000000000000000000000 # v1'
}
other_action() {
    put .github/actions/act/action.yml 'name: Act' 'description: d' 'runs:' '  using: composite' '  steps:' \
        '    - name: Check' '      shell: bash' '      run: ruby .github/scripts/check-it.rb'
}
node_no_setup() { job '      - name: Test' '        run: node .github/scripts/test-it.mjs'; }
node_mjs_only() { job '      - name: Test' '        run: .github/scripts/test-it.mjs --verbose'; }
node_script() {
    put .github/scripts/js.sh '#!/bin/sh' 'node "$(dirname "$0")/test-it.mjs"'
    job '      - name: Tests' '        run: .github/scripts/js.sh'
}
node_upstream_in_workflow() {
    job '      - name: Node' '        uses: actions/setup-node@0000000000000000000000000000000000000000 # v7' \
        '      - name: Test' '        run: node .github/scripts/test-it.mjs'
}
node_version_input() {
    put .github/actions/setup-node/action.yml 'name: Set up Node' 'description: d' 'runs:' '  using: composite' '  steps:' \
        '    - uses: actions/setup-node@0000000000000000000000000000000000000000 # v7' '      with:' "        node-version: '24'" \
        '    - name: Version' '      shell: bash' '      run: node --version'
}
node_action_order() {
    put .github/actions/setup-node/action.yml 'name: Set up Node' 'description: d' 'runs:' '  using: composite' '  steps:' \
        '    - name: Version' '      shell: bash' '      run: node --version' \
        '    - uses: actions/setup-node@0000000000000000000000000000000000000000 # v7'
}
ruby_setup_is_not_node() {   # each runtime needs its own setup
    job '      - uses: ./.github/actions/setup-ruby' '      - name: Test' '        run: node .github/scripts/test-it.mjs'
}
yaml_extension() { no_setup; mv "$dir/.github/workflows/w.yml" "$dir/.github/workflows/w.yaml"; }
broken_yaml() { put .github/workflows/broken.yml 'jobs: [unclosed'; }
no_workflows() { rm -rf "$dir/.github/workflows"; }

echo "check-runtime-setup: what passes"
MSG="Checked 10 steps in 3 workflows and actions: all 2 that run Ruby come after \./\.github/actions/setup-ruby; all 2 that run Node come after \./\.github/actions/setup-node" \
    case_ pass "Ruby and Node after their setup, comments and words that only contain ruby or node" as_is

echo "check-runtime-setup: what fails (Ruby)"
MSG="file=\.github/workflows/w\.yml,line=8::job lint: step 'Check' runs Ruby \(.ruby.\) before" \
    case_ fail "a ruby step in a job without the setup" no_setup
MSG="line=8::job lint: step 'Check' runs Ruby" case_ fail "the setup after the step" setup_after
MSG="step 'Tests' runs Ruby \(\.github/scripts/test-it\.sh \(it runs .ruby.\)\)" \
    case_ fail "a script that runs Ruby" script_only
MSG="step 'Tests' runs Ruby \(\.github/scripts/test-it\.sh" case_ fail "a script called as ./.github/..." script_dot_slash
MSG="step 'Check' runs Ruby \(.\.rb.\)" case_ fail "a .rb file without the ruby command" rb_file_only
MSG="step 'Hook' runs Ruby \(\.claude/hooks/h\.sh" case_ fail "a hook script that runs Ruby" hook_script
MSG="job b: step 'Check' runs Ruby" case_ fail "the setup of another job" other_job
MSG="step 'Ruby' uses ruby/setup-ruby: set Ruby up through \./\.github/actions/setup-ruby only" \
    case_ fail "ruby/setup-ruby in a workflow" upstream_in_workflow
MSG="gives ruby/setup-ruby a ruby-version: \.ruby-version alone decides it" case_ fail "a ruby-version input" version_input
MSG="file=\.github/actions/setup-ruby/action\.yml,line=6::.*step 'Versions' runs Ruby" \
    case_ fail "the action runs Ruby before ruby/setup-ruby" action_order
MSG="file=\.github/actions/act/action\.yml,line=6::.*step 'Check' runs Ruby" case_ fail "another composite action" other_action
MSG="w\.yaml,line=8::" case_ fail "a .yaml workflow" yaml_extension
MSG="broken\.yml cannot be read as YAML" case_ fail "a workflow that is not YAML" broken_yaml
MSG="no workflow found" case_ fail "no workflows (not run from the root)" no_workflows

echo "check-runtime-setup: what fails (Node)"
export WHY="backlog #0-163, ADR-0043"
MSG="file=\.github/workflows/w\.yml,line=8::job lint: step 'Test' runs Node \(.node.\) before .uses: \./\.github/actions/setup-node." \
    case_ fail "a node step in a job without the setup" node_no_setup
MSG="step 'Test' runs Node \(.\.mjs.\)" case_ fail "an .mjs file run without the node command" node_mjs_only
MSG="step 'Tests' runs Node \(\.github/scripts/js\.sh \(it runs .node.\)\)" case_ fail "a script that runs Node" node_script
MSG="step 'Node' uses actions/setup-node: set Node up through \./\.github/actions/setup-node only" \
    case_ fail "actions/setup-node in a workflow" node_upstream_in_workflow
MSG="gives actions/setup-node a node-version: \.node-version alone decides it" case_ fail "a node-version input" node_version_input
MSG="file=\.github/actions/setup-node/action\.yml,line=6::.*step 'Version' runs Node" \
    case_ fail "the action runs Node before actions/setup-node" node_action_order
MSG="step 'Test' runs Node" case_ fail "the Ruby setup does not set Node up" ruby_setup_is_not_node
unset WHY

if [ "$failures" -gt 0 ]; then
    echo "::error::$failures of $n cases failed (backlog #0-166, #0-163)"
    exit 1
fi
echo "All $n cases passed."
