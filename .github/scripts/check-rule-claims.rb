# ============================================================
# What the review rule files claim is true (backlog #0-184). The files of .ai/rules/review/ make three claims that
# nobody checked, and each had been wrong before:
#   1. A rule's label names a check (`_common.md`). Every backticked script (`*.sh`, `*.rb`, `*.mjs`, `*.js`) or Java
#      test or guard (`ApiKeyDenyByDefaultTest`, `StructuredLoggingGuard`) a label names outside its parentheses
#      exists. An "Enforced by", which tells the reviewer not to review the rule, also names at least one script or
#      job id, and each runs where a red result
#      stops the merge: a script in a step of a job of the ruleset of main's required checks
#      (.github/repository-settings/rulesets/main.json), in a workflow that runs on a pull request, in neither a step
#      nor a job whose `if:` leaves pull requests out (or is `false`) or that continues on error, on a line that is no
#      comment, no `echo` and not followed by `|| true`, and not in the step that runs check-factory-guards.sh, which
#      the owner's authorship turns green ("Changes that need the owner"); a job id names such a job. Round 2 of
#      general.md found such a claim wrong by hand.
#   2. No rule file but factory.md names the seeded-defect measure (ADR-0044's residual): every reviewer reads its
#      dimension's file on every run. architecture.md said "a seeded result" through the P-06...P-09 runs. The
#      phrases are narrow on purpose: security.md's "planted by" is a CI step that plants a file, not the measure.
#      factory.md is read only on a diff touching an Owner-only path, which /seed-bugs refuses; its measure is run
#      by hand and rarely (#0-115's F1), so its file stays out (a residual, ADR-0044 decision 6).
#   3. Every pattern of .ai/audit/benchmark/patterns.md that is not retired cites a live id of its dimension's file
#      (#0-178 "Also"): retiring ARC-20 left P-06...P-08 citing it, and nothing failed. The file is the dimension's
#      name, except an FAC- id: FAC-S is security's and FAC-A architecture's, both in factory.md (the mapping of
#      backlog-autopilot.js's RULE_FILE_OF, backlog #0-161).
#
# What it does not see, left to review: a label's plain words ("the smoke test", "JaCoCo's `check`"), a script run
# by another script, a synonym of the measure outside the phrases, and whether a check decides its rule in full.
# An `if:` with `||` counts as running on pull requests: a required job gated on the paths a PR changes (build-and-test
# skips Markdown-only ones, the smoke test runs only for code or infrastructure) is skipped, and a skipped required
# job counts as passed. That is right only when no PR it skips can break the rule (build-and-test's checks read no
# Markdown); whether it is stays the reviewer's question (ADR-0046).
# It is a check against the honest drift of a renamed script, a moved step or a retired id, not a boundary.
#
# Any input it cannot read fails it. Run from the repository root (CI: factory-guards, after its own cases in
# test-rule-claims.sh), or with the root as the only argument. Runs on the Ruby of .ruby-version (ADR-0040).
# ============================================================
require 'json'
require_relative 'run-blocks'

ROOT = File.expand_path(ARGV[0] || '.')
RULES_DIR = '.ai/rules/review'
PATTERNS = '.ai/audit/benchmark/patterns.md'
RULESET = '.github/repository-settings/rulesets/main.json'
OWNER_STEP_SCRIPT = 'check-factory-guards.sh'
# The rule file every reviewer may read only on an Owner-only diff (claim 2).
MEASURE_EXEMPT = 'factory.md'
MEASURE_PHRASES = /\bbenchmark|\bseed-bugs\b|\bseed(?:ed|er|ers|ing)\b|\bseed branch|\bpatterns\.md\b|\bP-\d{2}\b/i.freeze
ID = /[A-Z][A-Z0-9]*-[A-Z]?\d+/.freeze
SCRIPT_TOKEN = /\A[\w.-]+\.(?:sh|rb|mjs|js)\z/.freeze
# A Java check is a test or a guard: other backticked names in a label (`WebFetch`, `Service`) are what it checks.
CLASS_TOKEN = /\A[A-Z][A-Za-z0-9]*(?:Test|Tests|IT|Guard)\z/.freeze
# An `if:` that compares the event with anything but pull_request (or excludes pull_request), with no `||`.
NOT_ON_PR = /event_name\s*(?:==\s*'(?!pull_request')|!=\s*'pull_request')/.freeze

