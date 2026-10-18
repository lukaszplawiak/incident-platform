#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-shell.rb (backlog #0-149): one temporary git repository per case, holding a
# clean script and a composite action, which the case then breaks (or extends), and the checker run on it
# with the real shellcheck. Every failing case names the message it must give, so a case cannot pass on
# another rule's refusal.
#
# Needs the shellcheck the checker runs: SHELLCHECK=<path> (CI: lint-workflows, the pinned one). Locally:
#   SHELLCHECK=<path to shellcheck> .github/scripts/test-check-shell.sh
# ============================================================
# The cases write scripts as literal text: `$` and backticks in single quotes are meant (backlog #0-149).
# shellcheck disable=SC2016
set -euo pipefail

SHELLCHECK=${SHELLCHECK:?SHELLCHECK must name the shellcheck binary the checker runs}
HERE=$(cd "$(dirname "$0")" && pwd)
CHECKER=$HERE/check-shell.rb
ACTION=.github/actions/act/action.yml
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0
dir=""

# put <file> <line...>: writes the lines into the case's file and tracks it.
put() {
    local file=$1; shift
    mkdir -p "$(dirname "$dir/$file")"
    printf '%s\n' "$@" > "$dir/$file"
    git -C "$dir" add -- "$file"
}

# The case's layout: a clean script, and a composite action whose step uses an expression (which must not
# reach shellcheck as `${{`).
layout() {
    git -C "$dir" init -q
    put scripts/ok.sh '#!/usr/bin/env bash' 'set -euo pipefail' 'echo "ok: $*"'
    put "$ACTION" 'name: Act' 'description: A composite action' 'inputs:' '  who:' '    description: who' \
        'runs:' '  using: composite' '  steps:' '    - name: Greet' '      shell: bash' \
        '      run: |' '        name="${{ inputs.who }}"' '        echo "hello $name"'
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
    out=$(ruby "$CHECKER" "${CHECK_WITH:-$SHELLCHECK}" "$dir" 2>&1)
    rc=$?
    set -e
    # A failure must be the checker's own verdict, with the message of the rule the case breaks.
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ] && grep -qE "${MSG:-no finding}" <<<"$out"; } \
       || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -qE "backlog #0-149" <<<"$out" \
            && grep -qE "$MSG" <<<"$out"; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

as_is() { :; }
disabled_with_reason() {
    put scripts/split.sh '#!/usr/bin/env bash' 'flags="-a -b"' '# Two flags, split on purpose.' \
        '# shellcheck disable=SC2086' 'ls $flags'
}
untracked() { printf '#!/usr/bin/env bash\nls $1\n' > "$dir/scripts/draft.sh"; }
sourced_library() {
    put scripts/lib.sh '#!/usr/bin/env bash' 'greet() { echo "hi $1"; }'
    put scripts/main.sh '#!/usr/bin/env bash' '. "$(dirname "$0")/lib.sh"' 'greet you'
}
bash_library() {   # a library that is not a .sh file, so not on shellcheck's command line: only -x follows it
    put scripts/lib.bash '# shellcheck shell=bash' 'greet() { echo "hi $1"; }'
    put scripts/main.sh '#!/usr/bin/env bash' '. "$(dirname "$0")/lib.bash"' 'greet you'
}
pwsh_step() {
    put .github/actions/win/action.yml 'name: Win' 'description: d' 'runs:' '  using: composite' '  steps:' \
        '    - shell: pwsh' '      run: Write-Output $env:X'
}
node_action() {
    put .github/actions/node/action.yml 'name: Node' 'description: d' 'runs:' '  using: node20' '  main: index.js' \
        '  steps:' '    - shell: bash' '      run: ls $NEVER_RUN'
}
note() { put scripts/note.sh '#!/usr/bin/env bash' 'ls $1'; }
style() { put scripts/style.sh '#!/usr/bin/env bash' 'now=`date`' 'echo "$now"'; }
rc_root() { note; printf 'disable=SC2086\n' > "$dir/.shellcheckrc"; git -C "$dir" add .shellcheckrc; }
rc_beside() { note; printf 'disable=SC2086\n' > "$dir/scripts/.shellcheckrc"; git -C "$dir" add scripts/.shellcheckrc; }
no_shebang() { put scripts/bare.sh 'echo "no shell named"'; }
step_finding() {
    put "$ACTION" 'name: Act' 'description: A composite action' 'runs:' '  using: composite' '  steps:' \
        '    - name: Copy' '      shell: bash' '      run: |' '        set -euo pipefail' '        cp $SRC "$DST"'
}
step_plain() {
    put "$ACTION" 'name: Act' 'description: A composite action' 'runs:' '  using: composite' '  steps:' \
        '    - name: One line' '      shell: sh' '      run: rm -rf $TARGET'
}
step_quoted() {
    put "$ACTION" 'name: Act' 'description: A composite action' 'runs:' '  using: composite' '  steps:' \
        '    - name: Quoted' '      shell: sh' '      run: "rm -rf $TARGET"'
}
step_folded() {
    put "$ACTION" 'name: Act' 'description: A composite action' 'runs:' '  using: composite' '  steps:' \
        '    - name: Folded' '      shell: bash' '      run: >' '        echo start' '' '        ls $DIR'
}
step_custom_shell() {
    put "$ACTION" 'name: Act' 'description: A composite action' 'runs:' '  using: composite' '  steps:' \
        '    - name: Custom' '      shell: bash -e {0}' '      run: ls $DIR'
}
odd_names() {   # a script name with a comma and a step name with a newline: one annotation line each, escaped
    put 'scripts/a,b.sh' '#!/usr/bin/env bash' 'ls $1'
    put "$ACTION" 'name: Act' 'description: A composite action' 'runs:' '  using: composite' '  steps:' \
        '    - name: "Copy\nall"' '      shell: bash' '      run: cp $SRC "$DST"'
}
step_no_shell() {
    put "$ACTION" 'name: Act' 'description: A composite action' 'runs:' '  using: composite' '  steps:' \
        '    - name: Shell-less' '      run: echo hi'
}
broken_action() { put .github/actions/bad/action.yml 'runs: [unclosed'; }
no_scripts() { git -C "$dir" rm -q --cached scripts/ok.sh; }
not_a_repo() { rm -rf "$dir/.git"; }
missing_shellcheck() { CHECK_WITH="$dir/no-such-shellcheck"; }
failing_shellcheck() {
    printf '#!/bin/sh\necho "bad option" >&2\nexit 3\n' > "$dir/sc"; chmod +x "$dir/sc"; CHECK_WITH="$dir/sc"
}
silent_shellcheck() { printf '#!/bin/sh\necho "not json"\n' > "$dir/sc"; chmod +x "$dir/sc"; CHECK_WITH="$dir/sc"; }

