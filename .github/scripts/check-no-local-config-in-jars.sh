#!/usr/bin/env bash
# ============================================================
# Backlog #0-100: no jar holds a developer's gitignored local configuration (application-local.*,
# application-secrets.*: their real local secrets). The root POM excludes both from every jar
# (maven-jar-plugin), `shared` included; since #0-141 the local images package the jar a developer's
# machine built (ADR-0030), and CI's jars are a public artifact. CI plants both files in every module
# before compiling (ci.yml, build-and-test), then runs this on the packaged jars. For every directory
# with a Dockerfile:
#
# 1. exactly one <service>/target/<service>-*.jar (the repackaged jar; `*.jar.original` does not match);
# 2. its listing (read in full first, so no early exit of a pipe can hide a match) has no
#    application-local.* or application-secrets.* entry;
# 3. it nests exactly one BOOT-INF/lib/shared-*.jar, whose listing has none either.
#
# A jar that cannot be listed is a failure, not a pass. Run from the repository root after
# `./mvnw package` (CI: build-and-test job, after its own cases in test-no-local-config-in-jars.sh).
# Portable to bash 3.2.
# ============================================================
set -euo pipefail

LOCAL_CONFIG='(^|/)application-(local|secrets)\.'
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

errors=0
checked=0

fail() {
    echo "::error file=$1::$2 (backlog #0-100)"
    errors=$((errors + 1))
}

# list <zip> <listing file>: the zip's entry names, or a failure if it cannot be read.
list() {
    unzip -Z1 "$1" > "$2" 2>/dev/null
}

for dockerfile in */Dockerfile; do
    [ -e "$dockerfile" ] || continue
    service=${dockerfile%/Dockerfile}
    checked=$((checked + 1))

    jars=()
    for jar in "$service/target/$service"-*.jar; do
        [ -e "$jar" ] && jars+=("$jar")
    done
    if [ "${#jars[@]}" -ne 1 ]; then
        fail "$dockerfile" "$service: expected one $service/target/$service-*.jar, found ${#jars[@]}"
        continue
    fi
    jar=${jars[0]}

    if ! list "$jar" "$WORK/outer"; then
        fail "$jar" "$jar cannot be listed as a zip"
        continue
    fi
    if grep -qE "$LOCAL_CONFIG" "$WORK/outer"; then
        fail "$jar" "$jar contains $(grep -E "$LOCAL_CONFIG" "$WORK/outer" | head -1): a developer's local secrets would ship in it"
    fi

    grep -E '^BOOT-INF/lib/shared-[^/]*\.jar$' "$WORK/outer" > "$WORK/shared" || true
    shared_count=$(wc -l < "$WORK/shared" | tr -d ' ')
    if [ "$shared_count" -ne 1 ]; then
        fail "$jar" "$jar nests $shared_count BOOT-INF/lib/shared-*.jar, expected one to check"
        continue
    fi
    shared=$(cat "$WORK/shared")
    if ! unzip -p "$jar" "$shared" > "$WORK/shared.jar" 2>/dev/null || ! list "$WORK/shared.jar" "$WORK/inner"; then
        fail "$jar" "$shared in $jar cannot be read as a zip"
        continue
    fi
    if grep -qE "$LOCAL_CONFIG" "$WORK/inner"; then
        fail "$jar" "$shared in $jar contains $(grep -E "$LOCAL_CONFIG" "$WORK/inner" | head -1)"
    fi
done

if [ "$checked" -eq 0 ]; then
    echo "::error::no service with a Dockerfile found: run from the repository root (backlog #0-100)"
    exit 1
fi
if [ "$errors" -gt 0 ]; then
    exit 1
fi
echo "Checked $checked service jars and the shared jar in each: no local configuration in any."
