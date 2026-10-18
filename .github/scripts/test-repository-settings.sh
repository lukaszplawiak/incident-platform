#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/repository-settings.rb (backlog #0-113), offline: gh is a stub on PATH that serves
# GitHub's rulesets and repository settings from a directory of the case (live/), and applies the writes
# `apply` sends to it, so a case can check what GitHub would hold afterwards. One temporary directory per
# case, holding a workflow, two rulesets and the repository settings, which the case then breaks. Every
# case names the message it must give, so a case cannot pass on another rule's refusal.
#
# Run by the lint-workflows job in .github/workflows/ci.yml, before the check; locally:
#   .github/scripts/test-repository-settings.sh
# ============================================================
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT=$HERE/repository-settings.rb
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0
dir=""

# gh stub: `api [--method M] [--input -] <path>` against $GH_STUB_LIVE (rulesets/<id>.json, repo.json, admins.json); every
# call is logged as "<METHOD> <path>" in its calls file. GH_STUB_HIDE=bypass,repo hides what only an admin
# sees (setting: one repository setting, from an admin's token too); GH_STUB_FAIL=<text> fails a call whose path contains it; GH_STUB_NOT_LIST=1 answers the list with an object.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env ruby
require 'json'
live = ENV.fetch('GH_STUB_LIVE')
hide = ENV['GH_STUB_HIDE'].to_s.split(',')
args = ARGV.dup
abort("gh stub: unexpected #{args.join(' ')}") unless args.shift == 'api'
method = 'GET'
input = nil
path = nil
until args.empty?
  a = args.shift
  case a
  when '--method' then method = args.shift
  when '--input' then args.shift; input = JSON.parse($stdin.read)
  else path = a
  end
end
File.open(File.join(live, 'calls'), 'a') { |f| f.puts("#{method} #{path}") }
fail_on = ENV['GH_STUB_FAIL'].to_s
abort('HTTP 502: Bad Gateway') if !fail_on.empty? && path.include?(fail_on)
dir = File.join(live, 'rulesets')
file = ->(id) { File.join(dir, "#{id}.json") }
ids = Dir.glob(File.join(dir, '*.json')).map { |f| File.basename(f, '.json').to_i }.sort
case [method, path]
when ['GET', 'repos/o/r/rulesets?per_page=100']
  (puts '{}'; exit) if ENV['GH_STUB_NOT_LIST']
  puts JSON.generate(ids.map { |id| r = JSON.parse(File.read(file[id])); { 'id' => id, 'name' => r['name'], 'target' => r['target'] } })
when ['GET', 'repos/o/r/collaborators?permission=admin&affiliation=all&per_page=100']
  puts File.read(File.join(live, 'admins.json'))
when ['GET', 'repos/o/r']
  repo = JSON.parse(File.read(File.join(live, 'repo.json')))
  repo = repo.reject { |k, _| k == 'delete_branch_on_merge' } if hide.include?('setting')
  puts JSON.generate(hide.include?('repo') ? { 'full_name' => 'o/r', 'permissions' => { 'admin' => false } } : repo)
when ['POST', 'repos/o/r/rulesets']
  id = (ids.max || 99) + 1
  File.write(file[id], JSON.generate(input.merge('id' => id)))
  puts JSON.generate(input.merge('id' => id))
when ['PATCH', 'repos/o/r']
  repo = JSON.parse(File.read(File.join(live, 'repo.json'))).merge(input)
  File.write(File.join(live, 'repo.json'), JSON.generate(repo))
  puts JSON.generate(repo)
else
  id = path[%r{\Arepos/o/r/rulesets/(\d+)\z}, 1]
  abort("gh stub: unexpected #{method} #{path}") unless id
  abort('HTTP 404: Not Found') unless File.exist?(file[id])
  case method
  when 'GET'
    r = JSON.parse(File.read(file[id]))
    r.delete('bypass_actors') if hide.include?('bypass')
    puts JSON.generate(r)
  when 'PUT'
    File.write(file[id], JSON.generate(input.merge('id' => id.to_i)))
    puts JSON.generate(input)
  when 'DELETE'
    File.delete(file[id])
  end
end
STUB
chmod +x "$WORK/bin/gh"
export PATH="$WORK/bin:$PATH"

# put <file> <line...>: writes the lines into the case's file.
put() {
    local file=$1; shift
    mkdir -p "$(dirname "$dir/$file")"
    printf '%s\n' "$@" > "$dir/$file"
}

