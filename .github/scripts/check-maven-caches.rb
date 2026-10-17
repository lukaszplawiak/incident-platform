# ============================================================
# The Maven caches are restored and saved only through .github/actions/restore-maven-caches and
# save-maven-caches, and keep what #0-142 decided (backlog #0-145, ADR-0031).
#
# Until #0-145 each of the four jobs that run ./mvnw (ci.yml's build-and-test, snyk.yml, both jobs of
# owasp-dependency-check.yml) carried its own copy of both caches' paths and keys, kept equal by comments;
# a drift was a silent miss, the whole tree from Maven Central again. The two actions hold the only copy
# now. What this refuses, reading every .github/workflows/*.y(a)ml and .github/actions/*/action.y(a)ml:
#
#   1. a cache step in a workflow (actions/cache, actions/cache/restore, actions/cache/save, or
#      actions/setup-java with `cache: maven`) whose path is ~/.m2, ~/.m2/repository, ~/.m2/wrapper or
#      anything under ~/.m2 but OWASP Dependency-Check's own data (owasp-dependency-check.yml caches its
#      database under ~/.m2/repository/org/owasp/): only the two actions cache the Maven files. Not any
#      subdirectory of the repository: a cache of ~/.m2/repository/org/springframework would be a second
#      copy of part of the repository cache, the drift this rule removes. Another such cache needs this
#      allowance widened, in review;
#   2. a cache step in an action other than those two that does the same;
#   3. a step of the two actions with ~/.m2/wrapper (or a path under it) whose path list is not exactly
#      ~/.m2/wrapper, or that has restore-keys: mvnw never checks a distribution it finds unpacked, so it
#      is restored by its exact key alone (#0-142), never with the repository, never by prefix;
#   4. a path of the two actions that is ~ or ~/.m2 itself, or a glob there: it would cache ~/.m2/wrapper
#      with the repository;
#   5. a restore and a save whose path lists differ for the same cache, or a cache missing from either
#      action: the path list is part of an entry's version, so a save under another one is never restored;
#   6. a distribution key whose hashFiles leaves out one of the files that decide what is checked and how
#      it is cached (the properties, check-maven-wrapper-sum.sh, both actions): a change to that file
#      would restore the old entry instead of checking again.
#
# What it does not see, each left to review: a cache action other than actions/cache* or a `run:` step that
# archives ~/.m2 itself (a restore by prefix of the distribution would be back; #0-142's check of a download
# does not depend on this checker); a save-maven-caches call without ci.yml's `if: github.event_name ==
# 'push'` (harmless for main: a pull request's entries are scoped to its own ref, which main, snyk and owasp
# never read); a new job that runs ./mvnw without restore-maven-caches (only a miss: it downloads the tree and
# the distribution, which mvnw checks).
#
# A file that cannot be read or parsed fails the check; so does a missing action. Paths are compared after
# turning $HOME/, ${HOME}/, ${{ env.HOME }}/ and /home/runner/ into ~/ and dropping a trailing slash.
# Run from the repository root (CI: build-and-test, after its own cases in test-maven-caches.sh), or
# with the root as the only argument. Portable to Ruby 2.6.
# ============================================================
require 'yaml'

ROOT = ARGV[0] || '.'
RESTORE = '.github/actions/restore-maven-caches/action.yml'
SAVE = '.github/actions/save-maven-caches/action.yml'
WRAPPER_KEY_FILES = [
  '.mvn/wrapper/maven-wrapper.properties',
  '.github/scripts/check-maven-wrapper-sum.sh',
  RESTORE,
  SAVE
].freeze

$errors = 0

def fail!(file, msg)
  puts "::error file=#{file}::#{msg} (backlog #0-145)"
  $errors += 1
end

def load_yaml(file)
  doc = YAML.safe_load(File.read(File.join(ROOT, file)), aliases: true)
  return doc if doc.is_a?(Hash)
  fail!(file, "#{file} is not a YAML mapping")
  nil
rescue StandardError => e
  fail!(file, "#{file} cannot be read as YAML: #{e.message.lines.first.to_s.strip}")
  nil
end

def normalize(path)
  p = path.strip
  p = p.sub(%r{\A(\$HOME|\$\{HOME\}|\$\{\{\s*env\.HOME\s*\}\}|/home/runner)(?=/|\z)}, '~')
  p = p.chomp('/') while p.length > 1 && p.end_with?('/')
  p
end

def paths_of(step)
  with = step['with'].is_a?(Hash) ? step['with'] : {}
  with['path'].to_s.lines.map { |l| normalize(l) }.reject { |l| l.empty? || l.start_with?('!') }
end

def cache_step?(step)
  step['uses'].to_s =~ %r{\Aactions/cache(/restore|/save)?@}
end

def maven_setup_java?(step)
  return false unless step['uses'].to_s.start_with?('actions/setup-java@')
  with = step['with'].is_a?(Hash) ? step['with'] : {}
  with['cache'].to_s.strip == 'maven'
end

