#!/usr/bin/env bash
# ============================================================
# Tests for install-pinned-tool.sh (backlog #0-147, ADR-0033), offline: a temporary directory laid out as
# <repo>/releases/download/<tag>/<asset> is served through INSTALL_PINNED_TOOL_BASE_URL=file://...
# Run: .github/scripts/test-install-pinned-tool.sh   (also run by ci.yml's lint-workflows job)
# ============================================================
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/install-pinned-tool.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

rel="$WORK/srv/o/tool/releases/download/v1.2.3"
mkdir -p "$rel" "$WORK/pkg/tool-v1.2.3"
printf '#!/bin/sh\necho tool 1.2.3\n' > "$WORK/pkg/tool-v1.2.3/tool"
printf 'readme\n' > "$WORK/pkg/tool-v1.2.3/README"
(cd "$WORK/pkg" && tar -czf "$rel/tool_1.2.3_linux.tar.gz" tool-v1.2.3)
printf '#!/bin/sh\necho bare\n' > "$rel/tool-linux"
TGZ=$(sha256 "$rel/tool_1.2.3_linux.tar.gz" | cut -d' ' -f1)
BARE=$(sha256 "$rel/tool-linux" | cut -d' ' -f1)
export INSTALL_PINNED_TOOL_BASE_URL="file://$WORK/srv"

# run <into> <args...> — runs the installer; sets $out and $rc
run() { local into=$1; shift; out=$("$SCRIPT" "$@" --into "$into" 2>&1); rc=$?; }

# expect_ok <name> <into> <installed name> <expected output of the tool> <args...>
expect_ok() {
    local n=$1 into=$2 bin=$3 want=$4; shift 4
    run "$into" "$@"
    if [ "$rc" -ne 0 ]; then fail "$n: exit $rc: $out"; return; fi
    if [ "$out" != "$into/$bin" ]; then fail "$n: printed '$out', expected '$into/$bin'"; return; fi
    if [ ! -x "$into/$bin" ] || [ "$("$into/$bin")" != "$want" ]; then fail "$n: $into/$bin is not the tool"; return; fi
    ok "$n"
}

# expect_fail <name> <into> <text the error must contain> <args...> — fails, and leaves nothing in <into>
expect_fail() {
    local n=$1 into=$2 want=$3; shift 3
    run "$into" "$@"
    if [ "$rc" -eq 0 ]; then fail "$n: accepted ($out)"; return; fi
    case "$out" in *"$want"*) ;; *) fail "$n: error does not say '$want': $out"; return ;; esac
    if [ -n "$(ls -A "$into" 2>/dev/null)" ]; then fail "$n: left files in $into: $(ls -A "$into")"; return; fi
    ok "$n"
}

A=(--repo o/tool --tag v1.2.3 --asset tool_1.2.3_linux.tar.gz)
B=(--repo o/tool --tag v1.2.3 --asset tool-linux)

echo "install-pinned-tool.sh"
expect_ok "an archive member, installed under its basename" "$WORK/i1" tool "tool 1.2.3" "${A[@]}" --sha256 "$TGZ" --member tool-v1.2.3/tool
expect_ok "a bare binary, installed under --name" "$WORK/i2" snyk "bare" "${B[@]}" --sha256 "$BARE" --name snyk
expect_ok "a bare binary without --name keeps the asset's name" "$WORK/i3" tool-linux "bare" "${B[@]}" --sha256 "$BARE"

expect_fail "a wrong SHA-256" "$WORK/f1" "does not match its pinned SHA-256" "${A[@]}" --sha256 "$BARE" --member tool-v1.2.3/tool
expect_fail "an uppercase SHA-256" "$WORK/f2" "64 lowercase hex" "${A[@]}" --sha256 "$(printf '%s' "$TGZ" | tr a-f A-F)" --member tool-v1.2.3/tool
expect_fail "a short SHA-256" "$WORK/f3" "64 lowercase hex" "${A[@]}" --sha256 "${TGZ:0:63}" --member tool-v1.2.3/tool
expect_fail "a missing asset" "$WORK/f4" "could not download" --repo o/tool --tag v9.9.9 --asset tool_1.2.3_linux.tar.gz --sha256 "$TGZ" --member tool-v1.2.3/tool
expect_fail "a member the archive lacks" "$WORK/f5" "has no file" "${A[@]}" --sha256 "$TGZ" --member tool-v1.2.3/nope
expect_fail "a member that is a directory" "$WORK/f5b" "has no file" "${A[@]}" --sha256 "$TGZ" --member tool-v1.2.3
expect_fail "an archive without --member" "$WORK/f6" "--member names the file" "${A[@]}" --sha256 "$TGZ"
expect_fail "--member on a bare binary" "$WORK/f7" "--member does not apply" "${B[@]}" --sha256 "$BARE" --member x
expect_fail "a repo that is not owner/name" "$WORK/f8" "owner/name" --repo tool --tag v1.2.3 --asset tool-linux --sha256 "$BARE"
expect_fail "an asset with a path" "$WORK/f9" "file name" --repo o/tool --tag v1.2.3 --asset ../tool-linux --sha256 "$BARE"
expect_fail "a name with a path" "$WORK/f10" "file name" "${B[@]}" --sha256 "$BARE" --name ../snyk
expect_fail "a missing --into" "$WORK/f11" "usage" "${B[@]}" --sha256 "$BARE" --into
out=$("$SCRIPT" "${B[@]}" --sha256 "$BARE" 2>&1); rc=$?
if [ "$rc" -eq 2 ] && [[ "$out" == *usage* ]]; then ok "no --into is a usage error"; else fail "no --into: exit $rc: $out"; fi

if [ "$failures" -gt 0 ]; then echo "$failures install test(s) failed"; exit 1; fi
echo "All install tests passed."