echo "check-shell: what passes"
MSG="1 script\(s\) and 1 composite action step\(s\), no finding" case_ pass "a clean script and a step with an expression" as_is
case_ pass "a finding disabled where it is, with its reason" disabled_with_reason
case_ pass "a script git does not track" untracked
case_ pass "a library a script sources is followed (no SC1091)" sourced_library
case_ pass "a library that is not a .sh file is followed too (-x)" bash_library
MSG="1 in another shell not checked" case_ pass "a step in pwsh is skipped, and counted" pwsh_step
case_ pass "the steps of an action that is not composite never run, so are not read" node_action

echo "check-shell: what fails"
MSG="file=scripts/note\.sh,line=2,col=4::SC2086 \(info\)" case_ fail "a note (SC2086) counts" note
MSG="file=scripts/style\.sh,line=2,col=5::SC2006 \(style\)" case_ fail "a style finding (SC2006) counts" style
MSG="scripts/note\.sh,line=2.*SC2086" case_ fail "a .shellcheckrc at the root turns nothing off" rc_root
MSG="scripts/note\.sh,line=2.*SC2086" case_ fail "a .shellcheckrc beside the script turns nothing off" rc_beside
MSG="scripts/bare\.sh,line=1.*SC2148" case_ fail "a script that names no shell" no_shebang
MSG="file=\.github/actions/act/action\.yml,line=10,col=12::step 'Copy': SC2086" case_ fail "a finding in a step, at its line in action.yml" step_finding
MSG="file=\.github/actions/act/action\.yml,line=8,col=19::step 'One line': SC2086" case_ fail "a finding in a one-line sh step" step_plain
MSG="file=\.github/actions/act/action\.yml,line=8,col=20::step 'Quoted': SC2086" case_ fail "a finding in a quoted one-line step, past its quote" step_quoted
MSG="file=\.github/actions/act/action\.yml,line=9::step 'Folded': SC2086" case_ fail "a finding in a folded step, at its first line" step_folded
MSG="action\.yml,line=8.*step 'Custom': SC2086" case_ fail "a step in bash -e {0}" step_custom_shell
MSG="step 'Shell-less' has no shell" case_ fail "a composite step with no shell" step_no_shell
MSG="^::error file=scripts/a%2Cb\.sh,line=2,col=4::SC2086" case_ fail "a comma in a script's path is escaped" odd_names
MSG="^::error file=\.github/actions/act/action\.yml,line=8,col=[0-9]+::step 'Copy%0Aall': SC2086" case_ fail "a newline in a step name is escaped" odd_names
MSG="bad/action\.yml cannot be read as YAML" case_ fail "an action that is not YAML" broken_action
MSG="no tracked \.sh file" case_ fail "no script tracked (not run from the root)" no_scripts
MSG="git ls-files failed" case_ fail "not a git repository" not_a_repo
MSG="cannot run shellcheck" case_ fail "no shellcheck at that path" missing_shellcheck
CHECK_WITH=""
MSG="shellcheck exited 3: bad option" case_ fail "a shellcheck that refuses its options" failing_shellcheck
CHECK_WITH=""
MSG="shellcheck printed no JSON report" case_ fail "a shellcheck that prints no report" silent_shellcheck
CHECK_WITH=""

if [ "$failures" -gt 0 ]; then
    echo "::error::$failures of $n cases failed (backlog #0-149)"
    exit 1
fi
echo "All $n cases passed."
