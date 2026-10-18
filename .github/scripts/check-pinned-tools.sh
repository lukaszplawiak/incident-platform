#!/usr/bin/env bash
# ============================================================
# Checks every CI tool pin against what its publisher released (backlog #0-147, ADR-0033).
#
# A pin is three lines in a workflow's `env`, the only form this accepts:
#
#   # renovate: datasource=github-release-attachments depName=<owner/repo> verify=<method>
#   <NAME>_VERSION: <release tag>
#   <NAME>_SHA256: <64 lowercase hex>
#
# Renovate bumps the tag and the SHA-256 together, but it computes the SHA-256 by downloading the asset
# itself: trust on first use, what #0-142 found for the Maven wrapper. So a pin is trusted only when:
#   - the tag is a published, non-prerelease release of <owner/repo>, and one of its assets has the
#     SHA-256 as GitHub's own digest (computed by GitHub when the asset was uploaded); and
#   - <method>, the strongest proof that publisher gives:
#       attestation        the asset carries a GitHub artifact attestation (Sigstore) from <owner/repo>,
#                          built from the release's own tag (`gh attestation verify --source-ref
#                          refs/tags/<tag>`): not from another branch or tag of that repository
#       release            the asset is part of the release as published, an immutable release (`gh release
#                          verify-asset <tag>`: GitHub's release attestation lists its digest), so neither the tag
#                          nor the asset can be replaced since; says nothing of how it was built (backlog #0-109)
#       gpg:<fingerprint>  the release's sha256sums.txt.asc is signed by that key (committed under
#                          .github/keys/, the file named by the fingerprint), which is neither expired nor
#                          revoked in that file, and lists the asset's SHA-256
#       checksums:<file>   the release's <file> lists the asset's SHA-256 (same host as the asset: catches
#                          a mismatch, not a malicious release)
#       digest             nothing beyond GitHub's digest (the publisher signs nothing)
# Also refuses a `*_SHA256:` line outside that form (a pin Renovate would not bump and this would not
# check), a `<NAME>_SHA256` after another name's `_VERSION`, a pin without `verify=`, and an unknown method.
#
# Run: GH_TOKEN=... .github/scripts/check-pinned-tools.sh [--expect <n>] [files]   (default: every workflow,
# and every composite action's action.yml: the same places renovate.json's custom manager reads).
# --expect fails unless exactly <n> distinct pins are found: a job that checks its own pin before using a
# secret (snyk.yml) must not pass on a pin renamed out of the `_SHA256` form, which this would not see.
# Needs gh (any token: reads public releases and attestations), jq, sha256sum or shasum; gpg for a
# gpg: pin. Runs in ci.yml's lint-workflows job on every pull request and push to main.
# ============================================================
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
KEYS_DIR=${PINNED_TOOLS_KEYS_DIR:-$ROOT/.github/keys}
expect=""
if [ "${1:-}" = --expect ]; then
    case "${2:-}" in ''|*[!0-9]*) echo "usage: $0 [--expect <n>] [files]" >&2; exit 2 ;; esac
    expect=$2; shift 2
fi
if [ $# -gt 0 ]; then files=("$@"); else
    files=("$ROOT"/.github/workflows/*.yml "$ROOT"/.github/workflows/*.yaml "$ROOT"/.github/actions/*/action.yml "$ROOT"/.github/actions/*/action.yaml)
fi

errors=0
err() { echo "::error file=$1::$2"; errors=$((errors+1)); }
is_int() { case "$1" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }
# utc_date <epoch seconds> — YYYY-MM-DD (GNU date -d, BSD date -r)
utc_date() { date -u -d "@$1" +%F 2>/dev/null || date -u -r "$1" +%F; }
# lists <sums file> <sha> <asset> — the file has the line "<sha>  <asset>" (or "<sha> *<asset>")
lists() { awk -v s="$2" -v a="$3" '$1 == s && ($2 == a || $2 == "*" a) && NF == 2 { ok = 1 } END { exit !ok }' "$1"; }
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# --- parse: every *_SHA256 line must close a three-line pin ---------------------------------------
pins="$work/pins"
: > "$pins"
for f in "${files[@]}"; do
    [ -f "$f" ] || continue
    rel=${f#"$ROOT"/}
    awk -v file="$rel" '
        { line[NR] = $0 }
        END {
            for (i = 1; i <= NR; i++) {
                if (line[i] !~ /^[ \t]*[A-Z0-9_]+_SHA256:/) continue
                c = line[i-2]; v = line[i-1]; s = line[i]
                if (c !~ /^[ \t]*# renovate: datasource=github-release-attachments depName=[^ ]+ verify=[^ ]+[ \t]*$/ ||
                    v !~ /^[ \t]*[A-Z0-9_]+_VERSION: [^ ]+[ \t]*$/ ||
                    s !~ /^[ \t]*[A-Z0-9_]+_SHA256: [0-9a-f]+[ \t]*$/) {
                    printf "BAD\t%s\t%d\n", file, i; continue
                }
                # One name for both lines: the workflow installs with <NAME>_VERSION and <NAME>_SHA256, so
                # a pair of two names would check one SHA-256 and install with another.
                vn = v; sub(/^[ \t]*/, "", vn); sub(/_VERSION:.*/, "", vn)
                sn = s; sub(/^[ \t]*/, "", sn); sub(/_SHA256:.*/, "", sn)
                if (vn != sn) { printf "PAIR\t%s\t%d\t%s\t%s\n", file, i, vn, sn; continue }
                sub(/.*depName=/, "", c); split(c, cw, " "); repo = cw[1]
                meth = cw[2]; sub(/^verify=/, "", meth)
                sub(/^[ \t]*[A-Z0-9_]+_VERSION: /, "", v); sub(/[ \t]+$/, "", v)
                sub(/^[ \t]*[A-Z0-9_]+_SHA256: /, "", s); sub(/[ \t]+$/, "", s)
                printf "PIN\t%s\t%d\t%s\t%s\t%s\t%s\n", file, i, repo, v, s, meth
            }
        }' "$f" >> "$pins"