def glob?(path)
  path =~ /[*?\[{]/
end

def wrapper?(path)
  path == '~/.m2/wrapper' || path.start_with?('~/.m2/wrapper/')
end

# ~ or ~/.m2 itself, or a glob that can match ~/.m2/wrapper (the text before its first glob character is a
# start of "~/.m2/wrapper": ~/*, ~/.m*, ~/.m2/*; not ~/.cache/* nor ~/.m2/repository/*).
def ancestor?(path)
  return true if ['~', '~/.m2'].include?(path)
  return false unless glob?(path)
  literal = path[0, path.index(/[*?\[{]/)]
  '~/.m2/wrapper'.start_with?(literal)
end

# The one cache under ~/.m2 that is not Maven's: OWASP Dependency-Check's database (rule 1).
OWASP_DATA = '~/.m2/repository/org/owasp/'

# A path that caches the Maven files: anything under ~/.m2 except OWASP Dependency-Check's data.
def maven_path?(path)
  return true if ancestor?(path)
  return false unless path == '~/.m2' || path.start_with?('~/.m2/')
  !path.start_with?(OWASP_DATA)
end

def steps_of(doc, file)
  if file.start_with?('.github/actions/')
    runs = doc['runs'].is_a?(Hash) ? doc['runs'] : {}
    Array(runs['steps']).select { |s| s.is_a?(Hash) }
  else
    jobs = doc['jobs'].is_a?(Hash) ? doc['jobs'] : {}
    jobs.values.select { |j| j.is_a?(Hash) }.flat_map { |j| Array(j['steps']).select { |s| s.is_a?(Hash) } }
  end
end

def label(step)
  step['name'] || step['id'] || step['uses']
end

workflows = Dir.glob(File.join(ROOT, '.github/workflows/*.{yml,yaml}')).sort
actions = Dir.glob(File.join(ROOT, '.github/actions/*/action.{yml,yaml}')).sort
rel = ->(f) { f.sub(%r{\A#{Regexp.escape(ROOT)}/?}, '') }

if workflows.empty?
  puts "::error::no workflow found under #{ROOT}/.github/workflows: run from the repository root (backlog #0-145)"
  exit 1
end

# Rules 1 and 2: nothing but the two actions caches the Maven files.
(workflows + actions).map(&rel).each do |file|
  next if [RESTORE, SAVE].include?(file)
  doc = load_yaml(file) or next
  steps_of(doc, file).each do |step|
    if maven_setup_java?(step)
      fail!(file, "step '#{label(step)}' uses setup-java's `cache: maven`: the Maven caches go through #{RESTORE} only")
    elsif cache_step?(step)
      hits = paths_of(step).select { |p| maven_path?(p) }
      next if hits.empty?
      fail!(file, "step '#{label(step)}' caches #{hits.join(', ')}: the Maven caches go through #{RESTORE} and #{SAVE} only")
    end
  end
end

# Rules 3 to 6: what the two actions cache, and how.
lists = { restore: { wrapper: [], repository: [] }, save: { wrapper: [], repository: [] } }
{ restore: RESTORE, save: SAVE }.each do |side, file|
  unless File.file?(File.join(ROOT, file))
    fail!(file, "#{file} is missing: the Maven caches have no other place")
    next
  end
  doc = load_yaml(file) or next
  steps_of(doc, file).select { |s| cache_step?(s) }.each do |step|
    paths = paths_of(step)
    with = step['with'].is_a?(Hash) ? step['with'] : {}
    paths.select { |p| ancestor?(p) }.each do |p|
      fail!(file, "step '#{label(step)}' caches #{p}: that takes ~/.m2/wrapper with it, which is cached apart by its exact key (#0-142)")
    end
    if paths.any? { |p| wrapper?(p) }
      if paths != ['~/.m2/wrapper']
        fail!(file, "step '#{label(step)}' caches #{paths.join(', ')}: the Maven distribution's path list must be exactly ~/.m2/wrapper (#0-142)")
      end
      unless with['restore-keys'].to_s.strip.empty?
        fail!(file, "step '#{label(step)}' restores ~/.m2/wrapper with restore-keys: mvnw never checks a distribution it finds unpacked, so only its exact key may restore it (#0-142)")
      end
      lists[side][:wrapper] << paths
      if side == :restore
        key = with['key'].to_s
        named = key.scan(/hashFiles\(([^)]*)\)/).flatten.join(',').scan(/'([^']*)'|"([^"]*)"/).flatten.compact
        missing = WRAPPER_KEY_FILES - named
        unless missing.empty?
          fail!(file, "the Maven distribution's key does not hash #{missing.join(', ')}: a change to it would restore the old entry instead of checking again")
        end
      end
    elsif paths.include?('~/.m2/repository')
      lists[side][:repository] << paths
    end
  end
end

[:wrapper, :repository].each do |cache|
  r = lists[:restore][cache]
  s = lists[:save][cache]
  name = cache == :wrapper ? 'the Maven distribution' : 'the Maven repository'
  fail!(RESTORE, "#{RESTORE} restores #{name} #{r.size} times, expected once") unless r.size == 1
  fail!(SAVE, "#{SAVE} saves #{name} #{s.size} times, expected once") unless s.size == 1
  next unless r.size == 1 && s.size == 1
  next if r.first.sort == s.first.sort
  fail!(SAVE, "#{name} is saved with path #{s.first.join(', ')} but restored with #{r.first.join(', ')}: the path list is part of an entry's version, so the saved entry would never be restored")
end

exit 1 if $errors > 0
puts "Checked #{workflows.size} workflows and #{actions.size} actions: the Maven caches go through #{File.basename(File.dirname(RESTORE))} and #{File.basename(File.dirname(SAVE))} only."
