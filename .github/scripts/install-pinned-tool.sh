#!/usr/bin/env bash
# ============================================================
# Installs one CI tool from a GitHub release, pinned by tag and SHA-256 (backlog #0-147, ADR-0033).
#
#   install-pinned-tool.sh --repo rhysd/actionlint --tag v1.7.12 \
#       --asset actionlint_1.7.12_linux_amd64.tar.gz --sha256 <64 hex> \
#       [--member <path in the .tar.gz>] [--name <installed name>] --into <dir>
#
# Downloads the asset to a file and compares its SHA-256 with the pin (64 lowercase hex, checked first)
# before anything reads it, then installs one executable into <dir>: the archive's --member (a .tar.gz
# asset), or the asset itself (any other asset, a bare binary such as the Snyk CLI's snyk-linux).
# Installed as --name, by default the member's basename. Prints the installed path. Nothing is left in
# <dir> on any failure.
#
# The one copy of the download-and-check steps every workflow used to repeat (ADR-0021, ADR-0027,
# ADR-0032). The pins it is given sit in each job's `env`, under a `# renovate:` comment: Renovate bumps
# the tag and the SHA-256 together, and check-pinned-tools.sh checks every pin against its publisher.
# INSTALL_PINNED_TOOL_BASE_URL (default https://github.com) exists for the tests; whatever it serves
# still has to match the SHA-256.
# Needs bash, curl, sha256sum (or shasum), tar. The hash is compared as a string, not with
# `sha256sum --check --strict`: macOS's sha256sum has no --strict, and the tests run there too.
# ============================================================
set -euo pipefail

usage() { echo "usage: $0 --repo <owner/name> --tag <tag> --asset <name> --sha256 <hex> [--member <path>] [--name <name>] --into <dir>" >&2; exit 2; }

repo="" tag="" asset="" sha="" member="" name="" into=""
while [ $# -gt 0 ]; do
    [ $# -ge 2 ] || usage
    case "$1" in
        --repo) repo=$2 ;;
        --tag) tag=$2 ;;
        --asset) asset=$2 ;;
        --sha256) sha=$2 ;;
        --member) member=$2 ;;
        --name) name=$2 ;;
        --into) into=$2 ;;
        *) usage ;;
    esac
    shift 2
done
[ -n "$repo" ] && [ -n "$tag" ] && [ -n "$asset" ] && [ -n "$sha" ] && [ -n "$into" ] || usage

case "$repo" in */*/*|/*|*/) echo "::error::--repo must be owner/name, got '$repo'" >&2; exit 2 ;; */*) ;; *) echo "::error::--repo must be owner/name, got '$repo'" >&2; exit 2 ;; esac
case "$asset" in */*|.*) echo "::error::--asset must be a file name, got '$asset'" >&2; exit 2 ;; esac
if ! [[ "$sha" =~ ^[0-9a-f]{64}$ ]]; then
    echo "::error::--sha256 must be 64 lowercase hex characters, got '$sha'" >&2
    exit 2
fi
case "$asset" in
    *.tar.gz) [ -n "$member" ] || { echo "::error::$asset is an archive: --member names the file to install" >&2; exit 2; } ;;
    *) [ -z "$member" ] || { echo "::error::$asset is not a .tar.gz: --member does not apply" >&2; exit 2; } ;;
esac
[ -n "$name" ] || name=$(basename "${member:-$asset}")
case "$name" in */*|.*|"") echo "::error::--name must be a file name, got '$name'" >&2; exit 2 ;; esac

sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
base=${INSTALL_PINNED_TOOL_BASE_URL:-https://github.com}
url="$base/$repo/releases/download/$tag/$asset"

if ! curl --fail --silent --show-error --location --retry 2 --output "$work/$asset" "$url"; then
    echo "::error::could not download $url" >&2
    exit 1
fi
got=$(sha256 "$work/$asset" | cut -d' ' -f1)
if [ "$got" != "$sha" ]; then
    echo "::error::$asset ($repo $tag) does not match its pinned SHA-256 $sha: got $got" >&2
    exit 1
fi

if [ -n "$member" ]; then
    mkdir "$work/x"
    if ! tar -xzf "$work/$asset" -C "$work/x" "$member" 2>/dev/null || [ ! -f "$work/x/$member" ]; then
        echo "::error::$asset has no file $member" >&2
        exit 1
    fi
    src="$work/x/$member"
else
    src="$work/$asset"
fi

mkdir -p "$into"
install -m 0755 "$src" "$into/$name"
echo "$into/$name"