# rb <ruby>: runs Ruby in the case's directory (to write or change JSON).
rb() { (cd "$dir" && ruby -rjson -e "$1"); }

# The case's layout: a workflow with two jobs on every pull request, ruleset `checks` requiring both and
# `review` with an admin bypass, the repository settings; and GitHub holding the same (live/), with the
# fields GitHub adds and its arrays in another order.
layout() {
    put .github/workflows/ci.yml 'name: CI' 'on:' '  pull_request:' '    branches: [ main ]' 'permissions: read-all' 'jobs:' \
        '  build:' '    name: Build' '    runs-on: ubuntu-latest' '    steps:' '      - run: echo build' \
        '  lint:' '    runs-on: ubuntu-latest' '    steps:' '      - run: echo lint'
    put .github/workflows/release.yml 'name: Release' 'on: push' 'permissions: read-all' 'jobs:' \
        '  publish:' '    runs-on: ubuntu-latest' '    steps:' '      - run: echo publish'
    rb '
      cond = { "ref_name" => { "include" => ["refs/heads/main"], "exclude" => [] } }
      checks = { "name" => "checks", "target" => "branch", "enforcement" => "active", "conditions" => cond,
                 "bypass_actors" => [],
                 "rules" => [{ "type" => "deletion" },
                             { "type" => "required_status_checks", "parameters" => {
                                 "strict_required_status_checks_policy" => false,
                                 "required_status_checks" => [{ "context" => "Build", "integration_id" => 15368 },
                                                              { "context" => "lint", "integration_id" => 15368 }] } }] }
      review = { "name" => "review", "target" => "branch", "enforcement" => "active", "conditions" => cond,
                 "bypass_actors" => [{ "actor_id" => 5, "actor_type" => "RepositoryRole", "bypass_mode" => "pull_request" }],
                 "rules" => [{ "type" => "pull_request", "parameters" => {
                                 "required_approving_review_count" => 0, "require_code_owner_review" => true,
                                 "allowed_merge_methods" => ["merge", "squash"] } }] }
      Dir.mkdir(".github/repository-settings") rescue nil
      Dir.mkdir(".github/repository-settings/rulesets") rescue nil
      Dir.mkdir("live") rescue nil
      Dir.mkdir("live/rulesets") rescue nil
      File.write(".github/repository-settings/rulesets/checks.json", JSON.pretty_generate(checks))
      File.write(".github/repository-settings/rulesets/review.json", JSON.pretty_generate(review))
      File.write(".github/repository-settings/repository.json",
                 JSON.generate("settings" => { "delete_branch_on_merge" => true }, "admins" => ["owner"]))
      extra = { "source_type" => "Repository", "source" => "o/r", "node_id" => "RRS_x",
                "current_user_can_bypass" => "never", "_links" => {}, "created_at" => "2026-10-18T00:00:00Z" }
      [[11, checks], [12, review]].each do |id, r|
        r = Marshal.load(Marshal.dump(r)).merge(extra).merge("id" => id)
        r["rules"].reverse!
        r["rules"].each { |x| (x["parameters"] || {}).each_value { |v| v.reverse! if v.is_a?(Array) } }
        File.write("live/rulesets/#{id}.json", JSON.generate(r))
      end
      File.write("live/repo.json", JSON.generate("full_name" => "o/r", "permissions" => { "admin" => true },
                                                "delete_branch_on_merge" => true, "allow_auto_merge" => false))
      File.write("live/admins.json", JSON.generate([{ "login" => "owner" }]))
    '
}

# live <id> <ruby on r>: changes GitHub's copy of ruleset <id>.
live() { rb "f = \"live/rulesets/$1.json\"; r = JSON.parse(File.read(f)); $2; File.write(f, JSON.generate(r))"; }
# spec <name> <ruby on r>: changes the file of ruleset <name>.
spec() { rb "f = \".github/repository-settings/rulesets/$1.json\"; r = JSON.parse(File.read(f)); $2; File.write(f, JSON.generate(r))"; }
# The parameters of a ruleset's required_status_checks and pull_request rule, as Ruby on r (for live and spec).
P_CHECKS='r["rules"].find { |x| x["type"] == "required_status_checks" }["parameters"]'
P_REVIEW='r["rules"].find { |x| x["type"] == "pull_request" }["parameters"]'

