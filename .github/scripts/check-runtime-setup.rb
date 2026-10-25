# ============================================================
# Every step that runs Ruby or Node runs it on the version its file pins: .ruby-version (backlog #0-166, ADR-0040),
# .node-version (backlog #0-163, ADR-0043).
#
# .github/actions/setup-ruby and .github/actions/setup-node are the one way CI sets each up (ruby/setup-ruby and
# actions/setup-node, pinned by SHA, no version input). A job that runs a .rb check or a JavaScript test without its
# setup runs on the runner's own runtime, which once changed a check's result (PR #517, the json gem), and nothing
# would say so: the step still passes. For each runtime of RUNTIMES this refuses, in every job of
# .github/workflows/*.y(a)ml and in every composite action under .github/actions/:
#   1. a `run:` step that runs it before a step `uses: <its action>` in the same job (or, in that action itself,
#      before its `uses: <upstream action>`). A step runs it when its text, comment lines aside, names its command
#      or a file it runs as a script (`.rb`; `.mjs`, not `.js`: a .js name is mostly a file Node never runs, such as
#      an action's `main:` in a test fixture), or names a .github/scripts/*.sh or .claude/hooks/*.sh script whose
#      own text does (one level: what that script calls in turn is not followed);
#   2. a `uses:` of its upstream action anywhere but in its own action, and a version input on it (the version
#      file alone decides the version).
# Until #0-163 this was check-ruby-setup.rb, for Ruby alone; Node joined as a row of the table, not a second check
# (one list per control).
#
# What it does not see, left to review: a runtime run by a program the step starts (a Makefile, a script two levels
# down), a composite action of another directory, and a `uses:` of a third-party action that runs one itself.
# It is a check against the honest mistake of a new step in a job without the setup, not a boundary.
#
# A file that cannot be read as YAML fails the check. Run from the repository root (CI: lint-workflows, after its
# own cases in test-runtime-setup.sh), or with the root as the only argument.
# Runs on the Ruby of .ruby-version (ADR-0040).
# ============================================================
require_relative 'run-blocks'

ROOT = File.expand_path(ARGV[0] || '.')
SCRIPT = %r{(?:\./)?((?:\.github/scripts|\.claude/hooks)/[\w.-]+\.sh)\b}.freeze

# One row per runtime. `runs` is its run pattern: the command (not part of a longer word or path such as
# ruby/setup-ruby, RUBYOPT, .ruby-version, setup-node, node_modules) or a file of its own.
RUNTIMES = [
  { name: 'Ruby', action: './.github/actions/setup-ruby', action_file: %r{\A\.github/actions/setup-ruby/action\.ya?ml\z},
    upstream: 'ruby/setup-ruby', input: 'ruby-version', version_file: '.ruby-version', why: 'backlog #0-166, ADR-0040',
    runs: %r{(?<![\w./-])ruby(?![\w./-])|\.rb\b} },
  { name: 'Node', action: './.github/actions/setup-node', action_file: %r{\A\.github/actions/setup-node/action\.ya?ml\z},
    upstream: 'actions/setup-node', input: 'node-version', version_file: '.node-version', why: 'backlog #0-163, ADR-0043',
    runs: %r{(?<![\w./-])node(?![\w./-])|\.mjs\b} },
].freeze

$errors = 0

def fail!(file, msg, why, line: nil)
  puts Annotation.error("#{msg} (#{why})", file: file, line: line)
  $errors += 1
end

# The text without its comment lines: a comment that mentions a runtime runs nothing.
def code(text)
  text.lines.reject { |l| l =~ /\A\s*#/ }.join
end

# Why a run block runs the runtime, or nil: the text itself, or a script it names whose text does.
def runs_it(text, pattern)
  body = code(text)
  return "`#{body[pattern]}`" if body =~ pattern

  body.scan(SCRIPT).flatten.uniq.each do |script|
    path = File.join(ROOT, script)
    next unless File.file?(path)

    found = code(File.read(path, encoding: 'UTF-8'))[pattern]
    return "#{script} (it runs `#{found}`)" if found
  end
  nil
end

# The steps of each job of a workflow, or of a composite action, as [[label, [step nodes]]].
def jobs_of(file, top)
  if file.start_with?('.github/actions/')
    runs = RunBlocks.get(top, 'runs')
    return [] unless RunBlocks.scalar(RunBlocks.get(runs, 'using')) == 'composite'

    [[file, RunBlocks.seq(RunBlocks.get(runs, 'steps'))]]
  else
    jobs = RunBlocks.get(top, 'jobs')
    return [] unless jobs.is_a?(Psych::Nodes::Mapping)

    jobs.children.each_slice(2).map { |name, job| ["job #{RunBlocks.scalar(name)}", RunBlocks.seq(RunBlocks.get(job, 'steps'))] }
  end
end

files = RunBlocks.workflows(ROOT) + RunBlocks.actions(ROOT)
if RunBlocks.workflows(ROOT).empty?
  puts Annotation.error("no workflow found under #{ROOT}/.github/workflows: run from the repository root (backlog #0-166, #0-163)")
  exit 1
end

steps_checked = 0
found = Hash.new(0)
files.each do |path|
  file = path.sub(%r{\A#{Regexp.escape(ROOT)}/?}, '')
  begin
    doc = Psych.parse(File.read(path, encoding: 'UTF-8'))
  rescue StandardError => e
    fail!(file, "#{file} cannot be read as YAML: #{e.message.lines.first.to_s.strip}", 'backlog #0-166, #0-163')
    next
  end
  top = doc && doc.root
  unless top.is_a?(Psych::Nodes::Mapping)
    fail!(file, "#{file} is not a YAML mapping", 'backlog #0-166, #0-163')
    next
  end
  jobs_of(file, top).each do |label, steps|
    ready = {}
    steps.each do |step|
      steps_checked += 1
      line = step.start_line + 1
      name = RunBlocks.scalar(RunBlocks.get(step, 'name')) || RunBlocks.scalar(RunBlocks.get(step, 'id')) || 'unnamed step'
      uses = RunBlocks.scalar(RunBlocks.get(step, 'uses')).to_s
      run = RunBlocks.scalar(RunBlocks.get(step, 'run'))
      RUNTIMES.each do |rt|
        in_action = file =~ rt[:action_file]
        if uses.start_with?("#{rt[:upstream]}@")
          fail!(file, "step '#{name}' uses #{rt[:upstream]}: set #{rt[:name]} up through #{rt[:action]} only", rt[:why], line: line) unless in_action
          if RunBlocks.get(RunBlocks.get(step, 'with'), rt[:input])
            fail!(file, "step '#{name}' gives #{rt[:upstream]} a #{rt[:input]}: #{rt[:version_file]} alone decides it", rt[:why], line: line)
          end
          ready[rt[:name]] = true if in_action
        end
        ready[rt[:name]] = true if uses == rt[:action]
        next unless run

        why = runs_it(run, rt[:runs])
        next unless why

        found[rt[:name]] += 1
        next if ready[rt[:name]]

        fail!(file, "#{label}: step '#{name}' runs #{rt[:name]} (#{why}) before `uses: #{rt[:action]}`: it would run on " \
                    "the runner's own #{rt[:name]}, not #{rt[:version_file]}'s", rt[:why], line: line)
      end
    end
  end
end

exit 1 if $errors > 0
counts = RUNTIMES.map { |rt| "all #{found[rt[:name]]} that run #{rt[:name]} come after #{rt[:action]}" }.join('; ')
puts "Checked #{steps_checked} steps in #{files.size} workflows and actions: #{counts}."
