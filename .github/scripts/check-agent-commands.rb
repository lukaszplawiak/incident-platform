# ============================================================
# Every command form an autopilot agent's definition prescribes runs in an autopilot session (backlog #0-207 AC4).
#
# An agent in `dontAsk` (`.claude/settings.autopilot.json`) gets only the Bash commands an allow rule matches and its
# hooks pass; a refused call costs a turn and is not retried (two pickers lost six and seven calls on 2026-11-04, one
# of them its item). So each definition below names its commands in a marked list, with example values:
#   <!-- commands:start -->
#   - `git grep -n -A12 '^### 0-25\.' <sha> -- BACKLOG.md`
#   <!-- commands:end -->
# and this check takes every listed command and shows, for each agent the list is for:
#   1. it is none of the forms Claude Code refuses whatever an allow rule says, or that the hooks refuse: a separator,
#      pipe, redirect or substitution outside quotes, an unquoted glob, a `..` path, an absolute or `~` path,
#      `git -C`, `cd`, a recursive `cp`. A text match of a rule is not enough: run 20261106-203806 was refused
#      `cp -r .ai/work/_template .ai/work/0-197` although `Bash(cp -r .ai/work/_template *)` matched it as text, as
#      Claude Code does not let a rule run a recursive `cp` (#0-207's third case). These families are what such a
#      check cannot see, so the definitions do not use them;
#   2. an allow rule of `Bash(...)` matches it and no deny rule does, the rules of both settings files, as the autopilot
#      session merges them (`claude --settings .claude/settings.autopilot.json` on top of .claude/settings.json;
#      Claude Code's form: `*` any text, a trailing `:*` a prefix);
#   3. every PreToolUse hook that would see the call exits 0: the session-level Bash entries of both settings files
#      (the `--agent` twins included, with the call's agent_type) and the Bash entries of that agent's own
#      frontmatter, run in a throwaway repository whose `.ai/runs/LOCK` records the branch the list pushes
#      (`fix/0-25-notification-queue-version`), as a run's lock does.
#   4. no allow rule lets an agent run a program that can remove or move a path it does not name (backlog #0-235,
#      ADR-0054): every `Bash(...)` allow rule of both settings files, and of the `allowed-tools` of every skill an
#      autopilot agent preloads (`skills:`), starts with a prefix of SAFE_PREFIXES, or is in EXEMPT_RULES with its
#      reason. A closed list, not a list of dangerous commands: a program nobody has judged (`rsync --delete`,
#      `python3 -c`, `git clean`, `env rm`, a bare `Bash`) fails until it is. The runtime state the factory trusts
#      (.ai/runs/LOCK, state.json, the factory-ops marks, .ai/STOP) is gitignored, so no diff shows its removal, and
#      guard-protected-bash.sh only sees a command that names it: this list is what keeps `rm -rf .ai/runs` out.
#      An exemption whose rule is no longer allowed is stale and fails.
# Which agents: every definition whose `tools:` holds Bash (none named: every tool) has a list, follows another's
# (ALSO_FOR), or is in EXEMPT with its reason; an exemption that names no such agent, or one that has a list after all,
# is stale and fails, as check-hook-entries.rb's UNGUARDED (backlog #0-186). A list that names no command fails.
# Decided in ADR-0050.
#
# What it does not see: Claude Code's own permission check beyond the families above (only a session in dontAsk
# shows it, by hand: #0-207's AC1), and whether the prose of a definition asks for a command its list leaves out
# (a reviewer's to judge: the list must hold every form the definition names).
# Usage: ruby check-agent-commands.rb [<repository root>] (Factory guards, after its tests, run-agent-command-tests.sh).
# Runs on the Ruby of .ruby-version (ADR-0040).
# ============================================================
require 'fileutils'
require 'json'
require 'open3'
require 'tmpdir'
require 'yaml'

ROOT = File.expand_path(ARGV[0] || '.')
AGENTS_DIR = File.join(ROOT, '.claude', 'agents')
SETTINGS = %w[settings.json settings.autopilot.json].map { |f| File.join(ROOT, '.claude', f) }.freeze

