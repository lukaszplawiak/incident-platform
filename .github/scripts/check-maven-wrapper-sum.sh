#!/usr/bin/env bash
# ============================================================
# Backlog #0-142: the Maven distribution `./mvnw` downloads is the one Apache released.
#
# `.mvn/wrapper/maven-wrapper.properties` pins it by `distributionSha256Sum`, and `mvnw` refuses a download
# whose SHA-256 differs. That only moves the question to the sum itself: Maven Central publishes no
# `.sha256` for the zip (only `.sha1`, `.sha512` and `.asc`), and Renovate's maven-wrapper manager writes
# the sum of whatever it downloaded when it bumps the version, comparing it with nothing. So a bump's sum
# is trust on first use, and nobody reading the PR can tell a good one from a bad one. This check makes it
# a verified one:
#
# 1. the properties name exactly one distributionUrl, Maven Central's apache-maven-<version>-bin.zip
#    (no other host, no mvnd: `mvnw` does not check mvnd's sum), and exactly one distributionSha256Sum,
#    64 lowercase hex characters (mvnw.cmd compares lowercased, so an uppercase sum fails on Windows);
# 2. the zip, downloaded from that URL, matches the `.sha512` Apache publishes on another host
#    (downloads.apache.org, or archive.apache.org once the version has left it);
# 3. its SHA-256 is distributionSha256Sum.
#
# Run in CI by build-and-test (ci.yml) whenever its wrapper cache misses, which is when `mvnw` is about to
# download the distribution; its own cases are in test-maven-wrapper-sum.sh. Locally:
#   .github/scripts/check-maven-wrapper-sum.sh
# Overrides, for the tests only: WRAPPER_PROPERTIES (the file), MAVEN_CENTRAL (what replaces
# https://repo.maven.apache.org/maven2 in the download) and APACHE_DIST (the base URLs tried for the
# `.sha512`, space separated). Portable to bash 3.2.
# ============================================================
set -euo pipefail

PROPERTIES=${WRAPPER_PROPERTIES:-.mvn/wrapper/maven-wrapper.properties}
CENTRAL=https://repo.maven.apache.org/maven2
MIRROR=${MAVEN_CENTRAL:-$CENTRAL}
APACHE_DIST=${APACHE_DIST:-https://downloads.apache.org/maven https://archive.apache.org/dist/maven}

fail() {
    echo "::error file=$PROPERTIES::$1 (backlog #0-142)"
    exit 1
}

# digest <256|512> <file>: the file's SHA hex digest (a tool is checked for below, before any call).
digest() {
    local out
    if command -v sha"$1"sum >/dev/null; then
        out=$(sha"$1"sum "$2")
    else
        out=$(shasum -a "$1" "$2")
    fi
    echo "${out%% *}"
}

# fetch <url> <file>: downloads, retrying a transient failure (curl retries 408, 429, 500, 502, 503, 504).
fetch() {
    curl -fsSL --retry 3 --retry-delay 5 -o "$2" "$1"
}

[ -f "$PROPERTIES" ] || fail "$PROPERTIES not found: run from the repository root"
for bits in 256 512; do
    command -v sha"$bits"sum >/dev/null || command -v shasum >/dev/null || fail "neither sha${bits}sum nor shasum is available"
done

# Read the way mvnw reads it (the same loop, mvnw:111-117), so the check sees what mvnw sees: the key is
# everything before the first `=`, untrimmed (` distributionSha256Sum=` is not the key, and mvnw would
# check nothing), the value is trimmed of all whitespace, and a last line without a newline is not read
# at all. A key that appears twice fails here: mvnw would take the last.
url="" sum="" urls=0 sums=0
while IFS="=" read -r key value; do
    case "${key-}" in
    distributionUrl) url=$(printf "%s" "${value-}" | tr -d '[:space:]') urls=$((urls + 1)) ;;
    distributionSha256Sum) sum=$(printf "%s" "${value-}" | tr -d '[:space:]') sums=$((sums + 1)) ;;
    esac
done <"$PROPERTIES"
[ "$urls" -eq 1 ] || fail "distributionUrl must appear exactly once in $PROPERTIES (as mvnw reads it), found $urls"
[ "$sums" -eq 1 ] || fail "distributionSha256Sum must appear exactly once in $PROPERTIES (as mvnw reads it), found $sums"

[[ $sum =~ ^[0-9a-f]{64}$ ]] || fail "distributionSha256Sum must be 64 lowercase hex characters, got '$sum'"

pattern="^${CENTRAL//./\\.}/org/apache/maven/apache-maven/([0-9][0-9A-Za-z.-]*)/apache-maven-([0-9][0-9A-Za-z.-]*)-bin\\.zip\$"
[[ $url =~ $pattern ]] && [ "${BASH_REMATCH[1]}" = "${BASH_REMATCH[2]}" ] \
    || fail "distributionUrl must be $CENTRAL/org/apache/maven/apache-maven/<version>/apache-maven-<version>-bin.zip, got '$url'"
version=${BASH_REMATCH[1]}
major=${version%%.*}
zip_name=apache-maven-$version-bin.zip

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

fetch "$MIRROR${url#"$CENTRAL"}" "$WORK/$zip_name" || fail "cannot download $url"

expected_512=""
for base in $APACHE_DIST; do
    if fetch "$base/maven-$major/$version/binaries/$zip_name.sha512" "$WORK/sha512" 2>/dev/null; then
        expected_512=$(awk 'NR == 1 { print tolower($1) }' "$WORK/sha512")
        break
    fi
done
[ -n "$expected_512" ] || fail "no $zip_name.sha512 found under any of: $APACHE_DIST"
[[ $expected_512 =~ ^[0-9a-f]{128}$ ]] || fail "the published $zip_name.sha512 is not a SHA-512: '$expected_512'"

actual_512=$(digest 512 "$WORK/$zip_name")
[ "$actual_512" = "$expected_512" ] \
    || fail "$zip_name from $url does not match Apache's published SHA-512: the download is not the release"

actual_256=$(digest 256 "$WORK/$zip_name")
[ "$actual_256" = "$sum" ] \
    || fail "distributionSha256Sum is $sum, but the released $zip_name has SHA-256 $actual_256"

echo "Maven $version: $zip_name matches Apache's SHA-512 and distributionSha256Sum."
