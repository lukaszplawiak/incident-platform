#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-no-local-config-in-jars.sh (backlog #0-100): one temporary directory per
# case, holding services with a Dockerfile and the jars of the case, built with `zip` (a service jar
# nesting a shared jar, as Spring Boot repackages it), and the checker run in it the way CI runs it
# (from the repository root).
#
# Run by the build-and-test job in .github/workflows/ci.yml; locally:
#   .github/scripts/test-no-local-config-in-jars.sh
# ============================================================
set -euo pipefail

CHECKER=$(cd "$(dirname "$0")" && pwd)/check-no-local-config-in-jars.sh
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0
dir=""

# zipof <zip> <entry>...: a zip holding the given entries (empty files).
zipof() {
    local zip=$1; shift
    local src
    src=$(mktemp -d "$WORK/src.XXXXXX")
    for entry in "$@"; do
        mkdir -p "$src/$(dirname "$entry")"
        : > "$src/$entry"
    done
    (cd "$src" && zip -qr "$zip" .)
}

# service <name> [service-jar entries] -- [shared-jar entries]: a service directory with a Dockerfile and
# target/<name>-1.0.jar, nesting BOOT-INF/lib/shared-1.0.jar unless the shared part is `none`.
service() {
    local name=$1; shift
    local outer=() inner=() part=outer
    for arg in "$@"; do
        if [ "$arg" = -- ]; then part=inner; continue; fi
        if [ "$part" = outer ]; then outer+=("$arg"); else inner+=("$arg"); fi
    done
    mkdir -p "$dir/$name/target"
    echo 'FROM scratch' > "$dir/$name/Dockerfile"
    local stage
    stage=$(mktemp -d "$WORK/stage.XXXXXX")
    if [ "${inner[0]:-}" != none ]; then
        zipof "$WORK/shared.$$.jar" META-INF/MANIFEST.MF ${inner[@]+"${inner[@]}"}
        mkdir -p "$stage/BOOT-INF/lib"
        mv "$WORK/shared.$$.jar" "$stage/BOOT-INF/lib/shared-1.0.jar"
    fi
    for entry in BOOT-INF/classes/application.yml ${outer[@]+"${outer[@]}"}; do
        mkdir -p "$stage/$(dirname "$entry")"
        : > "$stage/$entry"
    done
    (cd "$stage" && zip -qr "$dir/$name/target/$name-1.0.jar" .)
}

# [MSG=<regex>] case_ <expect: pass|fail> <name> <setup command...>: with MSG, a failure must also say it.
case_() {
    local expect=$1 name=$2; shift 2
    dir="$WORK/case$((n += 1))"
    mkdir -p "$dir"
    "$@"
    local out rc
    set +e
    out=$(cd "$dir" && bash "$CHECKER" 2>&1)
    rc=$?
    set -e
    # A failure must be the checker's own verdict, not a crash.
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ]; } \
       || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -qE "backlog #0-100" <<<"$out" \
            && { [ -z "${MSG:-}" ] || grep -qE "$MSG" <<<"$out"; }; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

clean_two() { service a-service; service b-service; }
similar_names() { service a-service BOOT-INF/classes/application-localization.properties -- application-secretsmanager.txt; }
local_in_classes() { service a-service; service b-service BOOT-INF/classes/application-local.yml; }
secrets_in_classes() { service a-service BOOT-INF/classes/application-secrets.properties; }
local_in_subdir() { service a-service BOOT-INF/classes/config/application-local.yaml; }
local_in_shared() { service a-service -- application-local.yml; }
no_shared() { service a-service -- none; }
two_shared() { service a-service BOOT-INF/lib/shared-2.0.jar; }
broken_jar() { service a-service; echo 'not a zip' > "$dir/a-service/target/a-service-1.0.jar"; }
broken_shared() { service a-service; (cd "$WORK" && rm -rf bs && mkdir -p bs/BOOT-INF/lib && echo x > bs/BOOT-INF/lib/shared-1.0.jar && cd bs && zip -qr "$dir/a-service/target/a-service-1.0.jar" .); }
no_jar() { service a-service; rm "$dir/a-service/target/"*.jar; }
two_jars() { service a-service; cp "$dir/a-service/target/a-service-1.0.jar" "$dir/a-service/target/a-service-0.9.jar"; }
original_only_extra() { service a-service; : > "$dir/a-service/target/a-service-1.0.jar.original"; }
no_dockerfile() { echo x > "$dir/README"; }

echo "No local config in jars check"
case_ pass "two clean service jars, each nesting a clean shared jar" clean_two
case_ pass "names that only start like a local config" similar_names
case_ pass "a .jar.original next to the jar is not counted" original_only_extra
case_ fail "application-local.yml in a service's classes" local_in_classes
case_ fail "application-secrets.properties in a service's classes" secrets_in_classes
case_ fail "a local config in a subdirectory of the classes" local_in_subdir
case_ fail "application-local.yml in the nested shared jar" local_in_shared
MSG="nests 0 BOOT-INF/lib/shared" case_ fail "no nested shared jar to check" no_shared
MSG="nests 2 BOOT-INF/lib/shared" case_ fail "two nested shared jars" two_shared
MSG="cannot be listed" case_ fail "a service jar that is not a zip" broken_jar
MSG="cannot be read as a zip" case_ fail "a nested shared jar that is not a zip" broken_shared
MSG="found 0" case_ fail "a service without its jar" no_jar
MSG="found 2" case_ fail "a service with two jars" two_jars
case_ fail "no Dockerfile: not run from the repository root" no_dockerfile

if [ "$failures" -gt 0 ]; then
    echo "::error::$failures case(s) of the no-local-config-in-jars check failed"
    exit 1
fi
echo "All $n cases passed."
