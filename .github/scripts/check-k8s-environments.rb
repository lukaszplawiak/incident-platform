# ============================================================
# Each Kubernetes overlay deploys its own environment (backlog #0-29), and the HPAs own the services'
# replica counts (backlog #0-137).
#
# From the first commit of the manifests until #0-29, k8s/overlays/staging/kustomization.yml held the
# prod overlay's body and k8s/overlays/prod/ the staging one's: "deploying prod" rendered every resource
# into incident-platform-staging with images tagged `staging`, and the reverse. Kustomize's top-level
# `namespace:` overrides what each resource declares, so the secrets.yml beside each file, which were
# right, could not show it. This reads the rendered overlays (kubectl kustomize output) and fails when:
#   1. a resource of the dev, staging or prod render is in a namespace other than
#      incident-platform-<overlay>, the render has no Namespace object of that name or another
#      Namespace object, or nothing at all is in that namespace;
#   2. an image in the prod render has no tag (or digest), or the tag `latest`;
#   3. an image of a service (a Deployment labelled app.kubernetes.io/component=backend) in the prod
#      render is tagged with an environment's name (dev, staging, prod) rather than a release, or the
#      render has no such Deployment;
#   4. in any render, a service Deployment has no HorizontalPodAutoscaler, an HPA targets a Deployment the
#      render does not have, the targeted Deployment sets spec.replicas, or minReplicas exceeds maxReplicas;
#   5. an HPA in the prod render has a floor (minReplicas, default 1) below 2.
# Rules 4 and 5 (backlog #0-137): until #0-137 the overlays sized the services with spec.replicas patches
# while each HPA ran from minReplicas 1, so the HPA scaled prod's "3 replicas" down to 1 and every
# `kubectl apply` reset what it had scaled to (Kubernetes docs, "Migrating Deployments and StatefulSets to
# horizontal autoscaling": remove spec.replicas once an HPA is enabled); auth-service ran one replica in
# prod. With spec.replicas gone, a service without an HPA would silently run one pod, hence the first
# clause of rule 4.
# Parsing is structural (YAML), not grep, as check-k8s-mail.rb (backlog #0-42): a namespace field and an
# image name cannot be told from their surroundings in text.
#
# Run by the validate-k8s-manifests job in .github/workflows/ci.yml after the render steps; locally, from
# the repository root, with the renders in DIR:
#   ruby .github/scripts/check-k8s-environments.rb [DIR]   (default /tmp, files <env>-rendered.yml)
# Tested by .github/scripts/test-k8s-environments.sh, which mutates a copy of the tree once per rule.
# ============================================================
require "yaml"

DIR = ARGV[0] || "/tmp"
OVERLAYS = %w[dev staging prod].freeze
ENVIRONMENT_NAMES = OVERLAYS
NAMESPACE_PREFIX = "incident-platform-".freeze

$errors = []
def fail!(msg, item = "#0-29")
  $errors << "#{msg} (backlog #{item})"
end

# kustomize separates documents with "---" lines; each is parsed on its own with
# safe_load, which works the same on the Psych of Ruby 2.6 and 3.x.
def load_docs(path)
  text = File.read(path)
  raise "#{path} is empty" if text.strip.empty?
  text.split(/^---\s*$/).map { |d| YAML.safe_load(d) }.compact
end

def label(doc)
  "#{doc['kind']} #{doc.dig('metadata', 'name')}"
end

# Every container and init container of any pod template in the document (Deployment, StatefulSet, Job,
# CronJob, a bare Pod), found by walking it rather than by naming each kind's path.
def containers_in(node, found = [])
  case node
  when Hash
    node.each do |key, value|
      if %w[containers initContainers].include?(key) && value.is_a?(Array)
        found.concat(value.select { |c| c.is_a?(Hash) })
      else
        containers_in(value, found)
      end
    end
  when Array
    node.each { |v| containers_in(v, found) }
  end
  found
end

# The tag of an image reference, or nil when it has none; a digest (@sha256:...) counts as one. A tag is
# mutable (a registry push can move it), so this checks the shape of the reference, not immutability (#0-71).
def tag_of(image)
  return :digest if image.include?("@")
  last = image.split("/").last
  last.include?(":") ? last.split(":").last : nil
end

rendered = {}
OVERLAYS.each do |env|
  path = File.join(DIR, "#{env}-rendered.yml")
  unless File.file?(path)
    puts "::error::#{path} is missing; render the overlays first (backlog #0-29)"
    exit 1
  end
  rendered[env] = load_docs(path)
end

