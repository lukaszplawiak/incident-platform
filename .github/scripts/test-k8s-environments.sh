#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-k8s-environments.rb (backlog #0-29, #0-137, #0-138): the real k8s tree passes, and one mutated
# copy per rule fails, citing the backlog item. Each case copies k8s/ into a temporary directory, applies one
# edit, renders the three overlays with kubectl kustomize, and runs the check from there. The pattern of
# test-k8s-mail.sh (backlog #0-42).
# Needs kubectl (it bundles kustomize) and ruby. Run by the validate-k8s-manifests job in
# .github/workflows/ci.yml; locally: .github/scripts/test-k8s-environments.sh
# ============================================================
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
CHECK="$SRC/.github/scripts/check-k8s-environments.rb"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0

# case_ <pass|fail> <name> <mutation, run inside the copy> [message]
# A fail case names the text of the check's own message for its rule: the checker reports every problem it
# finds, so an exit code of 1 alone would also pass when the mutation trips another rule and its own is broken.
case_() {
    local expect=$1 name=$2 mutation=$3 want=${4:-}
    local dir="$WORK/case$((n += 1))"
    mkdir -p "$dir/render"
    cp -R "$SRC/k8s" "$dir/k8s"
    (cd "$dir" && eval "$mutation") || { echo "::error::$name: the mutation itself failed"; failures=$((failures + 1)); return; }
    local env
    for env in dev staging prod; do
        if ! kubectl kustomize "$dir/k8s/overlays/$env" > "$dir/render/$env-rendered.yml" 2>"$dir/render/$env.err"; then
            echo "::error::$name: kubectl kustomize k8s/overlays/$env failed: $(head -3 "$dir/render/$env.err")"
            failures=$((failures + 1)); return
        fi
    done
    local out rc
    out=$(cd "$dir" && ruby "$CHECK" "$dir/render" 2>&1); rc=$?
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ]; } || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -q "(backlog #0-" <<<"$out" && [ -n "$want" ] && grep -qF -- "$want" <<<"$out"; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

STAGING=k8s/overlays/staging/kustomization.yml
PROD=k8s/overlays/prod/kustomization.yml
DEV=k8s/overlays/dev/kustomization.yml
# perl -0 edits across lines; each mutation must change the file, or the case is meaningless.
edit() { local file=$1 expr=$2; cp "$file" "$file.orig"; perl -0pi -e "$expr" "$file"; ! cmp -s "$file" "$file.orig"; local rc=$?; rm -f "$file.orig"; return $rc; }

echo "check-k8s-environments"
case_ pass "the repository's own manifests"   ":"
case_ pass "prod pinned by digest"            "edit $PROD 's/(  - name: auth-service\n)    newTag: \"[^\"]*\"/\${1}    digest: sha256:0000000000000000000000000000000000000000000000000000000000000000/'"
# 1. every overlay in its own namespace
case_ fail "the #0-29 swap itself"            "cp $STAGING swap.yml && cp $PROD $STAGING && cp swap.yml $PROD" \
    "into namespace incident-platform-prod, not incident-platform-staging"
case_ fail "staging in prod's namespace"      "edit $STAGING 's/^namespace: incident-platform-staging/namespace: incident-platform-prod/m'" \
    "into namespace incident-platform-prod, not incident-platform-staging"
case_ fail "prod in staging's namespace"      "edit $PROD 's/^namespace: incident-platform-prod/namespace: incident-platform-staging/m'" \
    "into namespace incident-platform-staging, not incident-platform-prod"
case_ fail "dev in another namespace"         "edit $DEV 's/^namespace: incident-platform-dev/namespace: incident-platform/m'" \
    "dev has no Namespace incident-platform-dev"
