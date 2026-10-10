#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-k8s-mail.rb (backlog #0-42): the real k8s tree passes, and one mutated copy
# per rule fails, citing the backlog item. Each case copies k8s/ and docker/docker-compose.yml into a
# temporary directory, applies one edit, renders base and the three overlays with kubectl kustomize, and
# runs the check from there.
# Needs kubectl (it bundles kustomize) and ruby. Run by the validate-k8s-manifests job in
# .github/workflows/ci.yml; locally: .github/scripts/test-k8s-mail.sh
# ============================================================
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
CHECK="$SRC/.github/scripts/check-k8s-mail.rb"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0

# case_ <pass|fail> <name> <mutation, run inside the copy>
case_() {
    local expect=$1 name=$2 mutation=$3
    local dir="$WORK/case$((n += 1))"
    mkdir -p "$dir/docker" "$dir/render"
    cp -R "$SRC/k8s" "$dir/k8s"
    cp "$SRC/docker/docker-compose.yml" "$dir/docker/"
    (cd "$dir" && eval "$mutation") || { echo "::error::$name: the mutation itself failed"; failures=$((failures + 1)); return; }
    local env path
    for env in base dev staging prod; do
        path=k8s/overlays/$env; [ "$env" = base ] && path=k8s/base
        if ! kubectl kustomize "$dir/$path" > "$dir/render/$env-rendered.yml" 2>"$dir/render/$env.err"; then
            echo "::error::$name: kubectl kustomize $path failed: $(head -3 "$dir/render/$env.err")"
            failures=$((failures + 1)); return
        fi
    done
    local out rc
    out=$(cd "$dir" && ruby "$CHECK" "$dir/render" 2>&1); rc=$?
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ]; } || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -q "backlog #0-42" <<<"$out"; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

DEV=k8s/overlays/dev/kustomization.yml
MP=k8s/overlays/dev/mailpit.yml
# perl -0 edits across lines; each mutation must change the file, or the case is meaningless.
edit() { local file=$1 expr=$2; cp "$file" "$file.orig"; perl -0pi -e "$expr" "$file"; ! cmp -s "$file" "$file.orig"; local rc=$?; rm -f "$file.orig"; return $rc; }

echo "check-k8s-mail"
case_ pass "the repository's own manifests" ":"
# 1. Deployment and ClusterIP Service in dev
case_ fail "Service is a NodePort"            "edit $MP 's/type: ClusterIP/type: NodePort/'"
case_ fail "Service does not expose 8025"     "edit $MP 's/    - name: http\n      port: 8025\n      targetPort: http\n//'"
case_ fail "Service selects other pods"       "edit $MP 's/(  selector:\n    app: )mailpit/\${1}mailbox/'"
case_ fail "no Mailpit in dev"                "edit $DEV 's/  - mailpit.yml\n//'"
# 2 and 3. dev's app-config
case_ fail "MAIL_HOST not the Service"        "edit $DEV 's/(path: \/data\/MAIL_HOST\n        value: )\"mailpit\"/\${1}\"mailhost\"/'"
case_ fail "MAIL_PORT not SMTP"               "edit $DEV 's/(path: \/data\/MAIL_PORT\n        value: )\"1025\"/\${1}\"8025\"/'"
case_ fail "STARTTLS still required"          "edit $DEV 's/(STARTTLS_REQUIRED\n        value: )\"false\"/\${1}\"true\"/'"
case_ fail "SMTP auth still on"               "edit $DEV 's/(SMTP_AUTH\n        value: )\"false\"/\${1}\"true\"/'"
case_ fail "STARTTLS still enabled"           "edit $DEV 's/(STARTTLS_ENABLE\n        value: )\"false\"/\${1}\"true\"/'"
# 4. nothing outside dev
case_ fail "base points at a catcher"         "edit k8s/base/infrastructure/app-config.yml 's/MAIL_HOST: \"[^\"]*\"/MAIL_HOST: \"mailpit\"/'"
case_ fail "base turns SMTP auth off"         "edit k8s/base/infrastructure/app-config.yml 's/(  MAIL_PORT: \"587\"\n)/\${1}  SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH: \"false\"\n/'"
case_ fail "staging sets an override as env"  "edit k8s/base/notification-service/deployment.yml 's/(          env:\n)/\${1}            - name: SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH\n              value: \"false\"\n/'"
case_ fail "prod deploys Mailpit"             "cp $MP k8s/overlays/prod/ && edit k8s/overlays/prod/kustomization.yml 's/(resources:\n)/\${1}  - mailpit.yml\n/'"
# 5. the image
case_ fail "image uses latest"                "edit $MP 's/axllent\/mailpit:v[0-9.]+/axllent\/mailpit:latest/'"
case_ fail "image differs from compose"       "edit docker/docker-compose.yml 's/axllent\/mailpit:v[0-9.]+/axllent\/mailpit:v0.0.1/'"
# 6. no mailhog in dev
case_ fail "dev still names mailhog"          "edit $DEV 's/operator\@incident-platform.local/operator\@mailhog.local/'"
# 7. reachable only inside the cluster
case_ fail "hostPort on the UI"               "edit $MP 's/(containerPort: 8025\n)/\${1}              hostPort: 8025\n/'"
case_ fail "an Ingress routes to an alias"    "printf -- '---\napiVersion: v1\nkind: Service\nmetadata:\n  name: mail-ui\nspec:\n  selector:\n    app: mailpit\n  ports:\n    - port: 80\n      targetPort: 8025\n---\napiVersion: networking.k8s.io/v1\nkind: Ingress\nmetadata:\n  name: mail\nspec:\n  defaultBackend:\n    service:\n      name: mail-ui\n      port:\n        number: 80\n' >> $MP"
case_ fail "an Ingress routes to Mailpit"     "printf -- '---\napiVersion: networking.k8s.io/v1\nkind: Ingress\nmetadata:\n  name: mail-ui\nspec:\n  rules:\n    - http:\n        paths:\n          - path: /\n            pathType: Prefix\n            backend:\n              service:\n                name: mailpit\n                port:\n                  number: 8025\n' >> $MP"
# 8. the JVM patches reach the services only
case_ fail "JVM patch hits every Deployment"  "edit $DEV 's/(  # Spring Boot JVM needs minimum 512Mi[^\n]*\n  - target:\n      kind: Deployment\n)      labelSelector: [^\n]*\n/\${1}/'"
case_ fail "services lose the JVM patch"      "edit $DEV 's/labelSelector: app.kubernetes.io\/component=backend/labelSelector: app.kubernetes.io\/component=nothing/g'"

if [ "$failures" -gt 0 ]; then echo "$failures check-k8s-mail case(s) failed"; exit 1; fi
echo "All check-k8s-mail cases passed."