done

while IFS=$'\t' read -r kind file line vname sname; do
    case "$kind" in
        BAD) err "$file,line=$line" "a *_SHA256 pin must follow '# renovate: datasource=github-release-attachments depName=<owner/repo> verify=<method>' and a *_VERSION line (backlog #0-147, ADR-0033)" ;;
        PAIR) err "$file,line=$line" "${sname}_SHA256 follows ${vname}_VERSION: a pin's two lines share one <NAME> (ADR-0033)" ;;
    esac
done < "$pins"

# --- check each distinct pin once --------------------------------------------------------------------
# The loop reads the pins on stdin: every command inside it gets </dev/null, so none can consume them. Each pin
# gets its own work directory, whether or not the one before it passed.
checked=0
n=0
while IFS=$'\t' read -r repo tag sha meth file line; do
    n=$((n+1))
    loc="$file,line=$line"
    what="$repo $tag"
    if ! [[ "$sha" =~ ^[0-9a-f]{64}$ ]]; then err "$loc" "$what: the SHA-256 must be 64 lowercase hex characters"; continue; fi
    case "$meth" in
        attestation|release|digest) ;;
        gpg:*) [[ "${meth#gpg:}" =~ ^[0-9A-F]{40}$ ]] || { err "$loc" "$what: verify=gpg: takes a 40-character uppercase fingerprint"; continue; } ;;
        checksums:?*) ;;
        *) err "$loc" "$what: unknown verify method '$meth' (attestation, release, gpg:<fingerprint>, checksums:<file>, digest)"; continue ;;
    esac

    # gh's own last line says why (404, rate limit, network, token): "no such release" alone sent the reader
    # looking for a missing tag.
    if ! release=$(gh api "repos/$repo/releases/tags/$tag" 2>"$work/gh.err" </dev/null); then
        err "$loc" "$what: cannot read the release (gh: $(tail -n 1 "$work/gh.err"))"; continue
    fi
    if [ "$(jq -r '.draft or .prerelease' <<<"$release")" != false ]; then
        err "$loc" "$what: a draft or a prerelease"; continue
    fi
    asset=$(jq -r --arg d "sha256:$sha" '[.assets[] | select(.digest == $d) | .name] | first // empty' <<<"$release")
    if [ -z "$asset" ]; then
        err "$loc" "$what: no asset of the release has GitHub's digest sha256:$sha"; continue
    fi

    d="$work/pin$n"
    mkdir -p "$d"
    case "$meth" in
        digest)
            ;;
        attestation)
            if ! gh release download "$tag" -R "$repo" -p "$asset" -D "$d" </dev/null >/dev/null 2>&1; then
                err "$loc" "$what: could not download $asset"; continue
            fi
            if [ "$(sha256 "$d/$asset" | cut -d' ' -f1)" != "$sha" ]; then
                err "$loc" "$what: $asset as downloaded does not have the pinned SHA-256"; continue
            fi
            if ! out=$(gh attestation verify "$d/$asset" --repo "$repo" --source-ref "refs/tags/$tag" 2>&1 </dev/null); then
                err "$loc" "$what: $asset has no valid attestation from $repo built from refs/tags/$tag: $(echo "$out" | tail -1)"; continue
            fi
            ;;
        release)
            if ! gh release download "$tag" -R "$repo" -p "$asset" -D "$d" </dev/null >/dev/null 2>&1; then
                err "$loc" "$what: could not download $asset"; continue
            fi
            if [ "$(sha256 "$d/$asset" | cut -d' ' -f1)" != "$sha" ]; then
                err "$loc" "$what: $asset as downloaded does not have the pinned SHA-256"; continue
            fi
            if ! out=$(gh release verify-asset "$tag" "$d/$asset" -R "$repo" 2>&1 </dev/null); then
                err "$loc" "$what: $asset is not attested as part of the immutable release $tag: $(echo "$out" | tail -1)"; continue
            fi
            ;;
        checksums:*)
            sums=${meth#checksums:}
            if ! gh release download "$tag" -R "$repo" -p "$sums" -D "$d" </dev/null >/dev/null 2>&1; then
                err "$loc" "$what: the release has no $sums"; continue
            fi
            if ! lists "$d/$sums" "$sha" "$asset"; then
                err "$loc" "$what: $sums does not list $sha for $asset"; continue
            fi
            ;;
        gpg:*)
            fpr=${meth#gpg:}
            key="$KEYS_DIR/$fpr.asc"
            if [ ! -f "$key" ]; then err "$loc" "$what: no key file ${key#"$ROOT"/}"; continue; fi
            if ! command -v gpg >/dev/null 2>&1; then err "$loc" "$what: gpg is not installed"; continue; fi
            if ! gh release download "$tag" -R "$repo" -p sha256sums.txt.asc -D "$d" </dev/null >/dev/null 2>&1; then
                err "$loc" "$what: the release has no sha256sums.txt.asc"; continue
            fi
            g="$d/gnupg"
            mkdir -m 700 "$g"
            if ! gpg --homedir "$g" --batch --quiet --import "$key" 2>/dev/null </dev/null; then
                err "$loc" "$what: gpg cannot import ${key#"$ROOT"/}"; continue
            fi
            # A key with an expiry fails every pin that names it on the day it expires (fail closed: a
            # signature by an expired key is refused below), so this warns 30 days ahead. Refresh the file
            # from the publisher, after the same three-way fingerprint check (ADR-0033).
            expires=$(gpg --homedir "$g" --batch --with-colons --list-keys "$fpr" 2>/dev/null </dev/null | awk -F: '$1 == "pub" { print $7; exit }')
            if is_int "$expires" && [ "$expires" -gt 0 ] && [ $((expires - $(date +%s))) -lt $((30 * 86400)) ]; then
                echo "::warning file=${key#"$ROOT"/}::$what: the signing key $fpr expires on $(utc_date "$expires"): refresh this file from the publisher before then (ADR-0033)"
            fi
            # One clearsigned message, no more: a second block appended to the file (unsigned, or signed by
            # another key) must not get its lines into what lists() reads.
            if [ "$(grep -c '^-----BEGIN PGP SIGNED MESSAGE-----' "$d/sha256sums.txt.asc")" != 1 ]; then
                err "$loc" "$what: sha256sums.txt.asc is not exactly one signed message"; continue
            fi
            # VALIDSIG's last field is the primary key's fingerprint: the signature must be by the pinned key
            # (or one of its subkeys), whatever else the key file holds. gpg also reports VALIDSIG for a
            # signature by an expired or revoked key, so either of those refuses it: a key Snyk revokes
            # is caught once the key file is refreshed with the revocation. Any bad or unverifiable
            # signature in the file, or a non-zero exit, refuses it too. The exit is what refuses an unsigned
            # armored message (`gpg --store`) next to the signed one: gpg 2.4.4 writes its text to --output
            # when it comes first, and exits 2 either way ("no signature found", "multiple plaintexts").
            status=$(gpg --homedir "$g" --batch --status-fd 1 --output "$d/signed" --verify "$d/sha256sums.txt.asc" 2>/dev/null </dev/null)
            rc=$?
            if grep -Eq '^\[GNUPG:\] (EXPKEYSIG|REVKEYSIG) ' <<<"$status"; then
                err "$loc" "$what: sha256sums.txt.asc is signed by an expired or revoked key"; continue
            fi
            if [ "$rc" -ne 0 ] || grep -Eq '^\[GNUPG:\] (BADSIG|ERRSIG|NO_PUBKEY) ' <<<"$status"; then
                err "$loc" "$what: sha256sums.txt.asc is not validly signed by $fpr"; continue
            fi
            if ! awk -v f="$fpr" '$2 == "VALIDSIG" && $NF == f { ok = 1 } END { exit !ok }' <<<"$status"; then
                err "$loc" "$what: sha256sums.txt.asc is not validly signed by $fpr"; continue
            fi
            if ! lists "$d/signed" "$sha" "$asset"; then
                err "$loc" "$what: the signed sha256sums.txt does not list $sha for $asset"; continue
            fi
            ;;
    esac
    echo "  ok: $what $asset ($meth)"
    checked=$((checked+1))
done < <(awk -F'\t' '$1 == "PIN" && !seen[$4 FS $5 FS $6 FS $7]++ { print $4 "\t" $5 "\t" $6 "\t" $7 "\t" $2 "\t" $3 }' "$pins")

if [ -n "$expect" ] && [ "$n" -ne "$expect" ]; then
    err "${files[0]#"$ROOT"/}" "found $n pinned tool(s), expected $expect: a pin outside the *_SHA256 form is not checked (ADR-0033)"
fi
if [ "$errors" -gt 0 ]; then echo "$errors pinned tool problem(s)"; exit 1; fi
echo "All $checked pinned tools match their publishers."
