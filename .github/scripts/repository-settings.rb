# ============================================================
# The GitHub settings that guard `main`, kept in this repository (backlog #0-113, ADR-0034).
#
# Until #0-113 they lived only in GitHub's UI, invisible in any diff: one ruleset forbade deleting and
# force-pushing `main`, and nothing required a pull request, a green check or the owner's review, so the
# autopilot's machine account (Write) could push to `main` directly and `gh pr merge --auto` would merge at
# once. The settings are now files under .github/repository-settings/ (an owner-only path, CODEOWNERS):
#
#   rulesets/<name>.json   one repository ruleset each, the request body of GitHub's rulesets API
#                          (name, target, enforcement, conditions, bypass_actors, rules), every
#                          parameter written out, `bypass_actors` too when it is empty
#   repository.json        `settings`: repository settings (`delete_branch_on_merge`, `allow_auto_merge`);
#                          `admins`: the logins with the admin role, the only ones the ruleset's bypass (the
#                          admin role) reaches, so a new admin would bypass it too
#
# and this script is the one way between them and GitHub:
#
#   check   (CI: lint-workflows, every pull request and push to main) fails when GitHub's settings and the
#           files differ, in either direction: a ruleset or a parameter changed, added or removed in the UI
#           and not in a file, or a file not applied. Also fails, before asking GitHub anything, on a
#           required check that no job of a workflow running on every pull request to main reports (a
#           renamed job would leave every pull request waiting forever) and on one without its app's
#           `integration_id` (then a status anyone with Write sets through the statuses API satisfies it), and
#           on a required check that is the name of more than one job in any workflow (a run of the other, a
#           push or a manual dispatch on the pull request's branch, reports a check of that name on the same
#           commit), and the other way, on a job of a workflow running on every pull request that no ruleset
#           requires (a red one would not stop a merge, and a required job that needs it reports "skipped",
#           which passes), and on a workflow or job whose token may write checks or statuses (its run could
#           report a required check in the name of GitHub Actions) or that declares none at all (its token is then
#           the repository default, set in the UI). With --main <commit> (CI, on a pull request: main, fetched) it reads the files from that
#           commit, unless the checked-out commit changes them against its first parent (on a pull request,
#           GitHub's merge commit, whose first parent is main): so applying another pull request's change
#           does not leave every open pull request comparing GitHub with its own older copy (review of #0-113).
#   plan    prints what `apply` would change, and changes nothing.
#   apply   makes GitHub match the files: creates and updates the rulesets first and deletes the ones no
#           file names last, so `main` is never without its rules in between; then the repository
#           settings; then `check`, which must pass.
#
# What `check` cannot see with CI's token: a ruleset's `bypass_actors` (GitHub shows them only to an admin,
# so an actor added in the UI goes unnoticed in CI), the repository settings (not in the read-only
# repository response) and the admins (the collaborators list needs more than read). It says so in its
# summary; run by the owner (an admin token), it compares all three, and `plan`/`apply` refuse to run
# without them. `apply` never changes who is an admin: that is the owner's decision, in Settings. Arrays
# are compared as sets (their order means nothing to GitHub). A parameter GitHub adds later fails `check`
# until it is written in its file.
#
# Usage: ruby repository-settings.rb check|plan|apply [--repo <owner/name>] [--root <repository root>]
#        [--main <commit>] (check only)
# (repo: $GITHUB_REPOSITORY, else `gh repo view`). Needs gh with a token (GH_TOKEN in CI); apply needs the
# owner's (admin). Portable to Ruby 2.6.
# ============================================================
require 'json'
require 'open3'
require 'psych'
require_relative 'run-blocks'

USAGE = 'usage: repository-settings.rb check|plan|apply [--repo <owner/name>] [--root <repository root>] [--main <commit>]'
RULESET_KEYS = %w[name target enforcement conditions bypass_actors rules].freeze
# The app every required check must come from: GitHub Actions (the app that runs this repository's workflows).
GITHUB_ACTIONS_APP = 15368
# A workflow limited to some pull_request activity types must include these, or a new push reports nothing.
PR_TYPES = %w[opened synchronize reopened].freeze

