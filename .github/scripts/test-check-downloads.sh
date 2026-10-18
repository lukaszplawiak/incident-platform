#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-downloads.rb (backlog #0-149): one temporary directory per case, holding a
# workflow that installs a tool through install-pinned-tool.sh and a composite action, which the case then
# breaks (or extends), and the checker run on it. Every failing case names the message it must give, so a
# case cannot pass on another rule's refusal.
#
# Run by the lint-workflows job in .github/workflows/ci.yml, before the checker; locally:
#   .github/scripts/test-check-downloads.sh
# ============================================================
# The cases write workflows as literal text: `$` in single quotes is meant (backlog #0-149).
# shellcheck disable=SC2016
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
CHECKER=$HERE/check-downloads.rb
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

# step <run line...>: a workflow whose one job runs the given lines in a `run: |` block (from line 8).
step() {
    local lines=() l
    for l in "$@"; do lines+=("          $l"); done
    put .github/workflows/w.yml 'name: W' 'on: push' 'jobs:' '  build:' '    runs-on: ubuntu-latest' '    steps:' \
        '      - name: Get the tool' '        run: |' "${lines[@]}"
}

# The case's layout: the pinned install, and a composite action with a step of its own.
layout() {
    put .github/workflows/ci.yml 'name: CI' 'on: push' 'jobs:' '  lint:' '    runs-on: ubuntu-latest' '    steps:' \
        '      - name: Install' '        run: |' \
        '          .github/scripts/install-pinned-tool.sh --repo rhysd/actionlint --tag "$V" --asset "$A" --sha256 "$S" --into "$T"' \
        '      - run: echo "curling stones, libcurl, a .curlrc, CURL_FAILED and wgetrc are words, not downloads"'
    put .github/actions/act/action.yml 'name: Act' 'description: d' 'runs:' '  using: composite' '  steps:' \
        '    - name: Month' '      shell: bash' '      run: echo "month=$(date -u +%Y-%m)" >> "$GITHUB_OUTPUT"'
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
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ] && grep -qE "${MSG:-no download outside}" <<<"$out"; } \
       || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -qE "backlog #0-149" <<<"$out" \
            && grep -qE "$MSG" <<<"$out"; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

as_is() { :; }
marked() {
    step 'status=$(curl -s "http://localhost:8080/health")  # not-a-download: a health probe of a local service'
}
yaml_comment() {
    put .github/workflows/w.yml 'name: W' 'on: push' 'jobs:' '  build:' '    runs-on: ubuntu-latest' '    steps:' \
        '      # curl is stubbed in this step' '      - run: echo ok'
}
curl_tar() { step 'set -euo pipefail' 'curl -sSL "https://example.com/tool.tgz" | tar xz'; }
wget_tool() { step 'wget -q https://example.com/tool'; }
release_url() { step 'python3 -c "import urllib.request as u; u.urlretrieve(\"https://github.com/o/r/releases/download/v1/t\")"'; }
gh_release() { step 'gh  release   download v1 -R o/r -p tool'; }
in_action() {
    put .github/actions/act/action.yml 'name: Act' 'description: d' 'runs:' '  using: composite' '  steps:' \
        '    - name: Fetch' '      shell: bash' '      run: |' '        curl -sSLo tool https://example.com/tool'
}
bare_marker() { step 'curl -sSLo tool https://example.com/tool  # not-a-download:'; }
marker_elsewhere() { step '# not-a-download: the next line' 'curl -sSLo tool https://example.com/tool'; }
shell_comment() { step '# then curl the archive'; }
one_line() {
    put .github/workflows/w.yml 'name: W' 'on: push' 'jobs:' '  build:' '    runs-on: ubuntu-latest' '    steps:' \
        '      - name: One' '        run: curl -o t https://example.com/t'
}
folded() {
    put .github/workflows/w.yml 'name: W' 'on: push' 'jobs:' '  build:' '    runs-on: ubuntu-latest' '    steps:' \
        '      - name: Folded' '        run: >' '          echo start' '' '          curl -o t https://example.com/t'
}
odd_names() {   # a step name with a newline and a file name with a comma: one annotation line, escaped
    put '.github/workflows/a,b.yml' 'name: W' 'on: push' 'jobs:' '  build:' '    runs-on: ubuntu-latest' '    steps:' \
        '      - name: "Get\nthe tool"' '        run: curl -o t https://example.com/t'
}
yaml_extension() { curl_tar; mv "$dir/.github/workflows/w.yml" "$dir/.github/workflows/w.yaml"; }
broken_yaml() { put .github/workflows/broken.yml 'jobs: [unclosed'; }
no_workflows() { rm -rf "$dir/.github/workflows"; }

echo "check-downloads: what passes"
MSG="Checked 3 run blocks in 1 workflows and 1 actions" case_ pass "the pinned install, and words that only contain curl or wget" as_is
case_ pass "a curl that says why it is not a download" marked
case_ pass "a YAML comment is not a run block" yaml_comment

echo "check-downloads: what fails"
MSG="file=\.github/workflows/w\.yml,line=10::step 'Get the tool' runs .curl." case_ fail "curl | tar in a workflow" curl_tar
MSG="w\.yml,line=9::step 'Get the tool' runs .wget." case_ fail "wget" wget_tool
MSG="line=9::.* runs .releases/download/." case_ fail "a release asset's URL, by another program" release_url
MSG="line=9::.* runs .gh  release   download." case_ fail "gh release download" gh_release
MSG="file=\.github/actions/act/action\.yml,line=9::step 'Fetch' runs .curl." case_ fail "curl in a composite action" in_action
MSG="line=9::.* runs .curl." case_ fail "a marker without its reason" bare_marker
MSG="line=10::.* runs .curl." case_ fail "a marker on another line" marker_elsewhere
MSG="line=9::.* runs .curl." case_ fail "a shell comment naming curl" shell_comment
MSG="line=8::step 'One' runs .curl." case_ fail "a one-line run" one_line
MSG="line=9::step 'Folded' runs .curl." case_ fail "a folded run, at its first line" folded
MSG="w\.yaml,line=10::.* runs .curl." case_ fail "a .yaml workflow" yaml_extension
MSG="^::error file=\.github/workflows/a%2Cb\.yml,line=8::step 'Get%0Athe tool' runs .curl." case_ fail "a newline in a step name and a comma in a path are escaped" odd_names
MSG="broken\.yml cannot be read as YAML" case_ fail "a workflow that is not YAML" broken_yaml
MSG="no workflow found" case_ fail "no workflows (not run from the root)" no_workflows

if [ "$failures" -gt 0 ]; then
    echo "::error::$failures of $n cases failed (backlog #0-149)"
    exit 1
fi
echo "All $n cases passed."
