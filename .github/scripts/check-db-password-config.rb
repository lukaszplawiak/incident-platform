# ============================================================
# No service has a default database password (backlog #0-66).
#
# Reads every committed Spring config file under */src/main/resources and
# */src/main/resources/config (application*.yml, .yaml, .properties: the two
# classpath locations Spring Boot loads) and fails when:
#   - a password under spring.datasource (any depth: password, hikari.password,
#     ...) or spring.flyway is anything but a ${VAR} placeholder with no
#     default; spring.datasource.password itself must be exactly ${DB_PASSWORD};
#   - spring.datasource.url carries a password parameter;
#   - a service sets spring.datasource.url but not spring.datasource.password;
#   - no file sets spring.datasource.password at all (the check would pass
#     vacuously).
# Keys are compared the way Spring's relaxed binding does: case-insensitive,
# '-' and '_' ignored, nested and dotted forms alike. The gitignored
# application-local files are not committed, so they are not checked.
#
# Run by the postgres-roles job in .github/workflows/ci.yml; locally, from the
# repository root: ruby .github/scripts/check-db-password-config.rb
# Tested against bypass cases in .github/scripts/test-db-password-config.sh.
# ============================================================
require "yaml"

CANONICAL = "${DB_PASSWORD}".freeze
PLACEHOLDER = /\A\$\{[A-Za-z0-9_.\-]+\}\z/ # ${VAR}, no ":default"

def normalize(path)
  path.downcase.delete("-_")
end

# Flattens a parsed YAML document into [dotted path, value] pairs. A key may
# itself contain dots ("datasource.password"), so paths are joined as strings
# and normalized afterwards.
def flatten(node, prefix = nil, out = [])
  case node
  when Hash
    node.each { |k, v| flatten(v, prefix ? "#{prefix}.#{k}" : k.to_s, out) }
  when Array
    node.each_with_index { |v, i| flatten(v, "#{prefix}[#{i}]", out) }
  else
    out << [prefix.to_s, node] if prefix
  end
  out
end

# java.util.Properties: '#'/'!' comments, a trailing backslash continues the
# line, and the key ends at the first unescaped '=', ':' or whitespace.
def parse_properties(text)
  logical = []
  buffer = +""
  text.each_line do |raw|
    line = raw.chomp
    line = line.lstrip if buffer.empty?
    next if buffer.empty? && (line.empty? || line.start_with?("#", "!"))
    if line =~ /(\\+)\z/ && Regexp.last_match(1).length.odd?
      buffer << line[0...-1]
    else
      logical << (buffer + line)
      buffer = +""
    end
  end
  logical << buffer unless buffer.empty?
  logical.filter_map do |l|
    m = l.match(/\A((?:\\.|[^=:\s\\])+)\s*(?:[=:]\s*|\s+)?(.*)\z/)
    m && [m[1].gsub(/\\(.)/, '\1'), m[2]]
  end
end

def entries(file)
  if file.end_with?(".properties")
    parse_properties(File.read(file))
  else
    YAML.load_stream(File.read(file)).flat_map do |doc|
      doc.is_a?(Hash) ? flatten(doc) : [] # a non-map document sets nothing
    end
  end
end

# Both classpath locations Spring Boot loads, in every module. Arguments, if
# given, replace the git listing (used by the bypass test).
PATHSPECS = %w[yml yaml properties].flat_map do |ext|
  ["*/src/main/resources/application*.#{ext}", "*/src/main/resources/config/application*.#{ext}"]
end.freeze

files =
  if ARGV.empty?
    listed = IO.popen(["git", "ls-files", "--", *PATHSPECS], &:read)
    abort "::error::git ls-files failed" unless $?.success?
    listed.split("\n")
  else
    ARGV
  end
abort "::error::no Spring config files found; the check would pass vacuously" if files.empty?

errors = []
services_with_url = {}
services_with_password = {}
password_count = 0

files.each do |file|
  service = file.split("/").first
  entries(file).each do |path, value|
    key = normalize(path)
    text = value.to_s

    if key == "spring.datasource.password"
      password_count += 1
      services_with_password[service] = true
      errors << "#{file}: spring.datasource.password = #{text.inspect}, must be exactly #{CANONICAL}" unless text == CANONICAL
    elsif (key.start_with?("spring.datasource.") || key.start_with?("spring.flyway.")) && key.end_with?("password")
      errors << "#{file}: #{path} = #{text.inspect}, must be a ${VAR} placeholder with no default" unless text.match?(PLACEHOLDER)
    end

    if key == "spring.datasource.url"
      services_with_url[service] = true
      errors << "#{file}: spring.datasource.url carries a password parameter" if text.match?(/[?&;]password=/i)
    end
  end
end

(services_with_url.keys - services_with_password.keys).sort.each do |service|
  errors << "#{service}: sets spring.datasource.url but not spring.datasource.password"
end
errors << "no file sets spring.datasource.password; the check would pass vacuously" if password_count.zero?

unless errors.empty?
  puts errors
  abort "::error::database password config violates backlog #0-66 (see above)"
end
puts "Database password config OK: #{password_count} spring.datasource.password entries, all #{CANONICAL}; " \
     "#{services_with_url.size} services with a datasource; #{files.size} files."