$errors = 0

def fail!(msg, file: nil)
  puts Annotation.error("#{msg} (backlog #0-113)", file: file)
  $errors += 1
end

def stop!(msg)
  puts Annotation.error("#{msg} (backlog #0-113)")
  exit 1
end

mode = ARGV.shift
stop!(USAGE) unless %w[check plan apply].include?(mode)
opts = {}
until ARGV.empty?
  flag = ARGV.shift
  stop!(USAGE) unless %w[--repo --root --main].include?(flag) && ARGV.first
  opts[flag] = ARGV.shift
end
stop!('--main is for check only: plan and apply apply the working tree') if opts['--main'] && mode != 'check'
ROOT = File.expand_path(opts['--root'] || '.')
DIR = '.github/repository-settings'

# gh api <args>; the parsed JSON body, or nil for an empty one (a DELETE). A failed call ends the run: a
# check that cannot read GitHub has not checked.
def gh(*args, input: nil)
  out, err, status = Open3.capture3('gh', 'api', *args, stdin_data: input.to_s)
  stop!("gh api #{args.join(' ')} failed: #{err.lines.first.to_s.strip}") unless status.success?
  out.strip.empty? ? nil : JSON.parse(out)
rescue SystemCallError => e
  stop!("cannot run gh: #{e.message}")
rescue JSON::ParserError
  stop!("gh api #{args.join(' ')} printed no JSON: #{out.to_s.lines.first.to_s.strip}")
end

def repo_name(opts)
  return opts['--repo'] if opts['--repo']
  return ENV['GITHUB_REPOSITORY'] unless ENV['GITHUB_REPOSITORY'].to_s.empty?
  out, err, status = Open3.capture3('gh', 'repo', 'view', '--json', 'nameWithOwner', '--jq', '.nameWithOwner')
  stop!("cannot tell the repository (--repo, $GITHUB_REPOSITORY or gh repo view): #{err.lines.first.to_s.strip}") unless status.success?
  out.strip
rescue SystemCallError => e
  stop!("cannot run gh: #{e.message}")
end

