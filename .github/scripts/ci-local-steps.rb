# ============================================================
# Runs the `run:` steps of one job of a workflow, the way GitHub's runner does, for .github/scripts/ci-local.sh
# (backlog #0-166 PR 2): the workflow is the one list of what CI checks, so a step added to CI runs here too, with
# nothing to copy (FAC-A01).
#
#   ruby ci-local-steps.rb <workflow file> <job id> [--step <text>]...
#
# First the versions of Ruby, json and psych, as CI's setup-ruby action prints them. Then every step in order: its
# `run:` text in `bash --noprofile --norc -eo pipefail` (GitHub's default shell on Linux), in its
# `working-directory`, with the job's and the step's `env` and RUNNER_TEMP (removed at the end). One line per
# step, `[<exit code>] <name> (<seconds>s)`, the last 40 lines of a failing step's output under it; every step runs
# even after a failure (unlike CI, so that one run shows every problem), and the exit code is 1 when one failed.
#
# Not run, each printed as `[skip] <name>: <why>`: a `uses:` step (actions/checkout and ./.github/actions/setup-ruby
# are what the container already is: the copied tree, the Ruby of .ruby-version), a step with an `if:`, a step
# whose `run:` or `env` holds a `${{ }}` expression (the event, a secret, a token), and a step in another shell.
#
# --step: only the steps whose name contains one of the texts, and every step that runs install-pinned-tool.sh
# (what the others need). Exit 2 on a usage error, a workflow that cannot be read or a job it does not have.
# Tested by .github/scripts/test-ci-local.sh. Runs on the Ruby of .ruby-version (ADR-0040).
# ============================================================
require 'fileutils'
require 'json'
require 'open3'
require 'psych'
require 'tmpdir'
require 'yaml'

REPLACED = %w[actions/checkout ./.github/actions/setup-ruby].freeze
TAIL = 40

def usage!(msg)
  warn "ci-local-steps: #{msg}"
  warn 'usage: ruby ci-local-steps.rb <workflow file> <job id> [--step <text>]...'
  exit 2
end

args = ARGV.dup
filters = []
positional = []
until args.empty?
  a = args.shift
  case a
  when '--step'
    f = args.shift
    usage!('--step needs a text') if f.nil? || f.empty?
    filters << f
  when /\A-/ then usage!("unknown option #{a}")
  else positional << a
  end
end
usage!('a workflow file and a job id') unless positional.size == 2
file, job_id = positional

begin
  # aliases: a workflow may use YAML anchors (GitHub reads them), which safe_load refuses by default.
  workflow = YAML.safe_load(File.read(file, encoding: 'UTF-8'), aliases: true)
rescue SystemCallError, Psych::Exception => e
  usage!("cannot read #{file}: #{e.message.lines.first.to_s.strip}")
end
job = workflow.is_a?(Hash) && workflow['jobs'].is_a?(Hash) ? workflow['jobs'][job_id] : nil
usage!("#{file} has no job #{job_id}") unless job.is_a?(Hash) && job['steps'].is_a?(Array)

# A step's env as strings, or the reason it cannot be run here.
def step_env(env)
  out = {}
  (env || {}).each do |k, v|
    v = v.to_s
    return [nil, "its env #{k} is #{v}"] if v.include?('${{')

    out[k.to_s] = v
  end
  [out, nil]
end

job_env, why = step_env(job['env'])
usage!("job #{job_id}: #{why}, not supplied locally") unless job_env

# What runs the steps, as .github/actions/setup-ruby prints it in CI: the same Ruby version is not the same Ruby when
# its gems differ (PR #517), so a local run shows them too (AC5 of #0-166; that action itself is skipped below).
puts "#{RUBY_DESCRIPTION}; json #{JSON::VERSION}, psych #{Psych::VERSION}"

runner_temp = Dir.mktmpdir('runner-temp-')
at_exit { FileUtils.remove_entry(runner_temp) if File.directory?(runner_temp) }
failed = 0
ran = 0
skipped = 0
job['steps'].each_with_index do |step, i|
  name = (step['name'] || step['uses'] || step['run'].to_s.lines.first || "step #{i + 1}").to_s.strip
  # GitHub reads every `run:` as text: YAML makes `run: true` a boolean, `run: 1` a number.
  run = step.key?('run') && !step['run'].nil? ? step['run'].to_s : nil
  reason =
    if step['uses']
      REPLACED.any? { |r| step['uses'].start_with?(r) } ? "uses #{step['uses'].split('@').first}: the container is that" : "uses #{step['uses']}: not run locally"
    elsif step.key?('if') then "has `if: #{step['if']}`"
    elsif run.nil? then 'no run'
    elsif run.include?('${{') then 'its run holds a ${{ }} expression'
    elsif step['shell'] && step['shell'] != 'bash' then "runs in #{step['shell']}"
    elsif !filters.empty? && filters.none? { |f| name.include?(f) } && !run.include?('install-pinned-tool.sh')
      'not selected (--step)'
    end
  env = nil
  unless reason
    env, why = step_env(step['env'])
    reason = why unless env
  end
  if reason
    skipped += 1
    puts "[skip] #{name}: #{reason}"
    next
  end

  script = File.join(runner_temp, "step-#{i + 1}.sh")
  File.write(script, run)
  dir = step['working-directory'] || '.'
  started = Time.now
  out, status = Open3.capture2e(job_env.merge(env).merge('RUNNER_TEMP' => runner_temp),
                                'bash', '--noprofile', '--norc', '-eo', 'pipefail', script, chdir: dir)
  ran += 1
  code = status.exitstatus || 1
  puts "[#{code}] #{name} (#{(Time.now - started).round}s)"
  next if code.zero?

  failed += 1
  out.lines.last(TAIL).each { |l| puts "    #{l}" }
end

puts "#{job_id}: #{ran} step(s) run, #{failed} failed, #{skipped} skipped."
exit(failed.zero? ? 0 : 1)
