# ============================================================
# Mail in the Kubernetes manifests (backlog #0-42, ADR-0026).
#
# Reads the rendered manifests of base and the three overlays (kubectl kustomize
# output) and fails when:
#   1. dev has no Deployment and ClusterIP Service named mailpit in
#      incident-platform-dev, the Service does not expose SMTP 1025 and HTTP 8025,
#      or its selector does not match the Deployment's pod labels;
#   2. dev's app-config does not point MAIL_HOST / MAIL_PORT at that Service;
#   3. dev's app-config does not set the three SPRING_MAIL_PROPERTIES_* overrides
#      (SMTP auth, STARTTLS enable, STARTTLS required) to "false";
#   4. base, staging or prod contain a mailpit resource, a SPRING_MAIL_PROPERTIES_*
#      key anywhere (any ConfigMap or Secret, a container env), or a MAIL_HOST that
#      points at a dev catcher (mailpit, mailhog);
#   5. the Mailpit image has no explicit tag, uses latest, or differs from
#      docker/docker-compose.yml's mailpit image;
#   6. the rendered dev output still says "mailhog";
#   7. anything in any overlay exposes Mailpit beyond the cluster: a Service of
#      another type or with a nodePort, a hostPort, or an Ingress backend pointing
#      at any Service that selects Mailpit's pods (whatever its name);
#   8. dev's JVM patches (768Mi, 90/120 s probe delays) reach a Deployment that is
#      not one of the services (component other than backend), or miss a service.
# Parsing is structural (YAML), not grep: a Service's selector, a Deployment's
# labels and a patch's target cannot be told apart in text.
#
# Run by the validate-k8s-manifests job in .github/workflows/ci.yml after the
# render steps; locally, from the repository root, with the four renders in DIR:
#   ruby .github/scripts/check-k8s-mail.rb [DIR]       (default /tmp, files <env>-rendered.yml)
# Tested by .github/scripts/test-k8s-mail.sh, which mutates a copy of the tree
# once per rule.
# ============================================================
require "yaml"

DIR = ARGV[0] || "/tmp"
COMPOSE = ENV.fetch("COMPOSE_FILE_PATH", "docker/docker-compose.yml")
ENVS = %w[base dev staging prod].freeze
DEV_NAMESPACE = "incident-platform-dev".freeze
SMTP_PORT = 1025
HTTP_PORT = 8025
OVERRIDES = %w[
  SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH
  SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE
  SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED
].freeze
# The dev overlay's JVM patch values (k8s/overlays/dev/kustomization.yml): rule 8 uses them to tell which
# Deployments the patch reached. A deliberate change to that patch must change these two lines too.
JVM_MEMORY = "768Mi".freeze
JVM_DELAYS = [90, 120].freeze

$errors = []
def fail!(msg)
  $errors << msg
end

# kustomize separates documents with "---" lines; each is parsed on its own with
# safe_load, which works the same on the Psych of Ruby 2.6 and 3.x.
def load_docs(path)
  text = File.read(path)
  raise "#{path} is empty" if text.strip.empty?
  text.split(/^---\s*$/).map { |d| YAML.safe_load(d) }.compact
end

def kind(docs, k)
  docs.select { |d| d["kind"] == k }
end

def named(docs, k, name)
  kind(docs, k).find { |d| d.dig("metadata", "name") == name }
end

def app_config(docs)
  (named(docs, "ConfigMap", "app-config") || {}).fetch("data", {}) || {}
end

def containers(deployment)
  deployment.dig("spec", "template", "spec", "containers") || []
end

rendered = {}
ENVS.each do |env|
  path = File.join(DIR, "#{env}-rendered.yml")
  unless File.file?(path)
    puts "::error::#{path} is missing; render the four kustomizations first (backlog #0-42)"
    exit 1
  end
  rendered[env] = load_docs(path)
end
dev = rendered["dev"]

# 1. the Deployment and the ClusterIP Service
deploy = named(dev, "Deployment", "mailpit")
service = named(dev, "Service", "mailpit")
if deploy.nil? || service.nil?
  fail!("dev has no Deployment and Service named mailpit")
else
  [deploy, service].each do |r|
    ns = r.dig("metadata", "namespace")
    fail!("dev #{r['kind']} mailpit is in namespace #{ns.inspect}, not #{DEV_NAMESPACE}") unless ns == DEV_NAMESPACE
  end
  type = service.dig("spec", "type") || "ClusterIP"
  fail!("dev Service mailpit is #{type}, not ClusterIP") unless type == "ClusterIP"
  ports = (service.dig("spec", "ports") || []).map { |p| p["port"] }
  [SMTP_PORT, HTTP_PORT].each { |p| fail!("dev Service mailpit does not expose port #{p}") unless ports.include?(p) }
  selector = service.dig("spec", "selector") || {}
  pod_labels = deploy.dig("spec", "template", "metadata", "labels") || {}
  if selector.empty? || !selector.all? { |k, v| pod_labels[k] == v }
    fail!("dev Service mailpit's selector #{selector.inspect} does not match the pod labels #{pod_labels.inspect}")
  end
end

# 2 and 3. dev's app-config
dev_config = app_config(dev)
fail!("dev MAIL_HOST is #{dev_config['MAIL_HOST'].inspect}, not the mailpit Service") unless dev_config["MAIL_HOST"] == "mailpit"
fail!("dev MAIL_PORT is #{dev_config['MAIL_PORT'].inspect}, not #{SMTP_PORT}") unless dev_config["MAIL_PORT"].to_s == SMTP_PORT.to_s
OVERRIDES.each do |key|
  fail!("dev app-config sets #{key} to #{dev_config[key].inspect}, not \"false\"") unless dev_config[key] == "false"