# [MSG=<regex>] [CALLS=<regex the calls must match>] [NOCALLS=<regex they must not>] [MODE=check|plan|apply]
# [ARGS=<more arguments, split on spaces>]
# (the output and the calls are matched as one line, each of their lines ended by ';')
# case_ <expect: pass|fail> <name> <setup command...>
case_() {
    local expect=$1 name=$2; shift 2
    dir="$WORK/case$((n += 1))"
    mkdir -p "$dir"
    layout
    "$@"
    : > "$dir/live/calls"
    local out rc
    set +e
    local extra=()
    read -ra extra <<<"${ARGS:-}"
    out=$(cd "$dir" && GH_STUB_LIVE="$dir/live" ruby "$SCRIPT" "${MODE:-check}" --repo o/r ${extra[@]+"${extra[@]}"} 2>&1)
    rc=$?
    set -e
    # Output and calls on one line each, lines ended by ';', so a pattern can span lines.
    local calls ok=1
    out=$(tr '\n' ';' <<<"$out"); out=${out%;}
    calls=$(tr '\n' ';' < "$dir/live/calls")
    if [ "$expect" = pass ]; then
        [ "$rc" -eq 0 ] && grep -qE -- "${MSG:-match \.github/repository-settings/}" <<<"$out" || ok=0
    else
        [ "$rc" -eq 1 ] && grep -qE "backlog #0-113" <<<"$out" && grep -qE -- "$MSG" <<<"$out" || ok=0
    fi
    if [ -n "${CALLS:-}" ] && ! grep -qE "$CALLS" <<<"$calls"; then ok=0; fi
    if [ -n "${NOCALLS:-}" ] && grep -qE "$NOCALLS" <<<"$calls"; then ok=0; fi
    if [ "$ok" = 1 ]; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"$'\n'"calls: $calls"
        failures=$((failures + 1))
    fi
}

