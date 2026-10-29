#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-rule-claims.rb (backlog #0-184): one temporary directory per case, holding rule files,
# a pattern catalogue, a ruleset of main and two workflows whose steps run the checks the labels name, which the case
# then breaks (or extends), and the checker run on it. Every failing case names the message it must give, so a case
# cannot pass on another rule's refusal.
#
# Run by the factory-guards job in .github/workflows/factory-guards.yml, before the checker; locally (Ruby of
# .ruby-version, ADR-0040):
#   .github/scripts/test-rule-claims.sh
# ============================================================
# The cases write workflows as literal text: `$` in single quotes is meant.
# shellcheck disable=SC2016
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
CHECKER=${CHECKER:-$HERE/check-rule-claims.rb}
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

# edit <file> <sed expression>: changes the case's file in place (portable: no -i).
edit() {
    sed -e "$2" "$dir/$1" > "$dir/$1.new" && mv "$dir/$1.new" "$dir/$1"
}

# del <path...>: removes the case's files or directories (the case's directory is known only once case_ runs).
del() {
    local f
    for f in "$@"; do rm -r "${dir:?}/$f"; done
}

# The case's layout: every kind of label the rule files use, each true, and the patterns citing live ids.
layout() {
    put .ai/rules/review/_common.md '# Common' '' 'Two labels: "Enforced by" and "Backed by".'
    put .ai/rules/review/architecture.md '# Architecture' '' \
        '- **ARC-01 One thing.** Text.' \
        '  Check: a diff that does it.' \
        '- **ARC-20** retired: split into ARC-22, 2026-10-26.' \
        '- **ARC-22 Outbox.** Text.' \
        '- **ARC-40 No profile ships.** Text (#0-81). Enforced by:' \
        '  `check-profiles.sh`. (A literal secret there is SEC-30'"'"'s.)'
    put .ai/rules/review/security.md '# Security' '' \
        '- **SEC-10 A path has a role.** Backed by: `DenyByDefaultTest` (routes not listed are refused).' \
        '- **SEC-32** retired — merged into SEC-30, 2026-10-26.' \
        '- **SEC-33 No local config ships.** The jars leave it out, planted by build'"'"'s "Plant a config" step and' \
        '  checked after. Backed by: `check-jars.sh` (the two known patterns).'
    put .ai/rules/review/k8s.md '# K8s' '' \
        '- **K8S-03 The manifests render.** Enforced by: validate-k8s (the render and validate steps).' \
        '- **K8S-20 Own namespace.** Enforced by: `check-envs.rb` (rule 1).'
    put .ai/rules/review/factory.md '# Factory' '' \
        '- **FAC-S03 Least privilege.** Backed by: `test-hooks.sh` (the seeder'"'"'s scope too).' \
        '- **FAC-A02 Answers fail closed.** Backed by: `check-factory-guards.sh` (a deleted test file) and `seed-bugs.js`.'
    put .ai/audit/benchmark/patterns.md '# Patterns' '' '| Id | Dimension | Rule | Defect to plant |' '|---|---|---|---|' \
        '| P-05 | security | SEC-10 | drop the `@PreAuthorize` \| or a path |' \
        '| P-06 | architecture | ARC-22 | send directly |' \
        '| P-14 | migration | MIG-02 | retired 2026-10-26 (#0-115): cannot be planted |' \
        '| P-23 | architecture | FAC-A02 | drop the `!answer \|\|` of a guard |'
    put .github/repository-settings/rulesets/main.json \
        '{"rules": [{"type": "required_status_checks", "parameters": {"required_status_checks": [' \
        '{"context": "Build"}, {"context": "Validate K8s"}, {"context": "Factory guards"}]}}]}'
    put .github/workflows/ci.yml 'name: CI' 'on:' '  push:' '  pull_request:' 'jobs:' \
        '  build:' '    name: Build' \
        "    if: \${{ github.event_name == 'push' || needs.detect.outputs.code == 'true' }}" \
        '    runs-on: ubuntu-latest' '    steps:' \
        '      - name: No profile ships' '        run: |' '          .github/scripts/check-profiles.sh' \
        '      - name: Jars' '        run: .github/scripts/check-jars.sh' \
        '      - name: Diff only' "        if: github.event_name == 'pull_request'" '        run: echo diff' \
        '  validate-k8s:' '    name: Validate K8s' '    runs-on: ubuntu-latest' '    steps:' \
        '      - name: Environments' '        run: ruby .github/scripts/check-envs.rb /tmp'
    put .github/workflows/factory-guards.yml 'name: Factory guards' 'on: [push, pull_request]' 'jobs:' \
        '  factory-guards:' '    name: Factory guards' '    runs-on: ubuntu-latest' '    steps:' \
        '      - name: Hook tests' '        run: .claude/hooks/test-hooks.sh' \
        '      - name: Changes that need the owner' "        if: github.event_name != 'push'" \
        '        run: |' '          .github/scripts/check-factory-guards.sh "$BASE_SHA" "$HEAD_SHA"'
    local f
    for f in .github/scripts/check-profiles.sh .github/scripts/check-jars.sh .github/scripts/check-envs.rb \
             .github/scripts/check-factory-guards.sh .claude/hooks/test-hooks.sh .claude/workflows/seed-bugs.js \
             shared/src/test/java/x/DenyByDefaultTest.java; do
        put "$f" '# a check'
    done
}

