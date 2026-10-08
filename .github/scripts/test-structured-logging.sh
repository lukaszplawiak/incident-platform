#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-structured-logging.sh (backlog #0-94): one
# temporary git repository per case, holding the files of the case (the checker
# reads only tracked files), and the checker run in it the way CI runs it (from
# the repository root).
#
# Run by the build-and-test job in .github/workflows/ci.yml; locally:
#   .github/scripts/test-structured-logging.sh
# ============================================================
set -euo pipefail

CHECKER=$(cd "$(dirname "$0")" && pwd)/check-structured-logging.sh
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0

# case_ <expect: pass|fail> <name> [untracked:]<path>=<content> [...]
case_() {
    local expect=$1 name=$2; shift 2
    local dir="$WORK/case$((n += 1))"
    mkdir -p "$dir"
    git -C "$dir" init -q
    for spec in "$@"; do
        local track=1
        case "$spec" in untracked:*) track=0; spec=${spec#untracked:} ;; esac
        local path=${spec%%=*} content=${spec#*=}
        mkdir -p "$dir/$(dirname "$path")"
        printf '%b' "$content" > "$dir/$path"
        [ "$track" -eq 0 ] || git -C "$dir" add -- "$path"
    done
    local out rc
    set +e
    out=$(cd "$dir" && bash "$CHECKER" 2>&1)
    rc=$?
    set -e
    # A failure must be the checker's own verdict, not a crash.
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ]; } \
       || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -qE "backlog #0-94" <<<"$out"; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

Y=svc/src/main/resources/application.yml
GOOD='spring:\n  application:\n    name: svc\nlogging:\n  level:\n    root: INFO\n  # JSON from shared; a plain-text pattern is local only (backlog #0-94)\n'

echo "Structured logging config check"
case_ pass "levels only, a comment naming plain text" "$Y=$GOOD"
case_ pass "Kafka's PLAINTEXT protocol is not the switch" \
    "docker/docker-compose.yml=services:\n  kafka:\n    environment:\n      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT\n      KAFKA_LISTENERS: PLAINTEXT://:9092\n"
case_ pass "a developer's untracked application-local.yml may switch to plain text" \
    "$Y=$GOOD" "untracked:svc/src/main/resources/application-local.yml=platform:\n  logging:\n    plain-text: true\n"
case_ pass "shared's own test fixture" "$Y=$GOOD" "shared/src/test/resources/plain.yml=platform:\n  logging:\n    plain-text: true\n"
case_ fail "the switch in a service's application.yml" "$Y=platform:\n  logging:\n    plain-text: true\n"
case_ fail "in a config/ copy, flow style" "svc/src/main/resources/config/application.yml=platform: {logging: {plainText: true}}\n"
case_ fail "quoted key" "$Y=platform:\n  logging:\n    \"plain_text\": \"true\"\n"
case_ fail "a properties file" "svc/src/main/resources/application.properties=platform.logging.plaintext=true\n"
case_ fail "an environment variable in k8s" "k8s/base/svc/deployment.yml=env:\n  - name: PLATFORM_LOGGING_PLAINTEXT\n    value: \"true\"\n"
case_ fail "SPRING_APPLICATION_JSON in compose" "docker/docker-compose.yml=services:\n  svc:\n    environment:\n      SPRING_APPLICATION_JSON: '{\"platform\":{\"logging\":{\"plain-text\":true}}}'\n"
case_ fail "an argument in a Dockerfile" "svc/Dockerfile=FROM scratch\nENTRYPOINT [\"java\", \"-jar\", \"app.jar\", \"--platform.logging.plain-text=true\"]\n"
case_ fail "a workflow's environment" ".github/workflows/ci.yml=jobs:\n  smoke:\n    env:\n      PLATFORM_LOGGING_PLAIN_TEXT: \"true\"\n"
case_ fail "PlainText, a casing Spring binds too" "$Y=platform:\n  logging:\n    PlainText: true\n"
case_ fail "escaped inside a JSON string in YAML" "docker/docker-compose.yml=services:\n  svc:\n    environment:\n      SPRING_APPLICATION_JSON: \"{\\\\\"platform\\\\\":{\\\\\"logging\\\\\":{\\\\\"plain-text\\\\\":true}}}\"\n"
case_ fail "the variable in lower case, in a Makefile" "Makefile=run:\n\tplatform_logging_plaintext=true ./mvnw spring-boot:run\n"
case_ fail "an argument in a shell script" "scripts/run.sh=#!/bin/sh\njava -jar app.jar --platform.logging.plain-text=true\n"
case_ pass "lower-case plaintext as a value, not a key" "$Y=spring:\n  kafka:\n    security:\n      protocol: plaintext\n"
case_ fail "a Groovy Logback file" "$Y=$GOOD" "svc/src/main/resources/logback.groovy=root(INFO)\n"
case_ fail "a test Logback file in main resources" "$Y=$GOOD" "svc/src/main/resources/logback-test.xml=<configuration/>\n"
case_ fail "a Logback file of a service's own" "$Y=$GOOD" "svc/src/main/resources/logback-spring.xml=<configuration/>\n"

if [ "$failures" -gt 0 ]; then
    echo "$failures case(s) failed"
    exit 1
fi
echo "All cases passed."
