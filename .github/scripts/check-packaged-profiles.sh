#!/usr/bin/env bash
# ============================================================
# No Spring profile configuration ships inside a service jar (backlog #0-81).
#
# Whatever is committed under */src/main/resources goes into the jar and the
# image, so a profile file there travels to every environment and applies
# wherever that profile is switched on. The test profiles that used to sit
# there hard-coded a JWT secret and an MFA encryption key. Configuration
# reaches deployed services from the environment (ConfigMap / Secret
# variables); test configuration lives in src/test/resources or
# @TestPropertySource; a developer's application-local.yml is gitignored
# (and .dockerignored), so it is never committed and not seen here.
#
# Fails when a committed file under */src/main/resources or its config/
# subdirectory (the two classpath locations Spring Boot loads) is:
#   - application-<profile>.yml / .yaml / .properties, or
#   - an application.yml / .yaml / .properties with a profile-specific
#     document (a spring.config.activate.on-profile key, nested or dotted),
#     the same thing inside the base file, or one that switches a profile on
#     (spring.profiles.active / include / default / group: in the jar it would
#     apply in every environment, past #0-63's check of the manifests), or
#   - an application.* whose secret, encryption key, private key or API key
#     (keys ending in secret, encryption-key, private-key, api-key, in any
#     case, kebab or camelCase: jwt.secret, signing-secret, jwtSecret, apiKey)
#     is anything but a ${VAR} placeholder with no default, quoted or not
#     (review: a key written straight into the base file ships just the same).
# Known limits, accepted: a value on the line after its key (a block scalar,
# or "secret:" then an indented literal) is taken for a parent key and not
# checked; passwords are the #0-66 check's (datasource) and otherwise not
# looked at; spring.config.import is not checked.
# There is no allow-list: shipping a profile in a jar needs a backlog decision
# and a change to this script. It reads the git index (git ls-files), as CI
# sees the repository: a file not yet added is not checked locally.
#
# Run by the build-and-test job in .github/workflows/ci.yml; locally, from the
# repository root: .github/scripts/check-packaged-profiles.sh
# Tested in .github/scripts/test-packaged-profiles.sh.
# ============================================================
set -euo pipefail

# Portable listing (bash 3.2 on macOS has no mapfile); git quotes unusual
# names, so paths are taken as git prints them.
base=()
while IFS= read -r line; do base+=("$line"); done \
    < <(git ls-files -- '*/src/main/resources/application.*' '*/src/main/resources/config/application.*')
profiled=()
while IFS= read -r line; do profiled+=("$line"); done \
    < <(git ls-files -- '*/src/main/resources/application-*' '*/src/main/resources/config/application-*')

if [ "${#base[@]}" -eq 0 ]; then
    # Every service has an application.yml; none found means the listing broke,
    # and an empty listing must not pass.
    echo "::error::no committed application.* under */src/main/resources: the check would pass vacuously (backlog #0-81)"
    exit 1
fi

failed=0
for file in ${profiled[@]+"${profiled[@]}"}; do
    case "$file" in
        *.yml|*.yaml|*.properties) ;;
        *) continue ;;
    esac
    echo "::error file=$file::a profile file in src/main/resources ships in the jar; move it to src/test/resources or the environment (violates backlog #0-81)"
    failed=1
done

for file in "${base[@]}"; do
    case "$file" in
        *.yml|*.yaml|*.properties) ;;
        *) continue ;;
    esac
    # The key itself, nested (on-profile:) or dotted (...activate.on-profile=),
    # not the words in a comment or a value.
    if grep -nE '^[[:space:]]*([A-Za-z0-9_-]+\.)*on-profile[[:space:]]*[:=]' "$file"; then
        echo "::error file=$file::a profile-specific document in the base config ships in the jar (violates backlog #0-81)"
        failed=1
    fi
    # spring.profiles.<x> dotted, or "profiles:" nested whose next key is one
    # of the four (YAML nesting is followed one level: active/include/... on
    # their own line directly under a "profiles:" line).
    if grep -nE '^[[:space:]]*([A-Za-z0-9_-]+\.)*profiles\.(active|include|default|group)[[:space:]]*[:=]' "$file" \
       || awk '/^[[:space:]]*profiles:[[:space:]]*$/ {p=1; next}
               p && /^[[:space:]]*(active|include|default|group)[[:space:]]*:/ {print FILENAME": "NR": "$0; found=1}
               {p=0} END {exit found ? 0 : 1}' "$file"; then
        echo "::error file=$file::the base config switches a Spring profile on, in every environment (violates backlog #0-81)"
        failed=1
    fi
    # Secret keys: the value must be exactly a ${VAR} placeholder, no default.
    # An empty value (a parent key) is left alone.
    while IFS= read -r hit; do
        value=${hit#*[:=]}                   # after "<line>:" from grep -n
        value=${value#*[:=]}                 # after the key's own ':' or '='
        value=$(printf '%s' "$value" | sed -E 's/[[:space:]]+#.*$//; s/^[[:space:]]+//; s/[[:space:]]+$//')
        # A quoted placeholder is the same placeholder.
        value=$(printf '%s' "$value" | sed -E "s/^\"(.*)\"$/\\1/; s/^'(.*)'$/\\1/")
        [ -z "$value" ] && continue
        if ! printf '%s' "$value" | grep -qE '^\$\{[A-Za-z0-9_.]+\}$'; then
            echo "::error file=$file::${hit%%:*}: a secret that is not a \${VAR} placeholder without default ships in the jar (violates backlog #0-81)"
            failed=1
        fi
    done < <(grep -niE '^[[:space:]]*[A-Za-z0-9_.-]*(secret|encryption[-_]?key|private[-_]?key|api[-_]?key)[[:space:]]*[:=]' "$file" || true)
done

if [ "$failed" -eq 0 ]; then
    echo "Checked ${#base[@]} base config files: no profile configuration in any jar."
fi
exit "$failed"