# 1. every overlay in its own namespace
OVERLAYS.each do |env|
  expected = "#{NAMESPACE_PREFIX}#{env}"
  docs = rendered[env]
  docs.each do |d|
    ns = d.dig("metadata", "namespace")
    fail!("#{env} renders #{label(d)} into namespace #{ns}, not #{expected}") if ns && ns != expected
  end
  namespaces = docs.select { |d| d["kind"] == "Namespace" }.map { |d| d.dig("metadata", "name") }
  fail!("#{env} has no Namespace #{expected}") unless namespaces.include?(expected)
  (namespaces - [expected]).each { |n| fail!("#{env} creates Namespace #{n}, not only #{expected}") }
  fail!("#{env} renders nothing into #{expected}") if docs.none? { |d| d.dig("metadata", "namespace") == expected }
end

# 2 and 3. prod's images: tagged (not latest), and a release rather than an environment for the services
prod = rendered["prod"]
services = prod.select do |d|
  d["kind"] == "Deployment" && d.dig("metadata", "labels", "app.kubernetes.io/component") == "backend"
end
fail!("prod has no Deployment labelled app.kubernetes.io/component=backend; the service image rule checks nothing") if services.empty?
prod.each do |d|
  backend = services.include?(d)
  containers_in(d).each do |c|
    image = c["image"].to_s
    tag = tag_of(image)
    if tag.nil?
      fail!("prod #{label(d)} runs #{image.inspect} with no tag")
    elsif tag == "latest"
      fail!("prod #{label(d)} runs #{image}, tagged latest")
    elsif backend && ENVIRONMENT_NAMES.include?(tag)
      fail!("prod #{label(d)} runs #{image}, tagged with an environment's name instead of a release")
    end
  end
end

# 4. the HPAs own the services' replica counts, in every render
OVERLAYS.each do |env|
  docs = rendered[env]
  hpas = docs.select { |d| d["kind"] == "HorizontalPodAutoscaler" }
  # Deployments an HPA aims at, by kind and name (two documents never share both in one render)
  targeted = {}
  hpas.each do |h|
    ref = h.dig("spec", "scaleTargetRef") || {}
    target = docs.find { |d| d["kind"] == ref["kind"] && d.dig("metadata", "name") == ref["name"] }
    if target.nil?
      fail!("#{env} #{label(h)} targets #{ref['kind']} #{ref['name']}, which the render does not have", "#0-137")
      next
    end
    targeted["#{target['kind']}/#{target.dig('metadata', 'name')}"] = h
    unless target.dig("spec", "replicas").nil?
      fail!("#{env} #{label(target)} sets spec.replicas (#{target.dig('spec', 'replicas')}) although #{label(h)} owns its count", "#0-137")
    end
    min = h.dig("spec", "minReplicas") || 1
    max = h.dig("spec", "maxReplicas")
    fail!("#{env} #{label(h)} has minReplicas #{min} above maxReplicas #{max}", "#0-137") if max && min > max
  end
  docs.each do |d|
    next unless d["kind"] == "Deployment" && d.dig("metadata", "labels", "app.kubernetes.io/component") == "backend"
    fail!("#{env} #{label(d)} has no HorizontalPodAutoscaler, so it runs one pod", "#0-137") unless targeted.key?("#{d['kind']}/#{d.dig('metadata', 'name')}")
  end
end

# 5. prod never runs a service on a single pod
prod.select { |d| d["kind"] == "HorizontalPodAutoscaler" }.each do |h|
  min = h.dig("spec", "minReplicas") || 1
  fail!("prod #{label(h)} has minReplicas #{min}; prod's floor is 2", "#0-137") if min < 2
end

if $errors.empty?
  # The services' own containers, not their init containers (busybox), for the summary.
  tags = services.flat_map { |d| d.dig("spec", "template", "spec", "containers") || [] }
                 .map { |c| tag_of(c["image"].to_s) }.uniq
  floors = prod.select { |d| d["kind"] == "HorizontalPodAutoscaler" }
               .map { |h| "#{h.dig('spec', 'scaleTargetRef', 'name')} #{h.dig('spec', 'minReplicas')}-#{h.dig('spec', 'maxReplicas')}" }
  puts "Environments: dev, staging and prod each render into incident-platform-<overlay>; prod's #{services.size} services run #{tags.join(', ')}."
  puts "Replicas: the HPAs own every service's count in all three renders; prod: #{floors.join(', ')}."
  exit 0
end
$errors.each { |e| puts "::error::#{e}" }
puts "#{$errors.size} environment problem(s)."
exit 1