# With the top-level `namespace:` kustomize renames every Namespace object to it, and a second one is an ID
# conflict, so the next two cases take that line out: an overlay that dropped base, or brought its own
# Namespace objects, is what reaches these two branches.
case_ fail "another Namespace object"         "printf 'apiVersion: v1\nkind: Namespace\nmetadata:\n  name: incident-platform-dev\n---\napiVersion: v1\nkind: Namespace\nmetadata:\n  name: incident-platform-other\n' > k8s/overlays/dev/namespaces.yml && printf 'resources:\n  - secrets.yml\n  - namespaces.yml\n' > $DEV" \
    "dev creates Namespace incident-platform-other, not only incident-platform-dev"
case_ fail "nothing rendered into the namespace" "printf 'apiVersion: v1\nkind: Namespace\nmetadata:\n  name: incident-platform-dev\n' > k8s/overlays/dev/namespace.yml && printf 'resources:\n  - namespace.yml\n' > $DEV" \
    "dev renders nothing into incident-platform-dev"
# 2. prod's images pinned
case_ fail "prod service left at base's latest" "edit $PROD 's/  - name: oncall-service\n    newTag: \"[^\"]*\"\n?//'" \
    "runs oncall-service:latest, tagged latest"
case_ fail "prod third-party image latest"    "edit k8s/base/infrastructure/kafka.yml 's/apache\/kafka:[0-9.]+/apache\/kafka:latest/'" \
    "runs apache/kafka:latest, tagged latest"
case_ fail "prod image without a tag"         "edit k8s/base/infrastructure/redis.yml 's/image: redis:7-alpine/image: redis/'" \
    "runs \"redis\" with no tag"
# 3. prod's services on a release, not an environment
case_ fail "prod service tagged staging"      "edit $PROD 's/(  - name: incident-service\n    newTag: )\"[^\"]*\"/\${1}staging/'" \
    "runs incident-service:staging, tagged with an environment's name"
case_ fail "prod service tagged prod"         "edit $PROD 's/(  - name: ingestion-service\n    newTag: )\"[^\"]*\"/\${1}prod/'" \
    "runs ingestion-service:prod, tagged with an environment's name"
case_ fail "no service labelled backend"      "for f in k8s/base/*/deployment.yml; do edit \$f 's/app.kubernetes.io\/component: backend/app.kubernetes.io\/component: api/g' || exit 1; done" \
    "prod has no Deployment labelled app.kubernetes.io/component=backend"
# 4. the HPAs own the services' replica counts (backlog #0-137)
case_ fail "base Deployment sets replicas again" "edit k8s/base/incident-service/deployment.yml 's/^spec:\n/spec:\n  replicas: 1\n/m'" \
    "dev Deployment incident-service sets spec.replicas (1) although HorizontalPodAutoscaler incident-service-hpa owns its count (backlog #0-137)"
case_ fail "an overlay patches spec.replicas" "edit $PROD 's/^patches:\n/patches:\n  - target:\n      kind: Deployment\n      name: oncall-service\n    patch: |-\n      - op: add\n        path: \/spec\/replicas\n        value: 2\n/m'" \
    "prod Deployment oncall-service sets spec.replicas (2) although HorizontalPodAutoscaler oncall-service-hpa owns its count (backlog #0-137)"
case_ fail "a service without an HPA"         "edit k8s/base/oncall-service/service-hpa.yml 's/\n---\n.*\\z/\n/s'" \
    "staging Deployment oncall-service has no HorizontalPodAutoscaler, so it runs one pod (backlog #0-137)"
case_ fail "an HPA targets another name"      "edit k8s/base/postmortem-service/service-hpa.yml 's/(scaleTargetRef:\n(?:.*\n)*?    name: )postmortem-service/\${1}postmortem/'" \
    "staging HorizontalPodAutoscaler postmortem-service-hpa targets Deployment postmortem, which the render does not have (backlog #0-137)"
case_ fail "a floor above its ceiling"        "edit $STAGING 's/(path: \/spec\/minReplicas\n        value: )2/\${1}4/'" \
    "staging HorizontalPodAutoscaler incident-service-hpa has minReplicas 4 above maxReplicas 3 (backlog #0-137)"
