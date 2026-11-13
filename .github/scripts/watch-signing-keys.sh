#!/usr/bin/env bash
# ============================================================
# Compares each signing key committed under .github/keys/ with the one its publisher publishes now (backlog
# #0-152, ADR-0033), so a rotation or an extension is seen the week it happens, not on the day the old key
# expires. Snyk rotates its code-signing key (a new fingerprint every two years) and published its 2025 key two
# days after the 2023 one expired: waiting for check-pinned-tools.sh's 30-day warning leaves no key to take.
#
# Each key file <fingerprint>.asc has a sidecar <fingerprint>.source, one line `<owner/repo> <path>`: the file
# in that repository the publisher keeps its public key in (Snyk: snyk/cli
# help/_about-this-project/snyk-code-signing-public.pgp). The script fetches it (gh, public contents) and
# compares, with gpg, the primary keys in it with the committed one:
#   - a primary fingerprint the committed file does not hold: the publisher has a new key ("rotated");
#   - the committed fingerprint, revoked there and not in the committed file ("revoked": a compromise, the case where
#     every day counts; check-pinned-tools.sh refuses the key once the file carries the revocation);
#   - the committed fingerprint, expiring later than in the committed file: the key was extended.
# What it prints is a lead, not a key to commit: the refresh still takes the three-way fingerprint check of
# ADR-0033 (the repository, docs.snyk.io, keys.openpgp.org) in the owner's pull request.
#
# Exit 0: every key as committed; 3: a key changed at its publisher (the lines say which); 1: a key without a
# readable sidecar, or one that cannot be fetched or read (fail closed: a watch that cannot look says so).
# --sources-only checks only that every key file has its sidecar, offline (lint-workflows: a new key file without
# one fails its pull request, not the next Monday's watch).
# Run: GH_TOKEN=... .github/scripts/watch-signing-keys.sh [--sources-only] [keys dir]   (signing-keys.yml, weekly)
# Needs gh and gpg (neither for --sources-only).
# ============================================================
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
sources_only=0
if [ "${1:-}" = --sources-only ]; then sources_only=1; shift; fi
KEYS_DIR=${1:-$ROOT/.github/keys}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
errors=0
changed=0
err() { echo "::error::$1"; errors=$((errors+1)); }
utc_date() { date -u -d "@$1" +%F 2>/dev/null || date -u -r "$1" +%F; }
# primaries <key file> — "<fingerprint> <expiry epoch or 0> <validity>" for each primary key in the file (validity
# `r`: revoked)
primaries() {
    local g
    g=$(mktemp -d "$work/g.XXXXXX")
    chmod 700 "$g"
    gpg --homedir "$g" --batch --with-colons --show-keys "$1" 2>/dev/null </dev/null |
        awk -F: '$1 == "pub" { e = ($7 == "" ? 0 : $7); v = ($2 == "" ? "-" : $2); want = 1; next }
                 $1 == "fpr" && want { print $10, e, v; want = 0 }'
}

shopt -s nullglob
keys=("$KEYS_DIR"/*.asc)
[ "${#keys[@]}" -gt 0 ] || { err "no key file under ${KEYS_DIR#"$ROOT"/}"; exit 1; }
if [ "$sources_only" = 1 ]; then
    for key in "${keys[@]}"; do
        src="${key%.asc}.source"
        if ! read -r repo path _ < "$src" 2>/dev/null || [ -z "${repo:-}" ] || [ -z "${path:-}" ]; then
            err "${key#"$ROOT"/}: no ${src#"$ROOT"/} naming where the publisher keeps the key (<owner/repo> <path>)"
        fi
    done
    if [ "$errors" -gt 0 ]; then exit 1; fi
    echo "Every signing key names where its publisher keeps it."
    exit 0
fi
command -v gpg >/dev/null 2>&1 || { err "gpg is not installed"; exit 1; }
for key in "${keys[@]}"; do
    fpr=$(basename "$key" .asc)
    rel=${key#"$ROOT"/}
    src="$KEYS_DIR/$fpr.source"
    if ! read -r repo path _ < "$src" 2>/dev/null || [ -z "${repo:-}" ] || [ -z "${path:-}" ]; then
        err "$rel: no ${src#"$ROOT"/} naming where the publisher keeps the key (<owner/repo> <path>)"; continue
    fi
    if ! gh api -H 'Accept: application/vnd.github.raw' "repos/$repo/contents/$path" > "$work/upstream.asc" 2>"$work/gh.err" </dev/null; then
        err "$rel: cannot fetch $repo:$path (gh: $(tail -n 1 "$work/gh.err"))"; continue
    fi
    ours=$(primaries "$key")
    theirs=$(primaries "$work/upstream.asc")
    ours_exp=$(awk -v f="$fpr" '$1 == f { print $2 }' <<<"$ours")
    ours_val=$(awk -v f="$fpr" '$1 == f { print $3 }' <<<"$ours")
    if [ -z "$ours_exp" ]; then err "$rel: the file holds no primary key $fpr"; continue; fi
    if [ -z "$theirs" ]; then err "$rel: $repo:$path holds no key gpg can read"; continue; fi
    while read -r tf te tv; do
        [ -n "$tf" ] || continue
        if [ "$tf" = "$fpr" ] && [ "$tv" = r ] && [ "$ours_val" != r ]; then
            echo "revoked: $repo:$path has revoked $fpr, which the committed file does not show: refresh the file now"
            changed=$((changed+1))
        elif [ "$tf" != "$fpr" ] && ! grep -q "^$tf " <<<"$ours"; then
            echo "rotated: $repo:$path has a new key $tf ($( [ "$te" -gt 0 ] && echo "expires $(utc_date "$te")" || echo "no expiry")); $fpr is committed ($( [ "$ours_exp" -gt 0 ] && echo "expires $(utc_date "$ours_exp")" || echo "no expiry"))"
            changed=$((changed+1))
        elif [ "$tf" = "$fpr" ] && [ "$ours_exp" -gt 0 ] && { [ "$te" -eq 0 ] || [ "$te" -gt "$ours_exp" ]; }; then
            echo "extended: $repo:$path has $fpr expiring $( [ "$te" -gt 0 ] && utc_date "$te" || echo never), the committed file $(utc_date "$ours_exp")"
            changed=$((changed+1))
        fi
    done <<<"$theirs"
done
if [ "$errors" -gt 0 ]; then echo "$errors signing key watch problem(s)"; exit 1; fi
if [ "$changed" -gt 0 ]; then exit 3; fi
echo "Every signing key is as its publisher publishes it."