# The list of a definition applies to the agents named here too (implementer-light reads implementer.md and follows
# it exactly); each is checked with the hooks of its own frontmatter.
ALSO_FOR = { 'implementer' => %w[implementer-light] }.freeze
# The agents with Bash that keep no list, each with the reason: a guard of their own already fixes the forms they may
# run, and test-hooks.sh tests that guard (ADR-0050).
READ_ONLY = 'its Bash is readonly-bash.sh, one read-only git command per call (no pipe, chain or redirect), tested by test-hooks.sh'
EXEMPT = {
  'acceptance-reviewer' => READ_ONLY, 'planner' => READ_ONLY, 'ready-checker' => READ_ONLY,
  'review-architecture' => READ_ONLY, 'review-docs' => READ_ONLY, 'review-general' => READ_ONLY,
  'review-k8s' => READ_ONLY, 'review-migration' => READ_ONLY, 'review-performance' => READ_ONLY,
  'review-security' => READ_ONLY,
  'audit-pipeline' => "#{READ_ONLY}, plus gh's read commands (--gh-read)",
  'audit-reviewers' => "#{READ_ONLY}, plus gh's read commands (--gh-read)",
  'architect' => "#{READ_ONLY}, plus git add of its own paths and its one commit form (--architect, #0-203)",
  'factory-ops' => 'runs exactly the scripts/factory/ command the workflow names (factory-ops-bash.sh), never a form of its own'
}.freeze
# Rule 4 (backlog #0-235): the word prefixes an allow rule may start with, each with why the program it lets run
# cannot remove or move a path it does not name. A rule's words up to its first `*` must begin with one of them. Each
# prefix is as narrow as the rules it was judged on (review round 1 of #0-235): `gh pr checkout` (a git checkout) or
# `gh run download -D <dir>`, `git fetch --upload-pack=<cmd>` were never judged, so a rule allowing them fails.
SAFE_PREFIXES = {
  %w[ls] => 'lists', %w[jar tf] => 'lists a jar', %w[mkdir -p] => 'creates directories, removes nothing',
  %w[git status] => 'reads', %w[git log] => 'reads (--output as for git diff)', %w[git show] => 'reads (--output as for git diff)', %w[git rev-parse] => 'reads',
  %w[git merge-base] => 'reads', %w[git ls-files] => 'reads', %w[git blame] => 'reads', %w[git branch --list] => 'reads',
  %w[git diff] => 'reads; its --output writes the file it names, which guard-protected-bash.sh refuses into a protected path after resolving // and /./ (a quoted or spliced option word is #0-167)',
  %w[git grep] => 'reads tracked files; -O/--open-files-in-pager, --no-index and --untracked are refused by git_grep_unsafe (_lib.sh, #0-123)',
  %w[git commit] => 'records the index',
  %w[git fetch origin main] => "updates origin/main; guard-git-push.sh refuses another remote or a refspec into a ref (#0-131)",
  %w[git push -u origin] => "sends the run's branch, the only one guard-git-push.sh lets through (#0-131)",
  %w[gh pr create] => 'acts on GitHub', %w[gh pr edit] => 'acts on GitHub', %w[gh pr view] => 'reads GitHub',
  %w[gh pr list] => 'reads GitHub', %w[gh pr ready] => 'acts on GitHub', %w[gh pr diff] => 'reads GitHub',
  %w[gh issue view] => 'reads GitHub', %w[gh issue list] => 'reads GitHub', %w[gh run view] => 'reads GitHub',
  %w[gh run list] => 'reads GitHub', %w[gh label list] => 'reads GitHub'
}.freeze
# Allow rules outside SAFE_PREFIXES, each with its reason.
# git's commands that act on the working tree reach the gitignored runtime state, which lives in it: `git add` stages
# it once .gitignore no longer names it (or with -f), `git rm`/`mv` then delete, move or overwrite it, `git switch`
# overwrites it from a commit that tracks it. git_runtime_unsafe (_lib.sh) refuses the plain forms, a heuristic
# (review rounds 1-2 of #0-235); moving the state out of the working tree is backlog #0-236.
GIT_TREE = "acts on the working tree, where the gitignored runtime state lives; git_runtime_unsafe (_lib.sh) refuses its plain forms (add -f, a path into or above the state), a heuristic until the state leaves the working tree: backlog #0-236"
GIT_SWITCH = "overwrites or removes an ignored file from a commit that tracks it (one made after git add -f, refused, or a reviewed commit on main); no guard refuses any form of it: open until the state leaves the working tree, backlog #0-236"
FACTORY_SCRIPTS = 'only factory-ops runs them (guard-factory-scripts.sh), as they are on the base commit, one call per run (ADR-0053); state.sh is what writes the lock'
EXEMPT_RULES = {
  'Bash(./mvnw *)' => "Maven runs the build's own code, which can remove any path; no rule can bound it: backlog #0-116",
  'Bash(scripts/factory/*)' => FACTORY_SCRIPTS,
  'Bash(./scripts/factory/*)' => FACTORY_SCRIPTS,
  'Bash(git add *)' => GIT_TREE, 'Bash(git rm *)' => GIT_TREE, 'Bash(git mv *)' => GIT_TREE,
  'Bash(git switch *)' => GIT_SWITCH, 'Bash(git checkout -b *)' => GIT_SWITCH
}.freeze
LOCK_BRANCH = 'fix/0-25-notification-queue-version'
START = '<!-- commands:start -->'
STOP = '<!-- commands:end -->'

