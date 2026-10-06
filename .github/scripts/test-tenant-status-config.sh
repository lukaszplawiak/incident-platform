#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-tenant-status-config.sh (backlog #0-82): one
# temporary directory per case, holding the files of the case, and the checker
# run in it the way CI runs it (from the repository root).
#
# Run by the build-and-test job in .github/workflows/ci.yml; locally:
#   .github/scripts/test-tenant-status-config.sh
# ============================================================
set -euo pipefail

CHECKER=$(cd "$(dirname "$0")" && pwd)/check-tenant-status-config.sh
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0

# case_ <expect: pass|fail> <name> <path>=<content> [<path>=<content> ...]
case_() {
    local expect=$1 name=$2; shift 2
    local dir="$WORK/case$((n += 1))"
    mkdir -p "$dir"
    for spec in "$@"; do
        local path=${spec%%=*} content=${spec#*=}
        mkdir -p "$dir/$(dirname "$path")"
        printf '%b' "$content" > "$dir/$path"
    done
    local out rc
    set +e
    out=$(cd "$dir" && bash "$CHECKER" 2>&1)
    rc=$?
    set -e
    # A failure must be the checker's own verdict, not a crash.
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ]; } \
       || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -qE "backlog #0-82" <<<"$out"; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

D='FROM scratch\n'
Y=src/main/resources/application.yml
GOOD='server:\n  port: 8080\nauth-service:\n  base-url: ${AUTH_SERVICE_URL:http://localhost:8087}\n'

echo "Tenant status config check"
case_ pass "a service with the URL; auth-service itself is not checked" \
    "svc/Dockerfile=$D" "svc/$Y=$GOOD" "auth-service/Dockerfile=$D" "auth-service/$Y=server:\n  port: 8087\n"
case_ pass "a comment after the block key, quoted placeholder, no default" \
    "svc/Dockerfile=$D" "svc/$Y=auth-service: # status\n  base-url: \"\${AUTH_SERVICE_URL}\"\n"
case_ fail "a service without the block" "svc/Dockerfile=$D" "svc/$Y=server:\n  port: 8080\n"
case_ fail "a literal URL, not from the environment" \
    "svc/Dockerfile=$D" "svc/$Y=auth-service:\n  base-url: http://auth-service:8087\n"
case_ fail "base-url under another block" \
    "svc/Dockerfile=$D" "svc/$Y=oncall:\n  base-url: \${AUTH_SERVICE_URL:x}\nauth-service:\n  timeout: 2s\n"
case_ fail "the block commented out" \
    "svc/Dockerfile=$D" "svc/$Y=#auth-service:\n#  base-url: \${AUTH_SERVICE_URL:x}\n"
case_ fail "a service with a Dockerfile but no application.yml" "svc/Dockerfile=$D"
case_ fail "one good and one missing service" \
    "good/Dockerfile=$D" "good/$Y=$GOOD" "bad/Dockerfile=$D" "bad/$Y=server:\n  port: 1\n"
case_ fail "no service at all (would pass vacuously)" "README=nothing\n"

if [ "$failures" -gt 0 ]; then
    echo "::error::$failures tenant-status config check case(s) failed"
    exit 1
fi
echo "All tenant-status config check cases behave as expected."
