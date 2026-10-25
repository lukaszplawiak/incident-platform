# ============================================================
# Every step that runs Ruby runs it on the Ruby of .ruby-version (backlog #0-166, ADR-0040).
#
# .github/actions/setup-ruby is the one way CI sets Ruby up (ruby/setup-ruby, pinned by SHA, no `ruby-version`
# input). A job that runs a .rb check without it runs on the runner's own Ruby and gems, which once changed a
# check's result (PR #517, the json gem), and nothing would say so: the step still passes. This refuses, in every
# job of .github/workflows/*.y(a)ml and in every composite action under .github/actions/:
#   1. a `run:` step that runs Ruby before a step `uses: ./.github/actions/setup-ruby` in the same job (or, in
#      that action itself, before its `uses: ruby/setup-ruby`). A step runs Ruby when its text, comment lines
#      aside, names the `ruby` command or a `.rb` file, or names a .github/scripts/*.sh or .claude/hooks/*.sh
#      script whose own text does (one level: what that script calls in turn is not followed);
#   2. a `uses: ruby/setup-ruby` anywhere but in .github/actions/setup-ruby, and a `ruby-version` input on it
#      (.ruby-version alone decides the version).
#
# What it does not see, left to review: Ruby run by a program the step starts (a Makefile, a script two levels
# down), a composite action of another directory, and a `uses:` of a third-party action that runs Ruby itself.
# It is a check against the honest mistake of a new step in a job without the setup, not a boundary.
#
# A file that cannot be read as YAML fails the check. Run from the repository root (CI: lint-workflows, after its
# own cases in test-ruby-setup.sh), or with the root as the only argument. Portable to Ruby 2.6.
# ============================================================
require_relative 'run-blocks'

ROOT = File.expand_path(ARGV[0] || '.')
ACTION = './.github/actions/setup-ruby'.freeze
ACTION_FILE = %r{\A\.github/actions/setup-ruby/action\.ya?ml\z}.freeze
UPSTREAM = %r{\Aruby/setup-ruby@}.freeze
# The `ruby` command (not ruby/setup-ruby, RUBYOPT or a .ruby-version path), or a .rb file.
RUBY = %r{(?<![\w./-])ruby(?![\w./-])|\.rb\b}.freeze
SCRIPT = %r{(?:\./)?((?:\.github/scripts|\.claude/hooks)/[\w.-]+\.sh)\b}.freeze

$errors = 0

def fail!(file, msg, line: nil)
  puts Annotation.error("#{msg} (backlog #0-166, ADR-0040)", file: file, line: line)
  $errors += 1
end

# The text without its comment lines: a comment that mentions Ruby runs nothing.
def code(text)
  text.lines.reject { |l| l =~ /\A\s*#/ }.join
end

# Why a run block runs Ruby, or nil: the text itself, or a script it names whose text does.
def runs_ruby(text)
  body = code(text)
  return "`#{body[RUBY]}`" if body =~ RUBY

  body.scan(SCRIPT).flatten.uniq.each do |script|
    path = File.join(ROOT, script)
    next unless File.file?(path)

    found = code(File.read(path, encoding: 'UTF-8'))[RUBY]
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
  puts Annotation.error("no workflow found under #{ROOT}/.github/workflows: run from the repository root (backlog #0-166)")
  exit 1
end

steps_checked = 0
ruby_steps = 0
files.each do |path|
  file = path.sub(%r{\A#{Regexp.escape(ROOT)}/?}, '')
  begin
    doc = Psych.parse(File.read(path, encoding: 'UTF-8'))
  rescue StandardError => e
    fail!(file, "#{file} cannot be read as YAML: #{e.message.lines.first.to_s.strip}")
    next
  end
  top = doc && doc.root
  unless top.is_a?(Psych::Nodes::Mapping)
    fail!(file, "#{file} is not a YAML mapping")
    next
  end
  in_action = file =~ ACTION_FILE
  jobs_of(file, top).each do |label, steps|
    ready = false
    steps.each do |step|
      steps_checked += 1
      line = step.start_line + 1
      name = RunBlocks.scalar(RunBlocks.get(step, 'name')) || RunBlocks.scalar(RunBlocks.get(step, 'id')) || 'unnamed step'
      uses = RunBlocks.scalar(RunBlocks.get(step, 'uses')).to_s
      if uses =~ UPSTREAM
        fail!(file, "step '#{name}' uses ruby/setup-ruby: set Ruby up through #{ACTION} only", line: line) unless in_action
        if RunBlocks.get(RunBlocks.get(step, 'with'), 'ruby-version')
          fail!(file, "step '#{name}' gives ruby/setup-ruby a ruby-version: .ruby-version alone decides it", line: line)
        end
        ready = true if in_action
      end
      ready = true if uses == ACTION
      run = RunBlocks.scalar(RunBlocks.get(step, 'run'))
      next unless run

      why = runs_ruby(run)
      next unless why

      ruby_steps += 1
      next if ready

      fail!(file, "#{label}: step '#{name}' runs Ruby (#{why}) before `uses: #{ACTION}`: it would run on the " \
                  "runner's own Ruby and gems, not .ruby-version's", line: line)
    end
  end
end

exit 1 if $errors > 0
puts "Checked #{steps_checked} steps in #{files.size} workflows and actions: all #{ruby_steps} that run Ruby come after #{ACTION}."