@failures = []
def fail!(msg)
  @failures << msg
end

def read_utf8(path)
  File.read(path, encoding: 'UTF-8')
end

# The frontmatter (YAML between the first two `---` lines) and the body of a definition.
def split_definition(path)
  text = read_utf8(path)
  m = text.match(/\A---\n(.*?)\n---\n(.*)\z/m)
  raise "#{path}: no frontmatter" unless m
  [YAML.safe_load(m[1]) || {}, m[2]]
end

# The commands of the marked list: one `- \`command\`` per line between the markers.
def listed_commands(body, where)
  return nil unless body.include?(START)
  inner = body.split(START, 2)[1]
  raise "#{where}: #{START} without #{STOP}" unless inner.include?(STOP)
  inner = inner.split(STOP, 2)[0]
  inner.lines.map(&:strip).reject(&:empty?).map do |line|
    m = line.match(/\A- `(.+)`\z/)
    raise "#{where}: a line of the command list is not \"- `<command>`\": #{line}" unless m
    m[1]
  end
end

# The command with every quoted string removed: what is left is what the shell reads as syntax.
def unquoted(cmd)
  cmd.gsub(/'[^']*'/, "''").gsub(/"(?:[^"\\$`]|\\.)*"/, '""')
end

def family_refusal(cmd)
  bare = unquoted(cmd)
  return 'an unterminated quote' if bare.count("'").odd? || bare.count('"').odd?
  return 'a newline' if cmd.include?("\n")
  return 'a separator, pipe, redirect or substitution outside quotes' if bare =~ /[|;&<>`]|\$\(/
  return 'an expansion ($) outside quotes' if bare.include?('$')
  return 'an expansion ($ or a backtick) inside double quotes' if cmd.scan(/"(?:[^"\\]|\\.)*"/).any? { |q| q =~ /[$`]/ }
  return 'an unquoted glob (* ? [)' if bare =~ /[*?\[]/
  words = bare.split(/\s+/)
  return 'a path through ..' if cmd.split(/\s+/).any? { |w| w.delete(%q('")).split('/').include?('..') }
  return 'an absolute or ~ path' if words.any? { |w| w.start_with?('/', '~') }
  return 'git -C' if words[0] == 'git' && words.include?('-C')
  return 'cd' if words[0] == 'cd'
  return 'a recursive cp' if words[0] == 'cp' && words.any? { |w| w =~ /\A-[A-Za-z]*[rRa]/ || %w[--recursive --archive].include?(w) }
  nil
end

# Claude Code's Bash rule: `prefix:*` matches the prefix, otherwise `*` is any text and the rest is literal.
def rule_matches?(rule, cmd)
  body = rule[/\ABash\((.*)\)\z/m, 1]
  return false unless body
  return cmd.start_with?(body[0..-3]) if body.end_with?(':*')
  Regexp.new('\A' + body.split('*', -1).map { |part| Regexp.escape(part) }.join('.*') + '\z', Regexp::MULTILINE).match?(cmd)
end

def bash_hooks(entries)
  (entries || []).select { |e| e['matcher'].nil? || Regexp.new("\\A(?:#{e['matcher']})\\z").match?('Bash') }
                 .flat_map { |e| e['hooks'] || [] }.map { |h| h['command'] }.compact
end

def run_hook(command, input, repo)
  out, status = Open3.capture2e({ 'CLAUDE_PROJECT_DIR' => ROOT }, 'bash', '-c', command, stdin_data: input, chdir: repo)
  [status.exitstatus, out.strip]
end

# The entries of a skill's `allowed-tools`: a string of space-separated tools (a `Bash(...)` may hold spaces) or a list.
def allowed_tools(value)
  return value.map(&:to_s) if value.is_a?(Array)
  value.to_s.scan(/[A-Za-z_][\w-]*(?:\([^)]*\))?/)
end

# Rule 4: why an allow rule may let a program remove or move a path it does not name, or nil when it cannot.
def removal_refusal(rule)
  return 'it allows every Bash command' if rule == 'Bash'
  body = rule[/\ABash\((.*)\)\z/m, 1]
  return nil unless body
  body = body[0..-3] if body.end_with?(':*')
  # A separator, pipe, redirect or substitution in the rule itself: a safe first word would carry any command after it.
  return "its text holds a shell operator (#{body[/[;&|<>`\n]|\$\(/].inspect}), so a safe first word would carry another command" if body =~ /[;&|<>`\n]|\$\(/
  words = body.split(/\s+/).reject(&:empty?)
  fixed = words.take_while { |w| !w.include?('*') }
  return nil if SAFE_PREFIXES.keys.any? { |prefix| fixed.first(prefix.size) == prefix }
  "it runs #{fixed.empty? ? 'any program' : fixed.join(' ')}, which is not in SAFE_PREFIXES: a program that can remove or move a path it does not name (rm -rf .ai/runs) is never allowed"
end

settings = SETTINGS.map { |f| JSON.parse(read_utf8(f)) }
allow = settings.flat_map { |s| s.dig('permissions', 'allow') || [] }
deny = settings.flat_map { |s| s.dig('permissions', 'deny') || [] }
session_hooks = settings.flat_map { |s| bash_hooks(s.dig('hooks', 'PreToolUse')) }

lists = {}
own_hooks = {}
preloaded = {}
bash_agents = []
Dir.glob(File.join(AGENTS_DIR, '**', '*.md')).sort.each do |path|
  rel = path.sub("#{ROOT}/", '')
  front, body = split_definition(path)
  name = front['name']
  tools = front['tools'].to_s.split(',').map(&:strip)
  bash_agents << name if tools.empty? || tools.any? { |t| t == 'Bash' || t.start_with?('Bash(') }
  own_hooks[name] = bash_hooks(front.dig('hooks', 'PreToolUse'))
  Array(front['skills']).each { |skill| (preloaded[skill] ||= []) << name }
  cmds = listed_commands(body, rel)
  next if cmds.nil?
  fail!("#{rel}: the command list names no command") if cmds.empty?
  lists[name] = { where: rel, cmds: cmds }
rescue StandardError => e
  fail!(e.message)
end
followers = ALSO_FOR.values.flatten
bash_agents.each do |name|
  next if lists.key?(name) || followers.include?(name) || EXEMPT.key?(name)
  fail!("the definition of #{name} runs Bash and has no command list (#{START} … #{STOP}); give it one, or an EXEMPT entry with its reason")
end
EXEMPT.each_key do |name|
  fail!("EXEMPT names #{name}, which is no agent with Bash: remove the stale exemption") unless bash_agents.include?(name)
  fail!("EXEMPT names #{name}, which has a command list: remove the stale exemption") if lists.key?(name)
end
ALSO_FOR.each do |lead, names|
  names.each do |n|
    fail!("ALSO_FOR names #{n} for #{lead}, and #{n} is no agent with Bash") unless bash_agents.include?(n)
    fail!("ALSO_FOR names #{n} for #{lead}, and #{n} has a command list of its own: remove the stale entry") if lists.key?(n)
  end
end

# Rule 4 (backlog #0-235): the allow rules of both settings files, and the allow-listed tools of every skill an
# autopilot agent preloads.
rule_sources = SETTINGS.zip(settings).flat_map do |file, s|
  (s.dig('permissions', 'allow') || []).map { |r| [r, file.sub("#{ROOT}/", '')] }
end
preloaded.each do |skill, agents|
  path = File.join(ROOT, '.claude', 'skills', skill, 'SKILL.md')
  unless File.file?(path)
    fail!("#{agents.join(', ')} preload#{agents.size == 1 ? 's' : ''} the skill #{skill}, which has no #{path.sub("#{ROOT}/", '')}")
    next
  end
  front, = split_definition(path)
  allowed_tools(front['allowed-tools']).each { |r| rule_sources << [r, "#{path.sub("#{ROOT}/", '')} (allowed-tools, preloaded by #{agents.join(', ')})"] }
rescue StandardError => e
  fail!(e.message)
end
rule_sources.each do |rule, where|
  next if EXEMPT_RULES.key?(rule)
  why = removal_refusal(rule)
  fail!("#{where}: the allow rule #{rule}: #{why}; add its prefix to SAFE_PREFIXES with why it cannot, or the rule to EXEMPT_RULES with its reason (backlog #0-235)") if why
end
EXEMPT_RULES.each_key do |rule|
  fail!("EXEMPT_RULES names #{rule}, which no allow rule holds: remove the stale exemption") unless rule_sources.any? { |r, _| r == rule }
end

checked = 0
Dir.mktmpdir('agent-commands') do |repo|
  system('git', 'init', '-q', repo, exception: true)
  system('git', '-C', repo, '-c', 'user.name=check', '-c', 'user.email=check@example.invalid', 'commit', '-q',
         '--allow-empty', '-m', 'base', exception: true)
  head = Open3.capture2('git', '-C', repo, 'rev-parse', 'HEAD')[0].strip
  FileUtils.mkdir_p(File.join(repo, '.ai', 'runs'))
  File.write(File.join(repo, '.ai', 'runs', 'LOCK'), "check-run #{head} #{LOCK_BRANCH}\n")

  lists.each do |name, list|
    ([name] + (ALSO_FOR[name] || [])).each do |agent|
      list[:cmds].each do |cmd|
        checked += 1
        where = "#{list[:where]} (#{agent}): #{cmd}"
        if (why = family_refusal(cmd))
          fail!("#{where}: #{why}, which Claude Code or the hooks refuse whatever the allow rules say")
          next
        end
        fail!("#{where}: no allow rule of the two settings files matches it (dontAsk refuses it)") unless allow.any? { |r| rule_matches?(r, cmd) }
        hit = deny.find { |r| rule_matches?(r, cmd) }
        fail!("#{where}: the deny rule #{hit} matches it") if hit
        input = JSON.generate({ 'agent_type' => agent, 'tool_name' => 'Bash', 'tool_input' => { 'command' => cmd } })
        (session_hooks + (own_hooks[agent] || [])).each do |hook|
          status, out = run_hook(hook, input, repo)
          fail!("#{where}: refused by #{hook.split('/').last} (exit #{status}): #{out}") unless status.zero?
        end
      end
    end
  end
end

if @failures.empty?
  puts "Agent commands: #{checked} command form(s) of #{lists.size} definition(s) (#{lists.keys.sort.join(', ')}) pass their hooks and an allow rule of the two settings files, no deny rule, none of a refused family; #{EXEMPT.size} agent(s) with Bash exempt with a reason; of #{rule_sources.count { |r, _| r == 'Bash' || r.start_with?('Bash(') }} Bash allow rule(s), #{EXEMPT_RULES.size} exempt with a reason and none of the others runs a program that removes a path it does not name."
else
  @failures.each { |f| puts "::error::#{f}" }
  exit 1
end