as_is() { :; }
hidden() { export GH_STUB_HIDE=bypass,repo; }
setting_hidden() { export GH_STUB_HIDE=setting; }
gh_fails() { export GH_STUB_FAIL=rulesets/12; }
check_dropped() { live 11 "${P_CHECKS}[\"required_status_checks\"].pop"; }
no_code_owners() { live 12 "${P_REVIEW}[\"require_code_owner_review\"] = false"; }
evaluate() { live 11 'r["enforcement"] = "evaluate"'; }
bot_bypass() { live 11 'r["bypass_actors"] << { "actor_id" => 4, "actor_type" => "RepositoryRole", "bypass_mode" => "always" }'; }
github_param() { live 12 "${P_REVIEW}[\"required_reviewers\"] = []"; }
ui_ruleset() { live 11 'r["name"] = "Protect main"'; rb 'File.write("live/rulesets/13.json", File.read("live/rulesets/11.json").sub("Protect main", "checks").sub("\"id\":11", "\"id\":13"))'; }
not_applied() { rm "$dir/live/rulesets/12.json"; }
auto_delete_off() { rb 'r = JSON.parse(File.read("live/repo.json")); r["delete_branch_on_merge"] = false; File.write("live/repo.json", JSON.generate(r))'; }
renamed_job() { sed -i.bak 's/name: Build/name: Build and test/' "$dir/.github/workflows/ci.yml"; }
push_only() { spec checks "${P_CHECKS}[\"required_status_checks\"] << { \"context\" => \"publish\", \"integration_id\" => 15368 }"; }
path_filter() { sed -i.bak 's/    branches: \[ main \]/    paths: [ "src\/**" ]/' "$dir/.github/workflows/ci.yml"; }
other_branch() { sed -i.bak 's/    branches: \[ main \]/    branches: [ release ]/' "$dir/.github/workflows/ci.yml"; }
checks_write() { put .github/workflows/report.yml 'name: Report' 'on: push' 'permissions:' '  checks: write' 'jobs:' '  r:' '    runs-on: ubuntu-latest' '    steps:' '      - run: echo'; }
statuses_job() { put .github/workflows/report.yml 'name: Report' 'on: push' 'jobs:' '  r:' '    runs-on: ubuntu-latest' '    permissions: { statuses: write }' '    steps:' '      - run: echo'; }
write_all() { put .github/workflows/report.yml 'name: Report' 'on: push' 'permissions: write-all' 'jobs:' '  r:' '    runs-on: ubuntu-latest' '    steps:' '      - run: echo'; }
no_permissions() { put .github/workflows/release.yml 'name: Release' 'on: push' 'jobs:' '  publish:' '    runs-on: ubuntu-latest' '    steps:' '      - run: echo publish'; }
job_permissions() { put .github/workflows/release.yml 'name: Release' 'on: push' 'jobs:' '  publish:' '    runs-on: ubuntu-latest' '    permissions: { contents: read }' '    steps:' '      - run: echo publish'; }
unrequired_job() { printf '%s\n' '  e2e:' '    name: End to end' '    runs-on: ubuntu-latest' '    steps:' '      - run: echo e2e' >> "$dir/.github/workflows/ci.yml"; }
some_types() { sed -i.bak 's/    branches: \[ main \]/    types: [ opened, labeled ]/' "$dir/.github/workflows/ci.yml"; }
all_types() { sed -i.bak 's/    branches: \[ main \]/    types: [ opened, synchronize, reopened, labeled ]/' "$dir/.github/workflows/ci.yml"; }
push_twin() { put .github/workflows/release.yml 'name: Release' 'on: push' 'permissions: read-all' 'jobs:' '  lint:' '    runs-on: ubuntu-latest' '    steps:' '      - run: echo'; }
other_admin() { rb 'File.write("live/admins.json", JSON.generate([{ "login" => "owner" }, { "login" => "someone" }]))'; }
other_admin_before() { before; other_admin; }
extra_key() { put .github/repository-settings/repository.json '{ "settings": { "delete_branch_on_merge": true }, "admins": ["owner"], "teams": [] }'; }
bad_repository() { put .github/repository-settings/repository.json '{ "delete_branch_on_merge": true }'; }
# --main: main is the first commit (the layout), the pull request a second one. commit <message> commits the case.
commit() { git -C "$dir" add -A .; git -C "$dir" -c user.name=t -c user.email=t@example.org commit -qm "$1"; }
main_first() { git -C "$dir" init -q; commit main; }
# The pull request changes something else; the working tree then makes the checks strict, which GitHub's are not,
# so only main's files (HEAD^1) match.
pr_other() { main_first; echo pr > "$dir/README.md"; commit pr; spec checks "${P_CHECKS}[\"strict_required_status_checks_policy\"] = true"; }
# The pull request itself makes them strict: it is checked with its own files, which GitHub does not hold yet.
pr_settings() { main_first; spec checks "${P_CHECKS}[\"strict_required_status_checks_policy\"] = true"; commit pr; }
one_commit() { main_first; }
dup_rule() { spec checks 'r["rules"] << { "type" => "deletion" }'; }
untyped_rule() { spec checks 'r["rules"] << { "parameters" => {} }'; }
many_rulesets() { rb '(13..112).each { |i| File.write("live/rulesets/#{i}.json", JSON.generate("id" => i, "name" => "r#{i}", "rules" => [])) }'; }
same_live_name() { rb 'File.write("live/rulesets/13.json", File.read("live/rulesets/11.json").sub("\"id\":11", "\"id\":13"))'; }
not_a_list() { export GH_STUB_NOT_LIST=1; }
two_jobs() { put .github/workflows/other.yml 'name: Other' 'on: pull_request' 'permissions: read-all' 'jobs:' '  lint:' '    runs-on: ubuntu-latest' '    steps:' '      - run: echo'; }
no_app() { spec checks "${P_CHECKS}[\"required_status_checks\"][0].delete(\"integration_id\")"; }
no_bypass_key() { spec checks 'r.delete("bypass_actors")'; }
same_name() { rb 'File.write(".github/repository-settings/rulesets/copy.json", File.read(".github/repository-settings/rulesets/checks.json"))'; }
not_json() { put .github/repository-settings/rulesets/broken.json '{ "name": '; }
no_rulesets() { rm -rf "$dir/.github/repository-settings"; }
# For plan and apply: GitHub as #0-113 found it, one ruleset made in the UI, auto-delete off.
before() {
    rm "$dir/live/rulesets/"*.json
    rb 'File.write("live/rulesets/7.json", JSON.generate("id" => 7, "name" => "Protect main", "target" => "branch",
          "enforcement" => "active", "bypass_actors" => [], "conditions" => {}, "rules" => [{ "type" => "deletion" }]))'
    auto_delete_off
}
before_hidden() { before; hidden; }

echo "repository-settings: check, what passes"
MSG="2 ruleset\(s\), 1 setting\(s\) and the admins match \.github/repository-settings/ on o/r\.$" \
    case_ pass "GitHub holds the files (its own fields ignored, arrays in another order)" as_is