$failures = 0

def fail_at(msg, file: nil, line: nil)
  puts Annotation.error(msg, file: file, line: line)
  $failures += 1
end

def read(rel)
  File.read(File.join(ROOT, rel))
rescue SystemCallError => e
  fail_at("cannot read #{rel}: #{e.message}")
  nil
end

# The rules of a rule file: a bullet `- **<ID> ...` and the indented lines that continue it, joined.
Rule = Struct.new(:id, :line, :text, :retired)

def rules_of(text)
  rules = []
  cur = nil
  text.each_line.with_index(1) do |l, n|
    if (m = l.match(/\A- \*\*(#{ID})(\*\*\s+retired\b)?/))
      cur = Rule.new(m[1], n, l.strip, !m[2].nil?)
      rules << cur
    elsif cur && l.match?(/\A {2,}\S/)
      cur.text << ' ' << l.strip
    else
      cur = nil
    end
  end
  rules
end

# Each label of a rule as [kind, clause]: the text after "Enforced by:" or "Backed by:" up to the first full stop
# outside backticks and parentheses.
def labels_of(text)
  text.to_enum(:scan, /\b(Enforced|Backed) by:/).map do
    m = Regexp.last_match
    rest = text[m.end(0)..]
    tick = false
    depth = 0
    stop = rest.length
    rest.each_char.with_index do |c, k|
      tick = !tick if c == '`'
      next if tick
      depth += 1 if c == '('
      depth -= 1 if c == ')'
      if c == '.' && depth <= 0 && (k + 1 == rest.length || rest[k + 1] == ' ')
        stop = k
        break
      end
    end
    [m[1], rest[0...stop].strip]
  end
end

# The clause without what its parentheses explain: the checks it names are outside them.
def outside_parens(clause)
  out = +''
  depth = 0
  tick = false
  clause.each_char do |c|
    tick = !tick if c == '`'
    unless tick
      depth += 1 if c == '('
      depth -= 1 if c == ')' && depth.positive?
      next if depth.positive? || c == ')'
    end
    out << c if depth.zero?
  end
  out
end

# The files under the root, by path. In CI the checkout holds the tracked files only; a local run (ci-local.sh copies
# the tracked and the new, not ignored files) also counts an untracked one, so a script that exists only locally
# passes there and fails in CI.
def files_on_disk
  @files_on_disk ||= Dir.glob('**/*', File::FNM_DOTMATCH, base: ROOT)
                        .reject { |p| p.start_with?('.git/') || p.include?('/node_modules/') || p.include?('/target/') }
                        .select { |p| File.file?(File.join(ROOT, p)) }
end

def exists_named?(basename)
  files_on_disk.any? { |p| File.basename(p) == basename }
end

# Where each step and job of the workflows runs: [{workflow, job_id, job_name, on_pr, job_if, steps: [{name, if, run}]}].
def jobs_of_workflows
  RunBlocks.workflows(ROOT).flat_map do |path|
    file = path.sub("#{ROOT}/", '')
    begin
      top = Psych.parse(File.read(path))&.root
    rescue StandardError => e
      fail_at("#{file} cannot be read as YAML: #{e.message.lines.first.to_s.strip}", file: file)
      next []
    end
    unless top.is_a?(Psych::Nodes::Mapping)
      fail_at("#{file} is not a YAML mapping", file: file)
      next []
    end
    on = RunBlocks.get(top, 'on')
    on_pr = case on
            when Psych::Nodes::Scalar then on.value == 'pull_request'
            when Psych::Nodes::Sequence then on.children.any? { |c| RunBlocks.scalar(c) == 'pull_request' }
            when Psych::Nodes::Mapping then !RunBlocks.get(on, 'pull_request').nil? ||
                                            on.children.each_slice(2).any? { |k, _| RunBlocks.scalar(k) == 'pull_request' }
            else false
            end
    jobs = RunBlocks.get(top, 'jobs')
    next [] unless jobs.is_a?(Psych::Nodes::Mapping)
    jobs.children.each_slice(2).map do |k, job|
      id = RunBlocks.scalar(k)
      steps = RunBlocks.seq(RunBlocks.get(job, 'steps')).map do |s|
        { name: RunBlocks.scalar(RunBlocks.get(s, 'name')) || 'unnamed step',
          if: RunBlocks.scalar(RunBlocks.get(s, 'if')).to_s,
          continue: RunBlocks.scalar(RunBlocks.get(s, 'continue-on-error')).to_s,
          run: RunBlocks.scalar(RunBlocks.get(s, 'run')).to_s }
      end
      { workflow: file, job_id: id, job_name: RunBlocks.scalar(RunBlocks.get(job, 'name')) || id, on_pr: on_pr,
        job_if: RunBlocks.scalar(RunBlocks.get(job, 'if')).to_s,
        job_continue: RunBlocks.scalar(RunBlocks.get(job, 'continue-on-error')).to_s, steps: steps }
    end
  end
end

def runs_on_pr?(condition)
  return false if condition.gsub(/\A\$\{\{|\}\}\z/, '').strip == 'false'

  !(condition.match?(NOT_ON_PR) && !condition.include?('||'))
end

# A red result stops the merge only when neither the step nor its job continues on error (absent or `false`; an
# expression there could be true, so it does not count).
def stops_on_error?(value)
  value.empty? || value == 'false'
end

# Whether a run block runs the script: a line that names it outside a comment, not as what `echo` or `printf`
# print, and not followed by `|| true` or `|| :`, which turn its failure green.
def runs_script?(run, script)
  name = /(?<![\w.-])#{Regexp.escape(script)}(?![\w.-])/
  run.each_line.any? do |l|
    line = l.sub(/(?:\A|\s)#.*/, '')
    line.match?(name) && !line.match?(/\A\s*(?:echo|printf)\b/) && !line.match?(/\|\|\s*(?:true|:)\s*(?:;|\z)/)
  end
end

def decides?(job, required)
  required.include?(job[:job_name]) && job[:on_pr] && runs_on_pr?(job[:job_if]) && stops_on_error?(job[:job_continue])
end

def required_checks
  raw = read(RULESET) or return nil
  contexts = JSON.parse(raw).fetch('rules', []).select { |r| r['type'] == 'required_status_checks' }
                 .flat_map { |r| r.dig('parameters', 'required_status_checks').to_a.map { |c| c['context'] } }
  fail_at("#{RULESET} lists no required status check") if contexts.empty?
  contexts
rescue JSON::ParserError => e
  fail_at("#{RULESET} is not JSON: #{e.message.lines.first.to_s.strip}")
  nil
end

# Why a script does not decide a rule on every pull request, or nil when one step of a required job runs it.
def enforced_script_gap(script, jobs, required)
  runners = jobs.flat_map { |j| j[:steps].select { |s| runs_script?(s[:run], script) }.map { |s| [j, s] } }
  return "no workflow step runs #{script}" if runners.empty?

  ok = runners.any? do |j, s|
    decides?(j, required) && runs_on_pr?(s[:if]) && stops_on_error?(s[:continue]) && !s[:run].include?(OWNER_STEP_SCRIPT)
  end
  return nil if ok

  where = runners.map { |j, s| "#{j[:workflow]} job \"#{j[:job_name]}\" step \"#{s[:name]}\"" }.join('; ')
  "#{script} runs only where a red result need not stop the merge (not a required check, not on a pull request, " \
    "continuing on error, or in the owner's step): #{where}"
end

def check_labels(file, rules, jobs, required)
  job_ids = jobs.map { |j| j[:job_id] }.uniq
  rules.each do |r|
    labels_of(r.text).each do |kind, clause|
      named = outside_parens(clause)
      tokens = named.scan(/`([^`]+)`/).flatten
      scripts = tokens.grep(SCRIPT_TOKEN)
      tokens.grep(CLASS_TOKEN).each do |cls|
        next if exists_named?("#{cls}.java")

        fail_at("#{r.id}: \"#{kind} by\" names #{cls}, and no #{cls}.java exists", file: file, line: r.line)
      end
      scripts.each do |s|
        next if exists_named?(s)

        fail_at("#{r.id}: \"#{kind} by\" names #{s}, which does not exist", file: file, line: r.line)
      end
      next unless kind == 'Enforced'

      ids = named.gsub(/`[^`]*`/, ' ').scan(/(?<![\w.-])[a-z][a-z0-9]*(?:-[a-z0-9]+)+(?![\w-])/).select { |w| job_ids.include?(w) }
      if scripts.empty? && ids.empty?
        fail_at("#{r.id}: \"Enforced by\" names no script and no CI job id, so nothing shows a required check " \
                'decides it (_common.md)', file: file, line: r.line)
        next
      end
      scripts.select { |s| exists_named?(s) }.each do |s|
        gap = enforced_script_gap(s, jobs, required)
        fail_at("#{r.id}: \"Enforced by\" #{gap}: it is at most \"Backed by\" (_common.md)", file: file, line: r.line) if gap
      end
      ids.each do |id|
        next if jobs.any? { |j| j[:job_id] == id && decides?(j, required) }

        fail_at("#{r.id}: \"Enforced by\" names the job #{id}, which is no required check run on a pull request: " \
                'it is at most "Backed by" (_common.md)', file: file, line: r.line)
      end
    end
  end
end

def check_measure_words(file, text)
  return if File.basename(file) == MEASURE_EXEMPT

  text.each_line.with_index(1) do |l, n|
    next unless (m = l.match(MEASURE_PHRASES))

    fail_at("names the seeded-defect measure (\"#{m[0]}\"): every reviewer of the dimension reads this file on every " \
            'run (ADR-0044)', file: file, line: n)
  end
end

def rule_file_of(id, dimension)
  if id.start_with?('FAC-S') then [dimension == 'security', MEASURE_EXEMPT]
  elsif id.start_with?('FAC-A') then [dimension == 'architecture', MEASURE_EXEMPT]
  else [!id.start_with?('FAC-'), "#{dimension}.md"]
  end
end

def check_patterns(rules_by_file)
  text = read(PATTERNS) or return
  rows = 0
  text.each_line.with_index(1) do |l, n|
    next unless (m = l.match(/\A\| (P-\d+) \| ([a-z0-9]+) \| ([^|]+?) \| (.*) \|\s*\z/))

    rows += 1
    pattern, dimension, id, defect = m.captures
    next if defect.start_with?('retired')

    unless id.match?(/\A#{ID}\z/)
      fail_at("#{pattern} cites \"#{id}\", which is no rule id", file: PATTERNS, line: n)
      next
    end
    fits, file = rule_file_of(id, dimension)
    unless fits
      fail_at("#{pattern} is #{dimension}'s, and #{id} is no rule of #{dimension}", file: PATTERNS, line: n)
      next
    end
    rule = rules_by_file.fetch(file, []).find { |r| r.id == id }
    if rule.nil?
      fail_at("#{pattern} cites #{id}, which #{RULES_DIR}/#{file} does not define", file: PATTERNS, line: n)
    elsif rule.retired
      fail_at("#{pattern} cites #{id}, which #{RULES_DIR}/#{file} retired", file: PATTERNS, line: n)
    end
  end
  fail_at("#{PATTERNS} holds no pattern row") if rows.zero?
end

rule_files = Dir.glob(File.join(ROOT, RULES_DIR, '*.md')).sort.map { |p| p.sub("#{ROOT}/", '') }
fail_at("no rule file in #{RULES_DIR}") if rule_files.empty?
required = required_checks || []
jobs = jobs_of_workflows
rules_by_file = {}
rule_files.each do |file|
  text = read(file) or next
  rules = rules_of(text)
  rules_by_file[File.basename(file)] = rules
  check_labels(file, rules, jobs, required)
  check_measure_words(file, text)
end
check_patterns(rules_by_file)

if $failures.positive?
  puts "#{$failures} claim(s) of the review rule files are not true (backlog #0-184)."
  exit 1
end
labels = rules_by_file.values.flatten.sum { |r| labels_of(r.text).size }
puts "The review rule files' #{labels} label(s), their words and the patterns' rule ids hold."