# MSG=<regex> case_ <expect: pass|fail> <name> <setup command...>
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
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ] && grep -qE "${MSG:-hold\.}" <<<"$out"; } \
       || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -q 'backlog #0-184' <<<"$out" \
            && grep -qE "$MSG" <<<"$out"; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

as_is() { :; }

echo "check-rule-claims.rb:"
MSG="7 label\(s\)" case_ pass "the layout as it is (every label read, the retired pattern and rule skipped)" as_is
case_ pass "a measure phrase in factory.md" edit .ai/rules/review/factory.md '$a\
- **FAC-A09 The benchmark.** A seeded run, P-23, patterns.md.'
case_ pass "what a label's parentheses name is no check of its own" \
    edit .ai/rules/review/security.md 's/(routes not listed/(`GoneTest` and `gone.sh` explain it; routes not listed/'
case_ pass "a backticked name that is no test or guard is what the check checks" \
    edit .ai/rules/review/security.md 's/`DenyByDefaultTest`/`DenyByDefaultTest`, which names `WebFetch` and `Service`/'
case_ pass "an indented block after the rules is no rule's text" edit .ai/rules/review/k8s.md '$a\
\
    Enforced by: `gone.sh`'

# Claim 1: labels.
MSG="ARC-40: .Enforced by. names check-profiles.sh, which does not exist" \
    case_ fail "Enforced by a script that does not exist" del .github/scripts/check-profiles.sh
MSG="SEC-33: .Backed by. names check-jars.sh, which does not exist" \
    case_ fail "Backed by a script that does not exist" del .github/scripts/check-jars.sh
MSG="SEC-10: .Backed by. names DenyByDefaultTest, and no DenyByDefaultTest.java exists" \
    case_ fail "Backed by a test class that does not exist" del shared/src/test/java/x/DenyByDefaultTest.java
MSG="K8S-20: .Enforced by. check-envs.rb runs only where .* job .Validate K8s." \
    case_ fail "Enforced by a script in a job that is no required check" \
    edit .github/repository-settings/rulesets/main.json 's/{"context": "Validate K8s"}, //'
MSG="ARC-40: .Enforced by. check-profiles.sh runs only where .*step .No profile ships." \
    case_ fail "Enforced by a script in a step that runs on push only" \
    edit .github/workflows/ci.yml "s/      - name: No profile ships/&\\
        if: github.event_name == 'push'/"
MSG="ARC-40: .Enforced by. check-profiles.sh runs only where" \
    case_ fail "Enforced by a script in a job that runs on push only" \
    edit .github/workflows/ci.yml "s/^    if: .*/    if: github.event_name == 'push'/"
MSG="K8S-20: .Enforced by. check-envs.rb runs only where" \
    case_ fail "Enforced by a script of a workflow that does not run on a pull request" \
    edit .github/workflows/ci.yml 's/^  pull_request:$/  workflow_dispatch:/'
MSG="FAC-A02: .Enforced by. check-factory-guards.sh runs only where .*step .Changes that need the owner." \
    case_ fail "Enforced by the script of the owner's step" \
    edit .ai/rules/review/factory.md 's/Backed by: `check-factory-guards.sh`/Enforced by: `check-factory-guards.sh`/'
MSG="K8S-20: .Enforced by. no workflow step runs check-envs.rb" \
    case_ fail "Enforced by a script no step runs" \
    edit .github/workflows/ci.yml 's/check-envs.rb/check-other.rb/'
for cond in 'false' '${{ false }}'; do
    MSG="ARC-40: .Enforced by. check-profiles.sh runs only where" \
        case_ fail "Enforced by a script in a step whose if: is $cond" \
        edit .github/workflows/ci.yml "s/      - name: No profile ships/&\\
        if: $cond/"
done
for coe in 'true' '${{ inputs.lenient }}'; do
    MSG="ARC-40: .Enforced by. check-profiles.sh runs only where .*continuing on error" \
        case_ fail "Enforced by a script in a step with continue-on-error: $coe" \
        edit .github/workflows/ci.yml "s/      - name: No profile ships/&\\
        continue-on-error: $coe/"
done
MSG="K8S-03: .Enforced by. names the job validate-k8s, which is no required check" \
    case_ fail "Enforced by a job that continues on error" \
    edit .github/workflows/ci.yml 's/    name: Validate K8s/&\
    continue-on-error: true/'
