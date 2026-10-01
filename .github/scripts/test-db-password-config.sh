#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-db-password-config.rb (backlog #0-66): one
# temporary git repository per case, holding the config files of the case,
# and the checker run in it the way CI runs it (it lists files with
# git ls-files itself). Every bypass found in review has a case here.
#
# Run by the postgres-roles job in .github/workflows/ci.yml; locally:
#   .github/scripts/test-db-password-config.sh   (needs Ruby 3)
# ============================================================
set -euo pipefail

CHECKER=$(cd "$(dirname "$0")" && pwd)/check-db-password-config.rb
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0

# case <expect: pass|fail> <name> <path>=<content> [<path>=<content> ...]
case_() {
    local expect=$1 name=$2; shift 2
    local dir="$WORK/case$((n += 1))"
    mkdir -p "$dir"
    for spec in "$@"; do
        local path=${spec%%=*} content=${spec#*=}
        mkdir -p "$dir/$(dirname "$path")"
        printf '%b' "$content" > "$dir/$path"
    done
    git -C "$dir" init -q
    git -C "$dir" add -A
    local out rc
    set +e
    out=$(cd "$dir" && ruby "$CHECKER" 2>&1)
    rc=$?
    set -e
    # A failure must be the checker's own verdict, not a crash (a broken
    # checker would otherwise "pass" every negative case).
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ]; } \
       || { [ "$expect" = fail ] && [ "$rc" -ne 0 ] && grep -qE "violates backlog #0-66|pass vacuously" <<<"$out" \
            && ! grep -qE "\(([A-Za-z]+::)*[A-Za-z]*Error\)" <<<"$out"; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

R=svc/src/main/resources
OK_DS='spring:\n  datasource:\n    url: jdbc:postgresql://db/x\n    password: ${DB_PASSWORD}\n'

echo "Database password config check"
case_ pass "the platform's own form" "$R/application.yml=$OK_DS"
case_ pass "a module without a datasource next to one with it" "$R/application.yml=$OK_DS" "other/src/main/resources/application.yml=server:\n  port: 1\n"
case_ pass "a non-map YAML document is ignored" "$R/application.yml=--- just text\n---\n${OK_DS}"
case_ fail "a literal password" "$R/application.yml=spring:\n  datasource:\n    url: jdbc:postgresql://db/x\n    password: devpass\n"
case_ fail "the old default" "$R/application.yml=spring:\n  datasource:\n    url: jdbc:postgresql://db/x\n    password: \${DB_PASSWORD:incident_secret}\n"
case_ fail "another variable" "$R/application.yml=spring:\n  datasource:\n    url: jdbc:postgresql://db/x\n    password: \${DATABASE_PASSWORD}\n"
case_ fail "mixed key form spring.datasource: {password}" "$R/application.yml=spring.datasource:\n  url: jdbc:postgresql://db/x\n  password: devpass\n"
case_ fail "mixed key form spring: {datasource.password}" "$R/application.yml=spring:\n  datasource.url: jdbc:postgresql://db/x\n  datasource.password: devpass\n"
case_ fail "relaxed-binding key spelling" "$R/application.yml=Spring:\n  Data_Source:\n    url: jdbc:postgresql://db/x\n    Pass-Word: devpass\n"
case_ fail "hikari.password with a literal" "$R/application.yml=${OK_DS}    hikari:\n      password: devpass\n"
case_ fail "hikari.password with a default" "$R/application.yml=${OK_DS}    hikari:\n      password: \${X:devpass}\n"
case_ fail "a password in the JDBC URL" "$R/application.yml=spring:\n  datasource:\n    url: jdbc:postgresql://db/x?user=a&password=devpass\n    password: \${DB_PASSWORD}\n"
case_ fail "spring.flyway.password with a default" "$R/application.yml=${OK_DS}  flyway:\n    password: \${FLYWAY_PASSWORD:devpass}\n"
case_ fail "a datasource URL without a password" "$R/application.yml=spring:\n  datasource:\n    url: jdbc:postgresql://db/x\n"
case_ fail "a bad second YAML document" "$R/application.yml=${OK_DS}---\nspring:\n  datasource:\n    password: devpass\n"
case_ fail "config/ subdirectory" "$R/application.yml=$OK_DS" "$R/config/application.yml=spring:\n  datasource:\n    password: devpass\n"
case_ fail "a .yaml profile" "$R/application.yml=$OK_DS" "$R/application-prod.yaml=spring:\n  datasource:\n    password: devpass\n"
case_ fail ".properties with '='" "$R/application.yml=$OK_DS" "$R/application-x.properties=spring.datasource.password=devpass\n"
case_ fail ".properties with a space separator" "$R/application.yml=$OK_DS" "$R/application-x.properties=spring.datasource.password devpass\n"
case_ fail ".properties with a continued line" "$R/application.yml=$OK_DS" "$R/application-x.properties=spring.datasource.password=\\\\\n    devpass\n"
case_ fail "no file sets a password" "$R/application.yml=server:\n  port: 1\n"

echo
if [ "$failures" -ne 0 ]; then
    echo "::error::$failures of $n cases failed"
    exit 1
fi
echo "All $n cases behave as expected."