MSG="not visible to this token, so not compared: the bypass actors of 'checks', the bypass actors of 'review', the repository setting delete_branch_on_merge, the repository's admins" \
    case_ pass "a token that is not an admin's: bypass actors and settings named as not compared" hidden
unset GH_STUB_HIDE
MSG="not visible to this token, so not compared: the repository setting delete_branch_on_merge\)" \
    case_ pass "an admin's token that does not see a setting names it" setting_hidden
unset GH_STUB_HIDE
MSG="match \.github/repository-settings/ on o/r\.$" case_ pass "a pull_request workflow whose types include every new push" all_types
case_ pass "a workflow without permissions whose every job has its own" job_permissions
MSG="match \.github/repository-settings/ \(main's files at HEAD\^1: this commit does not change them\) on o/r\.$" \
    ARGS="--main HEAD^1" case_ pass "--main, a pull request that does not change the files: main's" pr_other

echo "repository-settings: check, what fails"
MSG="ruleset 'checks' differs from GitHub's: rules\.required_status_checks\.required_status_checks: the file says" \
    case_ fail "a required check removed in the UI" check_dropped
MSG="review\.json::ruleset 'review' differs from GitHub's: rules\.pull_request\.require_code_owner_review: the file says true, GitHub false" \
    case_ fail "code owner review turned off in the UI" no_code_owners
MSG="enforcement: the file says \"active\", GitHub \"evaluate\"" case_ fail "a ruleset set to evaluate only" evaluate
MSG="bypass_actors: the file says \[\], GitHub \[\{" case_ fail "a bypass actor added in the UI (admin token)" bot_bypass
MSG="rules\.pull_request\.required_reviewers: on GitHub \(\[\]\), not in the file" case_ fail "a parameter only GitHub has" github_param
MSG="ruleset 'Protect main' \(id 11\) is on GitHub but in no file" case_ fail "a ruleset made in the UI" ui_ruleset
MSG="review\.json::ruleset 'review' is not on GitHub: apply the file" case_ fail "a file never applied" not_applied
MSG="repository\.json::delete_branch_on_merge: the file says true, GitHub false" case_ fail "a repository setting changed in the UI" auto_delete_off
MSG="gh api repos/o/r/rulesets/12 failed: HTTP 502" case_ fail "GitHub cannot be read" gh_fails
unset GH_STUB_FAIL
MSG="required check 'Build' is the name of no job in a workflow that runs on every pull request" \
    NOCALLS=. case_ fail "a required check whose job was renamed (before asking GitHub)" renamed_job
MSG="required check 'publish' is the name of no job" case_ fail "a required check of a push-only workflow" push_only
MSG="required check 'Build' is the name of no job" case_ fail "a required check of a workflow with a path filter" path_filter
MSG="required check 'Build' is the name of no job" case_ fail "a required check of a workflow for another branch" other_branch
MSG="required check 'Build' is the name of no job" case_ fail "a required check of a workflow limited to some activity types" some_types
MSG="file=\.github/workflows/ci\.yml::\.github/workflows/ci\.yml: job 'End to end' runs on every pull request to main but is no required check" \
    NOCALLS=. case_ fail "a job of every pull request that no ruleset requires" unrequired_job
MSG="report\.yml: the workflow gives its token checks or statuses write" NOCALLS=. case_ fail "a workflow whose token may write checks" checks_write
MSG="release\.yml: job 'publish' has no permissions: and neither has the workflow" NOCALLS=. case_ fail "a workflow and job without permissions" no_permissions
MSG="report\.yml: job 'r' gives its token checks or statuses write" case_ fail "a job whose token may write statuses" statuses_job
MSG="report\.yml: the workflow gives its token checks or statuses write" case_ fail "a workflow with write-all" write_all
MSG="required check 'lint' is the name of a job in \.github/workflows/ci\.yml and \.github/workflows/release\.yml: a run of either" \
    case_ fail "a required check that is also the name of a job in a push workflow" push_twin
MSG="repository\.json::the admins of o/r are owner, someone; the file says owner" case_ fail "an admin the file does not name" other_admin
MSG="repository\.json must be \{\"settings\"" case_ fail "repository.json in another form" bad_repository
MSG="repository\.json must be \{\"settings\"" case_ fail "repository.json with a key it does not know" extra_key
MSG="ruleset 'checks' differs from GitHub's: rules\.required_status_checks\.strict_required_status_checks_policy" case_ fail "without --main, the working tree's files" pr_other
MSG="ruleset 'checks' differs from GitHub's: rules\.required_status_checks\.strict_required_status_checks_policy" ARGS="--main HEAD^1" \
    case_ fail "--main, a pull request that changes the files: its own" pr_settings