# 5. prod's floor is 2
case_ fail "prod auth-service on one pod"     "edit $PROD 's/(name: auth-service-hpa\n    patch: \|-\n      - op: replace\n        path: \/spec\/minReplicas\n        value: )2/\${1}1/'" \
    "prod HorizontalPodAutoscaler auth-service-hpa has minReplicas 1; prod's floor is 2 (backlog #0-137)"
# 6. a budget and a spread over nodes for every service (backlog #0-138)
case_ fail "a service without a budget"       "edit k8s/base/kustomization.yml 's/  - escalation-service\/pdb.yml\n//'" \
    "dev Deployment escalation-service is covered by 0 PodDisruptionBudgets, not 1 (backlog #0-138)"
case_ fail "a second budget for a service"    "sed 's/name: oncall-service-pdb/name: oncall-service-pdb-2/' k8s/base/oncall-service/pdb.yml > k8s/base/oncall-service/pdb-2.yml && edit k8s/base/kustomization.yml 's/(  - oncall-service\/pdb.yml\n)/\${1}  - oncall-service\/pdb-2.yml\n/'" \
    "prod Deployment oncall-service is covered by 2 PodDisruptionBudgets, not 1 (backlog #0-138)"
case_ fail "minAvailable at the floor"        "edit k8s/base/auth-service/pdb.yml 's/^  maxUnavailable: 1\$/  minAvailable: 2/m'" \
    "staging PodDisruptionBudget auth-service-pdb sets minAvailable (2), which at the floor refuses every eviction; use maxUnavailable: 1 (backlog #0-138)"
case_ fail "a budget of more than one pod"    "edit k8s/base/ingestion-service/pdb.yml 's/^  maxUnavailable: 1\$/  maxUnavailable: 2/m'" \
    "prod PodDisruptionBudget ingestion-service-pdb has maxUnavailable 2, not 1 (backlog #0-138)"
case_ fail "unhealthy pods hold the drain"    "edit k8s/base/incident-service/pdb.yml 's/  unhealthyPodEvictionPolicy: AlwaysAllow\n//'" \
    "prod PodDisruptionBudget incident-service-pdb has unhealthyPodEvictionPolicy nil, not AlwaysAllow, so a pod that is not Ready can hold a drain (backlog #0-138)"
case_ fail "a budget that selects nothing"    "edit k8s/base/postmortem-service/pdb.yml 's/app: postmortem-service/app: postmortem/'" \
    "dev PodDisruptionBudget postmortem-service-pdb covers no service Deployment (backlog #0-138)"
case_ fail "a service with no spread"         "edit k8s/base/notification-service/deployment.yml 's/      topologySpreadConstraints:\n(?:        .*\n|          .*\n)+//'" \
    "prod Deployment notification-service has no topologySpreadConstraint on kubernetes.io/hostname, ScheduleAnyway, selecting its own pods (backlog #0-138)"
case_ fail "a spread that refuses to schedule" "edit k8s/base/escalation-service/deployment.yml 's/whenUnsatisfiable: ScheduleAnyway/whenUnsatisfiable: DoNotSchedule/'" \
    "dev Deployment escalation-service has no topologySpreadConstraint on kubernetes.io/hostname"
case_ fail "a spread on zones only"           "edit k8s/base/oncall-service/deployment.yml 's/topologyKey: kubernetes.io\/hostname/topologyKey: topology.kubernetes.io\/zone/'" \
    "staging Deployment oncall-service has no topologySpreadConstraint on kubernetes.io/hostname"
case_ fail "a spread counting other pods"     "edit k8s/base/auth-service/deployment.yml 's/(labelSelector:\n            matchLabels:\n              app: )auth-service/\${1}incident-service/'" \
    "prod Deployment auth-service has no topologySpreadConstraint on kubernetes.io/hostname"

if [ "$failures" -gt 0 ]; then echo "$failures check-k8s-environments case(s) failed"; exit 1; fi
echo "All check-k8s-environments cases passed."