end

# 4. nothing of it outside dev
%w[base staging prod].each do |env|
  docs = rendered[env]
  docs.each do |d|
    fail!("#{env} contains #{d['kind']} mailpit") if d.dig("metadata", "name") == "mailpit"
  end
  # Anywhere: a key of any ConfigMap or Secret, or a container env name (review of #0-42: a check of
  # app-config alone would miss the same override set elsewhere).
  docs.each do |d|
    keys = []
    keys += (d["data"] || {}).keys + (d["stringData"] || {}).keys if %w[ConfigMap Secret].include?(d["kind"])
    (d.dig("spec", "template", "spec", "containers") || []).each { |c| keys += (c["env"] || []).map { |e| e["name"].to_s } }
    keys.grep(/\ASPRING_MAIL_PROPERTIES_/).each do |k|
      fail!("#{env} #{d['kind']} #{d.dig('metadata', 'name')} sets #{k}")
    end
  end
  config = app_config(docs)
  host = config["MAIL_HOST"].to_s
  fail!("#{env} MAIL_HOST is #{host.inspect}, a dev mail catcher") if host =~ /\A(mailpit|mailhog)\z/i
end

# 5. the image, pinned and equal to compose's
compose_image =
  begin
    compose = YAML.safe_load(File.read(COMPOSE), aliases: true)
    compose.dig("services", "mailpit", "image")
  rescue StandardError => e
    fail!("cannot read the mailpit image from #{COMPOSE}: #{e.message}")
    nil
  end
if deploy
  image = containers(deploy).map { |c| c["image"] }.find { |i| i.to_s.include?("mailpit") }
  tag = image.to_s[/:([^:\/@]+)\z/, 1]
  if image.nil?
    fail!("dev Deployment mailpit has no mailpit image")
  elsif tag.nil? || tag == "latest"
    fail!("dev Mailpit image #{image.inspect} has no explicit version tag")
  elsif compose_image && image != compose_image
    fail!("dev Mailpit image #{image} differs from #{COMPOSE}'s #{compose_image}; bump them together")
  end
end

# 6. no mailhog left in dev
dev.each do |d|
  fail!("dev #{d['kind']} #{d.dig('metadata', 'name')} still says mailhog") if YAML.dump(d) =~ /mailhog/i
end

# 7. Mailpit reachable only inside the cluster, in every overlay. A Service is Mailpit's when it is named
# mailpit or selects its pods; an Ingress reaches it when a backend names that Service.
%w[dev staging prod].each do |env|
  docs = rendered[env]
  mailpit_services = kind(docs, "Service").select do |s|
    s.dig("metadata", "name") == "mailpit" || (s.dig("spec", "selector") || {})["app"] == "mailpit"
  end
  mailpit_services.each do |s|
    name = s.dig("metadata", "name")
    type = s.dig("spec", "type") || "ClusterIP"
    fail!("#{env} Service #{name} exposes Mailpit as #{type}") unless type == "ClusterIP"
    fail!("#{env} Service #{name} gives Mailpit a nodePort") if (s.dig("spec", "ports") || []).any? { |p| p["nodePort"] }
  end
  mailpit = named(docs, "Deployment", "mailpit")
  if mailpit
    containers(mailpit).flat_map { |c| c["ports"] || [] }.each do |p|
      fail!("#{env} Deployment mailpit uses hostPort #{p['hostPort']}") if p["hostPort"]
    end
  end
  # Every backend service name in the Ingress (rules and defaultBackend), compared with Mailpit's Services.
  service_names = mailpit_services.map { |s| s.dig("metadata", "name") }
  kind(docs, "Ingress").each do |ing|
    spec = ing["spec"] || {}
    backends = [spec.dig("defaultBackend", "service", "name")]
    (spec["rules"] || []).each do |r|
      (r.dig("http", "paths") || []).each { |path| backends << path.dig("backend", "service", "name") }
    end
    (backends.compact & service_names).each do |svc|
      fail!("#{env} Ingress #{ing.dig('metadata', 'name')} routes to #{svc}, a Service of Mailpit")
    end
  end
end

# 8. the JVM patches reach the services only
kind(dev, "Deployment").each do |d|
  name = d.dig("metadata", "name")
  backend = d.dig("metadata", "labels", "app.kubernetes.io/component") == "backend"
  c = containers(d).first || {}
  jvm_memory = c.dig("resources", "limits", "memory") == JVM_MEMORY
  jvm_delays = [c.dig("readinessProbe", "initialDelaySeconds"), c.dig("livenessProbe", "initialDelaySeconds")] == JVM_DELAYS
  if backend
    fail!("dev service #{name} lost the JVM patch (#{JVM_MEMORY}, delays #{JVM_DELAYS.join('/')}); if the dev patch values changed on purpose, update JVM_MEMORY / JVM_DELAYS in this script") unless jvm_memory && jvm_delays
  elsif jvm_memory || jvm_delays
    fail!("dev #{name} (component #{d.dig('metadata', 'labels', 'app.kubernetes.io/component').inspect}) gets the JVM patch meant for the services")
  end
end
backends = kind(dev, "Deployment").count { |d| d.dig("metadata", "labels", "app.kubernetes.io/component") == "backend" }
fail!("dev has no Deployment labelled app.kubernetes.io/component=backend; the JVM patches target nothing") if backends.zero?

if $errors.empty?
  puts "Mail manifests: dev catches mail in Mailpit (ClusterIP, #{deploy && containers(deploy).first['image']}); base, staging and prod have no catcher and no overrides."
  exit 0
end
$errors.each { |e| puts "::error::#{e} (backlog #0-42)" }
puts "#{$errors.size} mail manifest problem(s)."
exit 1
