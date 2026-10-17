#!/usr/bin/env bash
# ============================================================
# Tests .github/scripts/check-maven-wrapper-sum.sh (backlog #0-142) offline: one temporary directory per
# case, holding a maven-wrapper.properties, a stand-in "Maven Central" with the release zip and a
# stand-in Apache distribution site with its .sha512, both served as file:// URLs through the checker's
# test overrides (MAVEN_CENTRAL, APACHE_DIST).
#
# Run by the build-and-test job in .github/workflows/ci.yml, before the checker; locally:
#   .github/scripts/test-maven-wrapper-sum.sh
# ============================================================
set -euo pipefail

CHECKER=$(cd "$(dirname "$0")" && pwd)/check-maven-wrapper-sum.sh
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0
dir=""

CENTRAL=https://repo.maven.apache.org/maven2
VERSION=9.9.9
ZIP=apache-maven-$VERSION-bin.zip
URL=$CENTRAL/org/apache/maven/apache-maven/$VERSION/$ZIP

sha() {
    local out
    if command -v sha"$1"sum >/dev/null; then out=$(sha"$1"sum "$2"); else out=$(shasum -a "$1" "$2"); fi
    echo "${out%% *}"
}

# release [zip content]: the case's Central holds $ZIP; its dist site holds the .sha512 of the real release
# (the default content), so a different content is a tampered download.
release() {
    mkdir -p "$dir/central/org/apache/maven/apache-maven/$VERSION" "$dir/dist/maven-9/$VERSION/binaries"
    printf 'the real release\n' > "$dir/real.zip"
    printf '%s' "${1:-the real release
}" > "$dir/central/org/apache/maven/apache-maven/$VERSION/$ZIP"
    sha 512 "$dir/real.zip" > "$dir/dist/maven-9/$VERSION/binaries/$ZIP.sha512"
    SUM=$(sha 256 "$dir/real.zip")
}

# props <line>...: the properties file, one line each, every line ending in a newline.
props() {
    printf '%s\n' wrapperVersion=3.3.4 distributionType=only-script "$@" > "$dir/maven-wrapper.properties"
}

