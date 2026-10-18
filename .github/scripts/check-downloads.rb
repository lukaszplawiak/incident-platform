# ============================================================
# A tool CI downloads is installed by install-pinned-tool.sh only (ADR-0033, backlog #0-149).
#
# ADR-0033 pins every CI tool as a release tag and its SHA-256 under a `# renovate:` comment, and
# install-pinned-tool.sh is the one place that downloads one, checking the hash before anything runs. Until
# #0-149 nothing held the second half: a new `curl … | tar` in a `run:` block passed CI, and
# check-pinned-tools.sh sees only the `*_SHA256` lines, so a download without one was never compared to its
# publisher. This refuses, in every `run:` block of .github/workflows/*.y(a)ml and of the composite actions
# under .github/actions/, a line that names curl or wget, a release asset's URL (`releases/download/`) or
# `gh release download`, unless the same line says why it is not a download of a tool:
#
#     status=$(curl -s … "http://localhost:$port/actuator/health")  # not-a-download: <why>
#
# (today the smoke test's health probe, ci.yml). A shell comment counts as a line too: reword it or mark it.
#
# What it does not see, left to review (the workflows are the owner's, CODEOWNERS): a download by another
# program (python, a `docker run`, an action from `uses:` that fetches its own binary, #0-62), and what a
# script the step calls does (install-pinned-tool.sh itself runs curl), and a composite action anywhere but
# .github/actions/<name>/action.y(a)ml (one level, where every local action is today). It is a check against
# the honest mistake of a download the pins do not cover, not a boundary.
#
# A file that cannot be read as YAML fails the check. Run from the repository root (CI: lint-workflows,
# after its own cases in test-check-downloads.sh), or with the root as the only argument. Portable to Ruby 2.6.
# ============================================================
require_relative 'run-blocks'

ROOT = File.expand_path(ARGV[0] || '.')
DOWNLOAD = %r{(?<![\w.-])(curl|wget)(?![\w-])|releases/download/|\bgh\s+release\s+download\b}.freeze
# The reason is required: a bare marker says nothing to the reviewer.
EXCEPTION = /#\s*not-a-download:\s*\S/.freeze

$errors = 0

def fail!(file, msg, line: nil)
  puts Annotation.error("#{msg} (backlog #0-149)", file: file, line: line)
  $errors += 1
end

workflows = RunBlocks.workflows(ROOT)
actions = RunBlocks.actions(ROOT)
if workflows.empty?
  puts Annotation.error("no workflow found under #{ROOT}/.github/workflows: run from the repository root (backlog #0-149)")
  exit 1
end

blocks, unreadable = RunBlocks.of(ROOT, workflows + actions)
unreadable.each { |file, msg| fail!(file, msg) }
blocks.each do |b|
  b.text.lines.each_with_index do |text, i|
    found = text[DOWNLOAD]
    next unless found
    next if text =~ EXCEPTION
    # A folded block's lines are joined, so only its first line maps back to the file.
    line = b.folded ? b.line : b.line + i
    fail!(b.file, "step '#{b.step}' runs `#{found}`: a tool is downloaded by .github/scripts/install-pinned-tool.sh " \
                  'only, pinned under a `# renovate:` comment (ADR-0033); anything else ends the line with ' \
                  '`# not-a-download: <why>`', line: line)
  end
end

exit 1 if $errors > 0
puts "Checked #{blocks.size} run blocks in #{workflows.size} workflows and #{actions.size} actions: no download outside install-pinned-tool.sh."
