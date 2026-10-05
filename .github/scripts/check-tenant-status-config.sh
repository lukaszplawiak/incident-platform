#!/usr/bin/env bash
# ============================================================
# Backlog #0-82, step 2: every runnable service but auth-service enforces a
# tenant's suspension by asking auth-service for its status
# (AuthServiceTenantStatusProvider in shared). That provider exists only where
# the service sets auth-service.base-url; without it the service silently falls
# back to "every tenant has full access". So: every directory with a Dockerfile,
# except auth-service (which owns the status), must have, in its
# src/main/resources/application.yml, a top-level
#
#   auth-service:
#     base-url: ${AUTH_SERVICE_URL...}
#
# read from the environment, as compose and k8s's app-config set it.
#
# Run from the repository root (CI: build-and-test job, after its own cases in
# test-tenant-status-config.sh). Portable to bash 3.2.
# ============================================================
set -euo pipefail

missing=0
checked=0
for dockerfile in */Dockerfile; do
    [ -e "$dockerfile" ] || continue
    service=${dockerfile%/Dockerfile}
    [ "$service" = auth-service ] && continue
    checked=$((checked + 1))
    config="$service/src/main/resources/application.yml"
    if [ ! -f "$config" ]; then
        echo "::error file=$config::$service has no application.yml, so no auth-service.base-url: tenant suspension is not enforced there (backlog #0-82)"
        missing=$((missing + 1))
        continue
    fi
    # The base-url line inside the top-level auth-service block only.
    if ! awk '
        /^auth-service:[[:space:]]*(#.*)?$/ { inblock = 1; next }
        /^[^[:space:]#]/                    { inblock = 0 }
        inblock && /^[[:space:]]+base-url:[[:space:]]*["'\'']?\$\{AUTH_SERVICE_URL[:}]/ { found = 1 }
        END { exit found ? 0 : 1 }
    ' "$config"; then
        echo "::error file=$config::$service does not set auth-service.base-url from \${AUTH_SERVICE_URL}: it would give every tenant full access, suspended or not (backlog #0-82)"
        missing=$((missing + 1))
    fi
done

if [ "$checked" -eq 0 ]; then
    echo "::error::no service with a Dockerfile found: run from the repository root (backlog #0-82)"
    exit 1
fi
if [ "$missing" -gt 0 ]; then
    exit 1
fi
echo "Checked $checked services: every one asks auth-service for tenant status."
