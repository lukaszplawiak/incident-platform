# ============================================================
# shellcheck of every tracked .sh file and of every composite action's run steps (backlog #0-149).
#
# Until #0-149 CI ran shellcheck only through actionlint, on the workflows' own `run:` blocks: actionlint
# reads no .sh file and does not lint a composite action's steps, so the CI checks and their tests
# (.github/scripts/), the autopilot's gates (scripts/factory/) and the agents' hooks (.claude/hooks/, the shell
# guards of #0-130/#0-132) were never checked. This runs the pinned shellcheck of the lint-workflows job on
# both, and fails on any finding:
#
#   1. every file `git ls-files '*.sh'` lists (mvnw is not one: it is Apache's, checked by its SHA-256,
#      ADR-0031), with --external-sources and --source-path=SCRIPTDIR, so a library a script sources is
#      read with it rather than reported as not followed (SC1091);
#   2. every `run:` step of a composite action under .github/actions/ whose shell is bash or sh, its
#      `${{ … }}` expressions replaced by as many underscores (actionlint does the same for the workflows),
#      each finding reported at its line in action.yml.
#
# Every severity counts, notes and style included: SC2086 (a variable unquoted, split and globbed) is a note,
# and it is the one that matters most in the guards. A finding that is meant is disabled where it is, with
# its reason on the line above (`# shellcheck disable=SC… `), never by a global exclude: --norc, so a
# .shellcheckrc anywhere in the repository (no protected path, and shellcheck looks for one upwards from
# every script) cannot turn a check off.
#
# What it does not see, left to review: a shell script without the .sh extension, a step in a shell other
# than bash or sh (skipped, and counted in the summary), what a workflow's `run:` block calls besides
# itself (actionlint lints the block; the script it calls is linted here only if it is a tracked .sh file),
# and a composite action anywhere but .github/actions/<name>/action.y(a)ml (one level: deeper, or elsewhere,
# its steps are not read; every local action is there today).
#
# A shellcheck that cannot run, a file that cannot be read and an action that is not YAML all fail the check.
# Usage: ruby check-shell.rb <shellcheck> [<repository root>] (CI: lint-workflows, after its own cases in
# test-check-shell.sh). Runs on the Ruby of .ruby-version (ADR-0040).
# ============================================================
require 'json'
require 'open3'
require_relative 'run-blocks'

SHELLCHECK = ARGV[0].to_s
ROOT = File.expand_path(ARGV[1] || '.')
FLAGS = %w[--norc --external-sources --source-path=SCRIPTDIR --severity=style --format=json1].freeze

$errors = 0

def fail!(file, msg, line: nil, col: nil)
  puts Annotation.error("#{msg} (backlog #0-149)", file: file, line: line, col: col)
  $errors += 1
end

def stop!(msg)
  puts Annotation.error("#{msg} (backlog #0-149)")
  exit 1
end

def finding(c)
  "SC#{c['code']} (#{c['level']}): #{c['message']}"
end

# shellcheck's own verdict: 0 no finding, 1 findings; any other exit status (or output that is not its
# JSON) means it did not check, and is a failure, not a pass.
def shellcheck(args, input: nil)
  out, err, status = Open3.capture3(SHELLCHECK, *FLAGS, *args, stdin_data: input.to_s, chdir: ROOT)
  unless [0, 1].include?(status.exitstatus)
    stop!("shellcheck exited #{status.exitstatus.inspect}: #{err.lines.first.to_s.strip}")
  end
  JSON.parse(out).fetch('comments')
rescue SystemCallError => e
  stop!("cannot run shellcheck #{SHELLCHECK}: #{e.message}")
rescue JSON::ParserError, KeyError, TypeError
  stop!("shellcheck printed no JSON report: #{out.to_s.lines.first.to_s.strip}")
end

stop!('usage: check-shell.rb <shellcheck> [<repository root>]') if SHELLCHECK.empty?

files, err, status = Open3.capture3('git', '-C', ROOT, 'ls-files', '-z', '--', '*.sh')
stop!("git ls-files failed in #{ROOT}: #{err.lines.first.to_s.strip}") unless status.success?
files = files.split("\0")
stop!("no tracked .sh file under #{ROOT}: run from the repository root") if files.empty?

# 1. The scripts.
shellcheck(['--', *files]).each do |c|
  fail!(c['file'], finding(c), line: c['line'], col: c['column'])
end

# 2. The composite actions' steps.
blocks, unreadable = RunBlocks.of(ROOT, RunBlocks.actions(ROOT))
unreadable.each { |file, msg| fail!(file, msg) }
checked = 0
skipped = 0
blocks.each do |b|
  if b.shell.to_s.strip.empty?
    fail!(b.file, "step '#{b.step}' has no shell: a composite action's run step must name one", line: b.line)
    next
  end
  shell = File.basename(b.shell.split.first)
  unless %w[bash sh].include?(shell)
    skipped += 1
    next
  end
  checked += 1
  text = b.text.gsub(/\$\{\{.*?\}\}/) { |m| '_' * m.length }
  shellcheck(["--shell=#{shell}", '-'], input: text).each do |c|
    # A folded block's lines are joined, so only its first line maps back to the file.
    line = b.folded ? b.line : b.line + c['line'] - 1
    col = b.column && c['column'] + b.column
    fail!(b.file, "step '#{b.step}': #{finding(c)}", line: line, col: col)
  end
end

exit 1 if $errors > 0
note = skipped > 0 ? " (#{skipped} in another shell not checked)" : ''
puts "shellcheck: #{files.size} script(s) and #{checked} composite action step(s)#{note}, no finding."
