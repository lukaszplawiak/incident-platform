#!/usr/bin/env bash
# ============================================================
# Backlog #0-94, step 1: every service logs one JSON object per line in ECS, its
# encoder escaping every value. shared's StructuredLoggingGuard refuses to start
# a service whose logs would not be (whatever set the format: a service's
# configuration, the environment, SPRING_APPLICATION_JSON, a custom Logback
# file), so a committed configuration that switched JSON off would already fail
# the smoke test, which starts all seven. What the guard lets through on
# purpose is the developer's switch, platform.logging.plain-text, set only in a
# gitignored application-local.yml. So no tracked configuration may name it, in
# the spellings people write (Spring binds more, ignoring case and dashes; the
# guard, not this check, is what holds, so this one aims at the usual slips):
#
#   plain-text / plain_text / plainText / PlainText / plaintext / PLAIN_TEXT as a
#   key (YAML, flow style, quoted, escaped inside a JSON string, properties, an
#   argument) and the environment variable PLATFORM_LOGGING_PLAINTEXT /
#   PLATFORM_LOGGING_PLAIN_TEXT in any case,
#
# and no service may ship a Logback file of its own (logback.xml,
# logback-spring.xml, .groovy) in src/main/resources, which the guard also
# refuses at startup. Kafka's PLAINTEXT protocol is not a key and is not matched.
#
# Read: tracked YAML, properties, JSON and .env files, Dockerfiles, shell scripts
# and Makefiles, except shared's own test fixtures and this check's own files. Run from the repository root (CI: build-and-test
# job, after its own cases in test-structured-logging.sh). Portable to bash 3.2.
# ============================================================
set -euo pipefail

KEY='(^|[^A-Za-z0-9_])([Pp]lain[-_]?[Tt]ext|PLAIN[-_]TEXT)\\?["'"'"']?[[:space:]]*[:=]'
ENV_VAR='platform_logging_plain_?text'

found=0
checked=0

while IFS= read -r file; do
    [ -n "$file" ] || continue
    case "$file" in shared/src/test/*|.github/scripts/*structured-logging.sh) continue ;; esac
    checked=$((checked + 1))
    line=$( { grep -nE "$KEY" "$file"; grep -niE "$ENV_VAR" "$file"; } | head -1 || true)
    if [ -n "$line" ]; then
        echo "::error file=$file::$file names platform.logging.plain-text (line ${line%%:*}): plain-text logs print values unescaped, and only a developer's gitignored application-local.yml may switch to them (backlog #0-94)"
        found=$((found + 1))
    fi
done < <(git ls-files -- '*.yml' '*.yaml' '*.properties' '*.json' '*.env' '*.env.*' '.env*' '*Dockerfile' \
            '*.sh' '*Makefile' '*.mk')

while IFS= read -r file; do
    [ -n "$file" ] || continue
    echo "::error file=$file::$file replaces Spring Boot's logging configuration and its JSON encoder; the service would refuse to start (backlog #0-94)"
    found=$((found + 1))
done < <(git ls-files -- '*/src/main/resources/logback*.xml' '*/src/main/resources/logback*.groovy')

if [ "$checked" -eq 0 ]; then
    echo "::error::no configuration found: run from the repository root (backlog #0-94)"
    exit 1
fi
if [ "$found" -gt 0 ]; then
    exit 1
fi
echo "Checked $checked files: no committed configuration switches the logs to plain text."