# [MSG=<regex>] [DIST=<bases, @DIR@ = the case's directory>] case_ <expect: pass|fail> <name> <setup command...>
case_() {
    local expect=$1 name=$2; shift 2
    dir="$WORK/case$((n += 1))"
    mkdir -p "$dir"
    release
    "$@"
    local out rc dist=${DIST:-file://@DIR@/dist}
    dist=${dist//@DIR@/$dir}
    set +e
    out=$(cd "$dir" && WRAPPER_PROPERTIES="$dir/maven-wrapper.properties" MAVEN_CENTRAL="file://$dir/central" \
        APACHE_DIST="$dist" bash "$CHECKER" 2>&1)
    rc=$?
    set -e
    # A failure must be the checker's own verdict, not a crash.
    if { [ "$expect" = pass ] && [ "$rc" -eq 0 ]; } \
       || { [ "$expect" = fail ] && [ "$rc" -eq 1 ] && grep -qE "backlog #0-142" <<<"$out" \
            && { [ -z "${MSG:-}" ] || grep -qE "$MSG" <<<"$out"; }; }; then
        echo "  ok: $expect - $name"
    else
        echo "::error::expected $expect - $name (exit $rc): $out"
        failures=$((failures + 1))
    fi
}

good() { props "distributionUrl=$URL" "distributionSha256Sum=$SUM"; }
sum_first() { props "distributionSha256Sum=$SUM" "distributionUrl=$URL"; }
crlf_and_spaces() { printf 'distributionUrl=%s\r\ndistributionSha256Sum= %s \r\n' "$URL" "$SUM" > "$dir/maven-wrapper.properties"; }
sha512_with_name() { printf '%s  %s\n' "$(sha 512 "$dir/real.zip" | tr a-f A-F)" "$ZIP" > "$dir/dist/maven-9/$VERSION/binaries/$ZIP.sha512"; good; }
fallback_mirror() { mkdir -p "$dir/archive" && mv "$dir/dist/maven-9" "$dir/archive/"; good; }

wrong_sum() { props "distributionUrl=$URL" "distributionSha256Sum=$(printf '%064d' 0)"; }
no_sum() { props "distributionUrl=$URL"; }
two_sums() { props "distributionUrl=$URL" "distributionSha256Sum=$SUM" "distributionSha256Sum=$SUM"; }
indented_key() { props "distributionUrl=$URL" " distributionSha256Sum=$SUM"; }
spaced_key() { props "distributionUrl=$URL" "distributionSha256Sum =$SUM"; }
last_line_unread() { printf 'distributionUrl=%s\ndistributionSha256Sum=%s' "$URL" "$SUM" > "$dir/maven-wrapper.properties"; }
upper_sum() { props "distributionUrl=$URL" "distributionSha256Sum=$(tr a-f A-F <<<"$SUM")"; }
short_sum() { props "distributionUrl=$URL" "distributionSha256Sum=${SUM%?}"; }
empty_sum() { props "distributionUrl=$URL" "distributionSha256Sum="; }
no_url() { props "distributionSha256Sum=$SUM"; }
two_urls() { props "distributionUrl=$URL" "distributionUrl=$URL" "distributionSha256Sum=$SUM"; }
other_host() { props "distributionUrl=https://repo.example.org/maven2/org/apache/maven/apache-maven/$VERSION/$ZIP" "distributionSha256Sum=$SUM"; }
http_central() { props "distributionUrl=http://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$VERSION/$ZIP" "distributionSha256Sum=$SUM"; }
dot_wildcard_host() { props "distributionUrl=https://repoxmavenxapachexorg/maven2/org/apache/maven/apache-maven/$VERSION/$ZIP" "distributionSha256Sum=$SUM"; }
embedded_url() { props "distributionUrl=https://evil.example.org/?u=$URL" "distributionSha256Sum=$SUM"; }
mvnd() { props "distributionUrl=$CENTRAL/org/apache/maven/maven-mvnd/1.0.2/maven-mvnd-1.0.2-bin.zip" "distributionSha256Sum=$SUM"; }
versions_differ() { props "distributionUrl=$CENTRAL/org/apache/maven/apache-maven/$VERSION/apache-maven-9.9.8-bin.zip" "distributionSha256Sum=$SUM"; }
tar_gz() { props "distributionUrl=${URL%.zip}.tar.gz" "distributionSha256Sum=$SUM"; }
tampered_zip() { release 'not the release'; good; }
tampered_zip_matching_sum() { release 'not the release'; props "distributionUrl=$URL" "distributionSha256Sum=$(sha 256 "$dir/central/org/apache/maven/apache-maven/$VERSION/$ZIP")"; }
no_sha512() { rm "$dir/dist/maven-9/$VERSION/binaries/$ZIP.sha512"; good; }
garbage_sha512() { echo '<html>Not Found</html>' > "$dir/dist/maven-9/$VERSION/binaries/$ZIP.sha512"; good; }
no_zip() { rm "$dir/central/org/apache/maven/apache-maven/$VERSION/$ZIP"; good; }
no_properties() { :; }

echo "check-maven-wrapper-sum.sh:"
case_ pass "the released zip and its sum" good
case_ pass "the sum before the URL" sum_first
case_ pass "CRLF line ends and spaces around the value (mvnw trims them)" crlf_and_spaces
case_ pass "a .sha512 in 'HEX  name' form, uppercase" sha512_with_name
DIST="file://@DIR@/nowhere file://@DIR@/archive" case_ pass "the .sha512 only on the second site (archive)" fallback_mirror

MSG="has SHA-256" case_ fail "a sum that is not the release's" wrong_sum
MSG="distributionSha256Sum must appear exactly once.*found 0" case_ fail "no sum" no_sum
MSG="found 2" case_ fail "the sum twice (mvnw takes the last)" two_sums
MSG="distributionSha256Sum must appear exactly once.*found 0" case_ fail "an indented key mvnw does not read" indented_key
MSG="distributionSha256Sum must appear exactly once.*found 0" case_ fail "a space before = (mvnw does not read the key)" spaced_key
MSG="distributionSha256Sum must appear exactly once.*found 0" case_ fail "the sum on a last line without a newline (mvnw skips it)" last_line_unread
MSG="lowercase hex" case_ fail "an uppercase sum (mvnw.cmd compares lowercase)" upper_sum
MSG="lowercase hex" case_ fail "a sum one character short" short_sum
MSG="lowercase hex" case_ fail "an empty sum" empty_sum
MSG="distributionUrl must appear exactly once.*found 0" case_ fail "no URL" no_url
MSG="distributionUrl must appear exactly once.*found 2" case_ fail "the URL twice" two_urls
MSG="distributionUrl must be" case_ fail "a zip from another host" other_host
MSG="distributionUrl must be" case_ fail "Central over plain http" http_central
MSG="distributionUrl must be" case_ fail "a host matching Central's only if . were a wildcard" dot_wildcard_host
MSG="distributionUrl must be" case_ fail "another URL that only contains Central's" embedded_url
MSG="distributionUrl must be" case_ fail "mvnd (mvnw cannot check its sum)" mvnd
MSG="distributionUrl must be" case_ fail "the directory and the file name differ in version" versions_differ
MSG="distributionUrl must be" case_ fail "a .tar.gz URL" tar_gz
MSG="not the release" case_ fail "a download that is not the release" tampered_zip
MSG="not the release" case_ fail "a tampered download whose SHA-256 is the property (trust on first use)" tampered_zip_matching_sum
MSG="no .*\\.sha512 found" case_ fail "no .sha512 published" no_sha512
MSG="is not a SHA-512" case_ fail "a .sha512 that is an error page" garbage_sha512
MSG="cannot download" case_ fail "the zip cannot be downloaded" no_zip
MSG="not found" case_ fail "no properties file" no_properties

if [ "$failures" -gt 0 ]; then
    echo "::error::$failures of $n cases failed (backlog #0-142)"
    exit 1
fi
echo "All $n cases passed."