MSG="git diff HEAD\^1 HEAD failed \(--main needs the checked-out commit's parent" ARGS="--main HEAD" \
    case_ fail "--main without the commit's parent (a shallow checkout)" one_commit
MSG="git ls-tree --name-only nope -- \.github/repository-settings/rulesets/ failed" ARGS="--main nope" case_ fail "--main naming no commit" pr_other
MSG="--main is for check only" MODE=plan ARGS="--main HEAD" case_ fail "--main with plan" as_is
MSG="checks\.json: every rule must be an object with a type, each type once" case_ fail "a rule type twice" dup_rule
MSG="checks\.json: every rule must be an object with a type, each type once" case_ fail "a rule without a type" untyped_rule
MSG="100 rulesets or more: this script reads one page" case_ fail "a hundred rulesets on GitHub" many_rulesets
MSG="two rulesets on GitHub are named 'checks' \(ids 11 and 13\)" case_ fail "two rulesets on GitHub with one name" same_live_name
MSG="gh api repos/o/r/rulesets: not a list" case_ fail "GitHub's list is not a list" not_a_list
unset GH_STUB_NOT_LIST
MSG="required check 'lint' is the name of a job in \.github/workflows/ci\.yml and \.github/workflows/other\.yml" \
    case_ fail "a required check two workflows report" two_jobs
MSG="required check 'Build' must name integration_id 15368" case_ fail "a required check from any app" no_app
MSG="checks\.json must have exactly the keys name, target, enforcement, conditions, bypass_actors, rules" \
    case_ fail "a ruleset file without bypass_actors" no_bypass_key
MSG="the ruleset name 'checks' is also" case_ fail "two files with one name" same_name
MSG="broken\.json cannot be read as JSON" case_ fail "a file that is not JSON" not_json
MSG="no ruleset file under \.github/repository-settings/rulesets" case_ fail "no ruleset files (not run from the root)" no_rulesets

echo "repository-settings: plan and apply"
MSG="^would: create ruleset 'checks';would: create ruleset 'review';would: delete ruleset 'Protect main' \(id 7\): no file names it;would: set delete_branch_on_merge=true$" \
    MODE=plan NOCALLS='(^|;)(POST|PUT|PATCH|DELETE) ' case_ pass "plan lists the changes, creates before deletes, and writes nothing" before
MSG="Nothing to change" MODE=apply NOCALLS='(^|;)(POST|PUT|PATCH|DELETE) ' case_ pass "apply when GitHub holds the files writes nothing" as_is
MSG="apply: update ruleset 'review' \(id 12\).*2 ruleset\(s\), 1 setting\(s\) and the admins match" MODE=apply CALLS='PUT repos/o/r/rulesets/12' \
    case_ pass "apply updates a ruleset changed in the UI, in place" no_code_owners
MSG="apply: delete ruleset 'Protect main'.*match \.github/repository-settings/ on o/r\.$" MODE=apply \
    CALLS=';POST repos/o/r/rulesets;POST repos/o/r/rulesets;DELETE repos/o/r/rulesets/7;PATCH repos/o/r;GET ' \
    case_ pass "apply creates, then deletes, then sets, then checks" before
MSG="plan and apply need a token with admin rights on o/r" MODE=apply \
    NOCALLS='(^|;)(POST|PUT|PATCH|DELETE) ' case_ fail "apply without an admin token changes nothing" before_hidden
unset GH_STUB_HIDE
MSG="this token does not see the repository setting delete_branch_on_merge: plan and apply need" MODE=plan \
    NOCALLS='(^|;)(POST|PUT|PATCH|DELETE) ' case_ fail "plan with a setting the admin's token does not see" setting_hidden
unset GH_STUB_HIDE
MSG="the admins of o/r are owner, someone; the file says owner: .*; apply does not change that" MODE=apply \
    NOCALLS='(^|;)(POST|PUT|PATCH|DELETE) ' case_ fail "apply with an admin the file does not name changes nothing" other_admin_before

if [ "$failures" -gt 0 ]; then
    echo "::error::$failures of $n cases failed (backlog #0-113)"
    exit 1
fi
echo "All $n cases passed."