def rel(path)
  path.sub(%r{\A#{Regexp.escape(ROOT)}/?}, '')
end

# --- the files ---------------------------------------------------------------------------------------

def git(*args)
  out, err, status = Open3.capture3('git', '-C', ROOT, *args)
  stop!("git #{args.join(' ')} failed: #{err.lines.first.to_s.strip}") unless status.success?
  out
rescue SystemCallError => e
  stop!("cannot run git: #{e.message}")
end

# The commit whose files are compared (main's, given by --main), or nil for the working tree: nil when the checked-out
# commit changes the files against its first parent, so a pull request that changes them is checked with its own.
def files_ref(main)
  return nil unless main
  _out, err, status = Open3.capture3('git', '-C', ROOT, 'diff', '--quiet', 'HEAD^1', 'HEAD', '--', DIR)
  return main if status.exitstatus == 0
  return nil if status.exitstatus == 1
  stop!("git diff HEAD^1 HEAD failed (--main needs the checked-out commit's parent: fetch-depth 2): #{err.lines.first.to_s.strip}")
rescue SystemCallError => e
  stop!("cannot run git: #{e.message}")
end

# The ruleset files (paths from the root), from the working tree (ref nil) or from that commit.
def ruleset_files(ref)
  dir = "#{DIR}/rulesets"
  if ref
    # Without -r: the files directly in it, as in the working tree.
    git('ls-tree', '--name-only', ref, '--', "#{dir}/").lines.map(&:strip).select { |f| f.end_with?('.json') }.sort
  else
    Dir.glob(File.join(ROOT, dir, '*.json')).sort.map { |f| rel(f) }
  end
end

def load_json(file, ref)
  JSON.parse(ref ? git('show', "#{ref}:#{file}") : File.read(File.join(ROOT, file)))
rescue SystemCallError, JSON::ParserError => e
  fail!("#{file} cannot be read as JSON: #{e.message.lines.first.to_s.strip}", file: file)
  nil
end

def load_rulesets(ref)
  files = ruleset_files(ref)
  stop!("no ruleset file under #{DIR}/rulesets#{ref ? " at #{ref}" : ''}: run from the repository root") if files.empty?
  specs = {}
  files.each do |file|
    spec = load_json(file, ref)
    next unless spec
    unless spec.is_a?(Hash) && (RULESET_KEYS - spec.keys).empty? && (spec.keys - RULESET_KEYS).empty?
      fail!("#{file} must have exactly the keys #{RULESET_KEYS.join(', ')} (bypass_actors too when it is " \
            'empty: no bypass is a decision, written down)', file: file)
      next
    end
    unless spec['name'].is_a?(String) && spec['rules'].is_a?(Array) && spec['bypass_actors'].is_a?(Array)
      fail!("#{file}: name must be a string, rules and bypass_actors arrays", file: file)
      next
    end
    types = spec['rules'].map { |r| r.is_a?(Hash) ? r['type'] : nil }
    if types.include?(nil) || types.uniq.size != types.size
      fail!("#{file}: every rule must be an object with a type, each type once", file: file)
      next
    end
    if specs.key?(spec['name'])
      fail!("#{file}: the ruleset name '#{spec['name']}' is also #{specs[spec['name']][:file]}", file: file)
      next
    end
    specs[spec['name']] = { file: file, spec: spec }
  end
  specs
end

# {settings: {name => boolean}, admins: [login]}.
def load_repository(ref)
  file = "#{DIR}/repository.json"
  repo = load_json(file, ref)
  return { 'settings' => {}, 'admins' => [] } unless repo
  settings = repo.is_a?(Hash) ? repo['settings'] : nil
  admins = repo.is_a?(Hash) ? repo['admins'] : nil
  unless repo.is_a?(Hash) && repo.keys.sort == %w[admins settings] &&
         settings.is_a?(Hash) && !settings.empty? && settings.values.all? { |v| [true, false].include?(v) } &&
         admins.is_a?(Array) && !admins.empty? && admins.all? { |a| a.is_a?(String) && !a.empty? }
    fail!("#{file} must be {\"settings\": {<name>: true|false, ...}, \"admins\": [<login>, ...]}", file: file)
    return { 'settings' => {}, 'admins' => [] }
  end
  repo
end

# --- every required check is reported on every pull request, and every such job is required ------------

# The names of the jobs of every workflow, each with its workflow files: [jobs of the workflows that run on
# every pull request to main, jobs of every workflow whatever its trigger].
def workflow_jobs
  jobs = Hash.new { |h, k| h[k] = [] }
  every = Hash.new { |h, k| h[k] = [] }
  RunBlocks.workflows(ROOT).each do |path|
    doc = begin
      Psych.safe_load(File.read(path), aliases: true)
    rescue StandardError => e
      fail!("#{rel(path)} cannot be read as YAML: #{e.message.lines.first.to_s.strip}", file: rel(path))
      next
    end
    next unless doc.is_a?(Hash)
    names = (doc['jobs'] || {}).map { |key, job| job.is_a?(Hash) && job['name'] ? job['name'].to_s : key.to_s }
    names.each { |name| every[name] << rel(path) }
    # A token with checks: write or statuses: write, in a run on any branch, reports a check or a status in the
    # name of GitHub Actions, which the integration_id of a required check accepts (security review of #0-113).
    scopes = [['the workflow', doc['permissions']]] +
             (doc['jobs'] || {}).map { |key, job| ["job '#{key}'", job.is_a?(Hash) ? job['permissions'] : nil] }
    # A job with no permissions: of its own, in a workflow with none, gets the repository's default token, which
    # only the UI sets: every workflow declares its scope (backlog #0-59), and so the check above sees it.
    if doc['permissions'].nil?
      scopes.drop(1).select { |_, perms| perms.nil? }.each do |where, _|
        fail!("#{rel(path)}: #{where} has no permissions: and neither has the workflow, so its token is the " \
              "repository's default, set in the UI and seen by no check (backlog #0-59, ADR-0034)", file: rel(path))
      end
    end
    scopes.each do |where, perms|
      next unless perms == 'write-all' || (perms.is_a?(Hash) && (perms['checks'] == 'write' || perms['statuses'] == 'write'))
      fail!("#{rel(path)}: #{where} gives its token checks or statuses write: its run could report a required " \
            "check in the name of GitHub Actions (integration_id #{GITHUB_ACTIONS_APP}) without the job behind " \
            'it (ADR-0034)', file: rel(path))
    end
    # YAML 1.1 reads the key `on` as true.
    on = doc.key?('on') ? doc['on'] : doc[true]
    triggers = on.is_a?(Hash) ? on : Array(on).map { |t| [t, nil] }.to_h
    next unless triggers.key?('pull_request')
    filter = triggers['pull_request'] || {}
    next unless filter.is_a?(Hash)
    # A workflow that skips some pull requests (by path, by a branch filter without main, or by activity types
    # without a new push) reports nothing on them: a check it reports cannot be required.
    next if (filter.keys & %w[paths paths-ignore branches-ignore]).any?
    next if filter.key?('branches') && !Array(filter['branches']).include?('main')
    next if filter.key?('types') && !(PR_TYPES - Array(filter['types'])).empty?
    names.each { |name| jobs[name] << rel(path) }
  end
  [jobs, every]
end

def check_required_checks(specs)
  jobs, every = workflow_jobs
  specs.each_value do |s|
    rule = s[:spec]['rules'].find { |r| r['type'] == 'required_status_checks' }
    next unless rule
    Array((rule['parameters'] || {})['required_status_checks']).each do |c|
      context = c['context']
      unless c['integration_id'] == GITHUB_ACTIONS_APP
        fail!("#{s[:file]}: required check '#{context}' must name integration_id #{GITHUB_ACTIONS_APP} " \
              "(GitHub Actions): without it a commit status of that name, which anyone with Write can set " \
              'through the statuses API, satisfies the check', file: s[:file])
      end
      found = jobs[context]
      if found.empty?
        fail!("#{s[:file]}: required check '#{context}' is the name of no job in a workflow that runs on every " \
              'pull request to main: no pull request could ever merge (a renamed job, or a path filter)',
              file: s[:file])
      elsif every[context].size > 1
        fail!("#{s[:file]}: required check '#{context}' is the name of a job in #{every[context].join(' and ')}: " \
              'a run of either (a push, a manual dispatch on the pull request\'s branch) reports a check of that ' \
              'name on the same commit, and one that passes could satisfy it', file: s[:file])
      end
    end
  end
  # And the other way (review of #0-113): every job that runs on every pull request is required. One that is not
  # only advises, and a required job that `needs:` it reports "skipped" when it fails, which a required check accepts.
  required = specs.values.flat_map do |s|
    rule = s[:spec]['rules'].find { |r| r['type'] == 'required_status_checks' }
    rule ? Array((rule['parameters'] || {})['required_status_checks']).map { |c| c['context'] } : []
  end
  (jobs.keys - required).sort.each do |name|
    jobs[name].uniq.each do |file|
      fail!("#{file}: job '#{name}' runs on every pull request to main but is no required check of a ruleset under " \
            "#{DIR}/rulesets: a red run would not stop a merge, and a required job that needs it would report " \
            'skipped and pass (ADR-0034)', file: file)
    end
  end
end

# --- the comparison ----------------------------------------------------------------------------------

# Hashes with sorted keys, arrays sorted: the order of an array means nothing to GitHub here.
def canonical(value)
  case value
  when Hash then value.keys.sort.map { |k| [k, canonical(value[k])] }.to_h
  when Array then value.map { |v| canonical(v) }.sort_by { |v| JSON.generate(v) }
  else value
  end
end

# The rules as {type => parameters}, so a difference is named by its rule.
def by_type(rules)
  Array(rules).map { |r| [r['type'], r['parameters'] || {}] }.to_h
end

def diff(want, got, path, out)
  if want.is_a?(Hash) && got.is_a?(Hash)
    (want.keys | got.keys).sort.each do |k|
      if !got.key?(k)
        out << "#{path}#{k}: in the file, not on GitHub"
      elsif !want.key?(k)
        out << "#{path}#{k}: on GitHub (#{JSON.generate(got[k])}), not in the file"
      else
        diff(want[k], got[k], "#{path}#{k}.", out)
      end
    end
  elsif want != got
    out << "#{path.chomp('.')}: the file says #{JSON.generate(want)}, GitHub #{JSON.generate(got)}"
  end
end

# The differences between a ruleset's file and GitHub's copy, and whether its bypass actors were seen.
def ruleset_diff(spec, live)
  out = []
  keys = %w[target enforcement conditions]
  visible = live.key?('bypass_actors')
  keys << 'bypass_actors' if visible
  want = keys.map { |k| [k, canonical(spec[k])] }.to_h.merge('rules' => canonical(by_type(spec['rules'])))
  got = keys.map { |k| [k, canonical(live[k])] }.to_h.merge('rules' => canonical(by_type(live['rules'])))
  diff(want, got, '', out)
  [out, visible]
end

# GitHub's state against the files: what differs, what this token could not see, and the plan to fix it,
# in the order to apply it: every ruleset a file names is created or updated before any other is deleted, so
# `main` keeps a ruleset at every moment; the repository settings last.
def compare(repo, specs, repository)
  settings = repository['settings']
  listed = gh("repos/#{repo}/rulesets?per_page=100")
  stop!("gh api repos/#{repo}/rulesets: not a list") unless listed.is_a?(Array)
  stop!('100 rulesets or more: this script reads one page') if listed.size >= 100
  live = {}
  listed.each do |r|
    stop!("two rulesets on GitHub are named '#{r['name']}' (ids #{live[r['name']]['id']} and #{r['id']})") if live.key?(r['name'])
    live[r['name']] = r
  end
  result = { findings: [], hidden: [], actions: [] }
  specs.each do |name, s|
    if !live.key?(name)
      result[:findings] << [s[:file], "ruleset '#{name}' is not on GitHub: apply the file (repository-settings.rb apply)"]
      result[:actions] << [:create, name, s[:spec]]
      next
    end
    out, visible = ruleset_diff(s[:spec], gh("repos/#{repo}/rulesets/#{live[name]['id']}"))
    result[:hidden] << "the bypass actors of '#{name}'" unless visible
    next if out.empty?
    out.each { |d| result[:findings] << [s[:file], "ruleset '#{name}' differs from GitHub's: #{d}"] }
    result[:actions] << [:update, name, s[:spec], live[name]['id']]
  end
  (live.keys - specs.keys).sort.each do |name|
    result[:findings] << [nil, "ruleset '#{name}' (id #{live[name]['id']}) is on GitHub but in no file under " \
                               "#{DIR}/rulesets/: changed outside a diff (write it as a file, or apply)"]
    result[:actions] << [:delete, name, nil, live[name]['id']]
  end
  current = gh("repos/#{repo}")
  result[:admin] = (current['permissions'] || {})['admin'] == true
  patch = {}
  settings.each do |key, value|
    if !current.key?(key)
      result[:hidden] << "the repository setting #{key}"
    elsif current[key] != value
      result[:findings] << ["#{DIR}/repository.json", "#{key}: the file says #{value}, GitHub #{current[key]}"]
      patch[key] = value
    end
  end
  result[:actions] << [:patch, nil, patch] unless patch.empty?
  if result[:admin]
    admins = gh("repos/#{repo}/collaborators?permission=admin&affiliation=all&per_page=100")
    stop!("gh api repos/#{repo}/collaborators: not a list") unless admins.is_a?(Array)
    logins = admins.map { |a| a['login'] }.sort
    unless logins == repository['admins'].sort
      result[:admins] = "the admins of #{repo} are #{logins.join(', ')}; the file says " \
                        "#{repository['admins'].sort.join(', ')}: an admin bypasses the ruleset at merge (ADR-0034), " \
                        'so who is one is decided in Settings, Collaborators, then written in the file'
      result[:findings] << ["#{DIR}/repository.json", result[:admins]]
    end
  else
    result[:hidden] << "the repository's admins"
  end
  result
end

# --- the modes ----------------------------------------------------------------------------------------

# main: the --main given, ref: the commit whose files were read (nil: the working tree), for the summary.
def check(repo, specs, repository, main: nil, ref: nil)
  result = compare(repo, specs, repository)
  result[:findings].each { |file, msg| fail!(msg, file: file) }
  exit 1 if $errors > 0
  note = result[:hidden].empty? ? '' : " (not visible to this token, so not compared: #{result[:hidden].join(', ')})"
  source = if !main then ''
           elsif ref then " (main's files at #{ref}: this commit does not change them)"
           else " (this commit's files: it changes them)"
           end
  puts "Repository settings: #{specs.size} ruleset(s), #{repository['settings'].size} setting(s) and the admins " \
       "match #{DIR}/#{source} on #{repo}#{note}."
end

def describe(action)
  kind, name, body, id = action
  case kind
  when :create then "create ruleset '#{name}'"
  when :update then "update ruleset '#{name}' (id #{id})"
  when :delete then "delete ruleset '#{name}' (id #{id}): no file names it"
  when :patch then "set #{body.map { |k, v| "#{k}=#{v}" }.join(', ')}"
  end
end

def plan(repo, specs, repository, apply:)
  result = compare(repo, specs, repository)
  stop!("plan and apply need a token with admin rights on #{repo} (the owner's)") unless result[:admin]
  unless result[:hidden].empty?
    stop!("this token does not see #{result[:hidden].join(', ')}: plan and apply need the owner's (admin) " \
          'token, or a bypass actor could be changed without being shown')
  end
  stop!("#{result[:admins]}; apply does not change that") if result[:admins]
  actions = result[:actions]
  if actions.empty?
    puts "Nothing to change: GitHub's settings on #{repo} match #{DIR}/."
    return
  end
  actions.each do |a|
    puts "#{apply ? 'apply' : 'would'}: #{describe(a)}"
    next unless apply
    kind, _name, body, id = a
    case kind
    when :create then gh('--method', 'POST', "repos/#{repo}/rulesets", '--input', '-', input: JSON.generate(body))
    when :update then gh('--method', 'PUT', "repos/#{repo}/rulesets/#{id}", '--input', '-', input: JSON.generate(body))
    when :delete then gh('--method', 'DELETE', "repos/#{repo}/rulesets/#{id}")
    when :patch then gh('--method', 'PATCH', "repos/#{repo}", '--input', '-', input: JSON.generate(body))
    end
  end
  check(repo, specs, repository) if apply
end

ref = files_ref(opts['--main'])
specs = load_rulesets(ref)
repository = load_repository(ref)
check_required_checks(specs)
exit 1 if $errors > 0
repo = repo_name(opts)
case mode
when 'check' then check(repo, specs, repository, main: opts['--main'], ref: ref)
when 'plan' then plan(repo, specs, repository, apply: false)
when 'apply' then plan(repo, specs, repository, apply: true)
end