case_ pass "a step with continue-on-error: false still decides" \
    edit .github/workflows/ci.yml 's/      - name: No profile ships/&\
        continue-on-error: false/'
for run in '# .github/scripts/check-profiles.sh' 'echo .github/scripts/check-profiles.sh' \
           '.github/scripts/check-profiles.sh || true' '.github/scripts/check-profiles.sh || :' \
           'true # .github/scripts/check-profiles.sh'; do
    MSG="ARC-40: .Enforced by. no workflow step runs check-profiles.sh" \
        case_ fail "Enforced by a script a line only names: $run" \
        edit .github/workflows/ci.yml "s@^          .github/scripts/check-profiles.sh\$@          $run@"
done
case_ pass "a comment beside the line that runs it" \
    edit .github/workflows/ci.yml 's|^          .github/scripts/check-profiles.sh$|          # the profiles\
          .github/scripts/check-profiles.sh \&\& echo done|'
MSG="K8S-03: .Enforced by. names the job validate-k8s, which is no required check" \
    case_ fail "Enforced by a job that is no required check" \
    edit .github/repository-settings/rulesets/main.json 's/{"context": "Validate K8s"}, //'
MSG="K8S-03: .Enforced by. names no script and no CI job id" \
    case_ fail "Enforced by plain words" \
    edit .ai/rules/review/k8s.md 's/Enforced by: validate-k8s/Enforced by: the render job/'

# Claim 2: the measure's words.
for phrase in 'a benchmark run' 'a seeded result' 'the seeder' '/seed-bugs' 'a seed branch' 'patterns.md' 'P-06'; do
    MSG="architecture.md,line=3::names the seeded-defect measure" \
        case_ fail "a rule file names the measure: $phrase" edit .ai/rules/review/architecture.md "3s|Text.|Text, $phrase.|"
done
MSG="_common.md,line=3::names the seeded-defect measure" \
    case_ fail "_common.md names the measure" edit .ai/rules/review/_common.md '3s/labels/labels (a Benchmark)/'

# Claim 3: the patterns' rule ids.
MSG="P-06 cites ARC-20, which .ai/rules/review/architecture.md retired" \
    case_ fail "a pattern cites a retired id" edit .ai/audit/benchmark/patterns.md 's/| ARC-22 |/| ARC-20 |/'
MSG="P-06 cites ARC-99, which .ai/rules/review/architecture.md does not define" \
    case_ fail "a pattern cites an id its file does not define" edit .ai/audit/benchmark/patterns.md 's/| ARC-22 |/| ARC-99 |/'
MSG="P-05 cites ARC-22, which .ai/rules/review/security.md does not define" \
    case_ fail "a pattern cites an id of another dimension's file" edit .ai/audit/benchmark/patterns.md 's/| SEC-10 |/| ARC-22 |/'
MSG="P-23 is security's, and FAC-A02 is no rule of security" \
    case_ fail "an FAC-A id under security" edit .ai/audit/benchmark/patterns.md 's/| P-23 | architecture |/| P-23 | security |/'
MSG="P-05 is security's, and FAC-X01 is no rule of security" \
    case_ fail "an FAC- id of neither half" edit .ai/audit/benchmark/patterns.md 's/| SEC-10 |/| FAC-X01 |/'
MSG="P-05 cites \"sec 10\", which is no rule id" \
    case_ fail "a rule cell that is no id" edit .ai/audit/benchmark/patterns.md 's/| SEC-10 |/| sec 10 |/'
MSG="P-14 cites MIG-02, which .ai/rules/review/migration.md does not define" \
    case_ fail "a pattern no longer retired is read" edit .ai/audit/benchmark/patterns.md 's/| retired 2026-10-26/| plant it/'

# Inputs it cannot read fail it.
MSG="holds no pattern row" case_ fail "a catalogue with no row" put .ai/audit/benchmark/patterns.md '# Patterns'
MSG="cannot read .ai/audit/benchmark/patterns.md" case_ fail "no catalogue" del .ai/audit/benchmark/patterns.md
MSG="rulesets/main.json is not JSON" case_ fail "a ruleset that is not JSON" put .github/repository-settings/rulesets/main.json '{'
MSG="lists no required status check" case_ fail "a ruleset with no required check" \
    put .github/repository-settings/rulesets/main.json '{"rules": []}'
MSG="ci.yml cannot be read as YAML" case_ fail "a workflow that is not YAML" put .github/workflows/ci.yml 'jobs: [:'
MSG="no rule file in .ai/rules/review" case_ fail "no rule file" del .ai/rules/review

if [ "$failures" -gt 0 ]; then
    echo "$failures of $n case(s) failed."
    exit 1
fi
echo "All $n check-rule-claims cases passed."
