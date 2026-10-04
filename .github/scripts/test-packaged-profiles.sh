#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-packaged-profiles.sh (backlog #0-81): one
# temporary git repository per case, holding the files of the case, and the
# checker run in it the way CI runs it (it lists files with git ls-files).
#
# Run by the build-and-test job in .github/workflows/ci.yml; locally:
#   .github/scripts/test-packaged-profiles.sh
# ============================================================
set -euo pipefail

CHECKER=$(cd "$(dirname "$0")" && pwd)/check-packaged-profiles.sh
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0

# case_ <expect: pass|fail> <name> <path>=<content> [<path>=<content> ...]
# A path ending in "!" is created but left untracked (like a gitignored file).
case_() {
    local expect=$1 name=$2; shift 2
    local dir="$WORK/case$((n += 1))"
    mkdir -p "$dir"
    git -C "$dir" init -q
    for spec in "$@"; do
        local path=${spec%%=*} content=${spec#*=} track=1
        if [ "${path: -1}" = "!" ]; then path=${path%!}; track=0; fi
        mkdir -p "$dir/$(dirname "$path")"
        printf '%b' "$content" > "$dir/$path"
        [ "$track" -eq 1 ] && git -C "$dir" add "$path"
    done
    local out rc
    set +e
    out=$(cd "$dir" && bash "$CHECKER" 2>&1)
    rc=$?
    set -e
    # A failure must be the checker's own verdict, not a crash.
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ]; } \
       || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -qE "backlog #0-81" <<<"$out"; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

R=svc/src/main/resources
BASE='server:\n  port: 8080\n'

echo "Packaged profile check"
case_ pass "only the base config" "$R/application.yml=$BASE"
case_ pass "an untracked (gitignored) application-local.yml" "$R/application.yml=$BASE" "$R/application-local.yml!=jwt:\n  secret: local\n"
case_ pass "a test profile under src/test/resources" "$R/application.yml=$BASE" "svc/src/test/resources/application-test.yml=jwt:\n  secret: x\n"
case_ pass "a non-config file named application-*" "$R/application.yml=$BASE" "$R/application-banner.txt=hello\n"
case_ fail "a committed test profile" "$R/application.yml=$BASE" "$R/application-test.yml=jwt:\n  secret: x\n"
case_ fail "a committed local profile" "$R/application.yml=$BASE" "$R/application-local.yml=jwt:\n  secret: x\n"
case_ fail "a .properties profile" "$R/application.yml=$BASE" "$R/application-qa.properties=jwt.secret=x\n"
case_ fail "a .yaml profile" "$R/application.yml=$BASE" "$R/application-dev.yaml=a: 1\n"
case_ fail "a profile under config/" "$R/application.yml=$BASE" "$R/config/application-test.yml=a: 1\n"
case_ fail "a profile document inside the base file" "$R/application.yml=${BASE}---\nspring:\n  config:\n    activate:\n      on-profile: test\njwt:\n  secret: x\n"
case_ fail "a dotted on-profile in properties" "$R/application.properties=spring.config.activate.on-profile=test\n"
case_ fail "no base config at all (would pass vacuously)" "README=nothing\n"
case_ pass "on-profile in a comment or a value is not a profile document" "$R/application.yml=# see spring.config.activate.on-profile in the docs\nnote: no on-profile here\n"
case_ fail "a dotted on-profile key nested under spring" "$R/application.yml=spring:\n  config.activate.on-profile: test\n"
case_ pass "secrets as placeholders without default" "$R/application.yml=jwt:\n  secret: \${JWT_SECRET}\nmfa:\n  encryption-key: \${MFA_ENCRYPTION_KEY}  # 32 bytes\nslack:\n  signing-secret: \${SLACK_SIGNING_SECRET}\n"
case_ pass "a parent key named secret with nested values" "$R/application.yml=secret:\n  rotation: daily\n"
case_ fail "a literal secret in the base file" "$R/application.yml=jwt:\n  secret: test-secret-key-minimum-32-characters\n"
case_ fail "a secret placeholder with a default" "$R/application.yml=jwt:\n  secret: \${JWT_SECRET:changeme}\n"
case_ fail "a literal encryption key in properties" "$R/application.properties=mfa.encryption-key=dGVzdC1rZXk=\n"
case_ fail "a literal *-secret" "$R/application.yml=slack:\n  signing-secret: abc123\n"
case_ fail "a literal private-key" "$R/application.yml=jwt:\n  private-key: abc\n"
case_ fail "a literal secret in config/application.yml" "$R/application.yml=$BASE" "$R/config/application.yml=jwt:\n  secret: abc\n"
case_ pass "a quoted placeholder" "$R/application.yml=jwt:\n  secret: \"\${JWT_SECRET}\"\nslack:\n  signing-secret: '\${SLACK_SIGNING_SECRET}'\n"
case_ fail "a quoted literal" "$R/application.yml=jwt:\n  secret: \"abc\"\n"
case_ fail "a camelCase secret" "$R/application.yml=app:\n  jwtSecret: abc\n"
case_ fail "an API key with a default" "$R/application.yml=gemini:\n  api-key: \${GEMINI_API_KEY:your-api-key-here}\n"
case_ fail "a camelCase apiKey in properties" "$R/application.properties=gemini.apiKey=AIza123\n"
case_ pass "parent keys and TTLs are not secrets" "$R/application.yml=api-keys:\n  creation-limit: 20\napi-key:\n  header: X-API-Key\ningest:\n  api-key-introspection:\n    timeout: 2s\njwt:\n  access-token-ttl: PT15M\n"
case_ fail "spring.profiles.active in the base file" "$R/application.yml=spring:\n  profiles:\n    active: dev\n"
case_ fail "a dotted profiles.include" "$R/application.properties=spring.profiles.include=dev\n"
case_ pass "a key merely named profiles is not activation" "$R/application.yml=feature:\n  profiles:\n    names: [a, b]\n"

if [ "$failures" -gt 0 ]; then
    echo "::error::$failures packaged-profile check case(s) failed"
    exit 1
fi
echo "All packaged-profile check cases behave as expected."
