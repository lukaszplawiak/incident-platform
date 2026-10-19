#!/usr/bin/env bash
# ============================================================
# Tests for check-pinned-tools.sh (backlog #0-147, ADR-0033), offline: gh is a stub on PATH that serves
# releases, assets and attestations from a temporary directory. The gpg cases use the real gpg with a
# key generated here, and run only where gpg is installed (CI's runner has it; they are reported as
# skipped elsewhere).
# Run: .github/scripts/test-check-pinned-tools.sh   (also run by ci.yml's lint-workflows job)
# ============================================================
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-pinned-tools.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
FIX="$WORK/fix"
mkdir -p "$WORK/bin" "$FIX" "$WORK/keys"

# gh stub: `api repos/<r>/releases/tags/<t>`, `release download <t> -R <r> -p <name> -D <dir>`,
# `attestation verify <file> --repo <r> --source-ref <ref>` (valid when "<r> <ref> <sha of file>" is a line of
# $FIX/attested: an attestation is from one repository and built from one ref), and
# `release verify-asset <t> <file> -R <r>` (valid when "<r> <t> <sha of file>" is a line of $FIX/released: a
# release attestation lists the assets of one immutable release).
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
case "$1 $2" in
    "api repos/"*)
        p=${2#repos/}; r=${p%/releases/tags/*}; t=${p##*/releases/tags/}
        [ -f "$FIX/$r/$t/release.json" ] || { echo "HTTP 404" >&2; exit 1; }
        cat "$FIX/$r/$t/release.json" ;;
    "release download")
        t=$3; shift 3; while [ $# -gt 0 ]; do case "$1" in -R) r=$2 ;; -p) n=$2 ;; -D) d=$2 ;; esac; shift 2; done
        [ -f "$FIX/$r/$t/serve/$n" ] || { echo "no asset" >&2; exit 1; }
        cp "$FIX/$r/$t/serve/$n" "$d/" ;;
    "attestation verify")
        f=$3; shift 3; r=""; ref=""
        while [ $# -gt 0 ]; do case "$1" in --repo) r=$2 ;; --source-ref) ref=$2 ;; esac; shift 2; done
        s=$(sha256 "$f" | cut -d' ' -f1)
        grep -qx "$r $ref $s" "$FIX/attested" 2>/dev/null || { echo "no matching attestations found" >&2; exit 1; } ;;
    "release verify-asset")
        t=$3; f=$4; shift 4; while [ $# -gt 0 ]; do case "$1" in -R) r=$2 ;; esac; shift 2; done
        s=$(sha256 "$f" | cut -d' ' -f1)
        grep -qx "$r $t $s" "$FIX/released" 2>/dev/null || { echo "no attestations found for tag $t" >&2; exit 1; } ;;
    *) echo "gh stub: unexpected $*" >&2; exit 1 ;;
esac
STUB
chmod +x "$WORK/bin/gh"
export FIX PATH="$WORK/bin:$PATH" PINNED_TOOLS_KEYS_DIR="$WORK/keys"
: > "$FIX/attested"
: > "$FIX/released"

# release <repo> <tag> <prerelease true|false> <asset files...> — publishes files from $WORK/files as
# assets, with GitHub's digest of each; what `release download` serves is the same files unless a test
# replaces one under serve/.
release() {
    local r=$1 t=$2 pre=$3; shift 3
    local dir="$FIX/$r/$t" assets="[]" f
    mkdir -p "$dir/serve"
    for f in "$@"; do
        cp "$WORK/files/$f" "$dir/serve/$f"
        assets=$(jq --arg n "$f" --arg d "sha256:$(sha256 "$WORK/files/$f" | cut -d' ' -f1)" '. + [{name: $n, digest: $d}]' <<<"$assets")
    done
    jq -n --argjson a "$assets" --argjson pre "$pre" '{draft: false, prerelease: $pre, assets: $a}' > "$dir/release.json"
}
file() { mkdir -p "$WORK/files"; printf '%s\n' "$2" > "$WORK/files/$1"; sha256 "$WORK/files/$1" | cut -d' ' -f1; }

# pin <file> <repo> <tag> <sha> <verify> — a workflow with one pin, in the form the checker accepts
pin() {
    printf 'jobs:\n  j:\n    env:\n      # renovate: datasource=github-release-attachments depName=%s verify=%s\n      TOOL_VERSION: %s\n      TOOL_SHA256: %s\n' "$2" "$5" "$3" "$4" > "$1"
}

# expect <name> <0|1> <text the output must contain> <workflow files...>
expect() {
    local n=$1 want=$2 text=$3 out rc; shift 3
    out=$("$SCRIPT" "$@" 2>&1); rc=$?
    if [ "$want" = 0 ] && [ "$rc" -ne 0 ]; then fail "$n: refused: $out"; return; fi
    if [ "$want" = 1 ] && [ "$rc" -eq 0 ]; then fail "$n: accepted: $out"; return; fi
    case "$out" in *"$text"*) ok "$n" ;; *) fail "$n: output does not contain '$text': $out" ;; esac
}

W="$WORK/wf"
mkdir -p "$W"
TOOL=$(file tool-linux.tar.gz "tool 1.0")
OTHER=$(file other.tar.gz "other")
release o/tool v1.0.0 false tool-linux.tar.gz
release o/tool v2.0.0-rc1 true tool-linux.tar.gz

echo "check-pinned-tools.sh: GitHub's digest"
pin "$W/a.yml" o/tool v1.0.0 "$TOOL" digest
expect "a pin equal to GitHub's digest of an asset" 0 "ok: o/tool v1.0.0 tool-linux.tar.gz (digest)" "$W/a.yml"
pin "$W/b.yml" o/tool v9.9.9 "$TOOL" digest
expect "a tag with no release, with gh's reason" 1 "cannot read the release (gh: HTTP 404)" "$W/b.yml"
pin "$W/c.yml" o/tool v2.0.0-rc1 "$TOOL" digest
expect "a prerelease" 1 "a draft or a prerelease" "$W/c.yml"
pin "$W/d.yml" o/tool v1.0.0 "$OTHER" digest
expect "a SHA-256 that is no asset's digest" 1 "no asset of the release has GitHub's digest" "$W/d.yml"
expect "the same pin in two files is checked once" 0 "All 1 pinned tools" "$W/a.yml" "$W/a.yml"
expect "--expect: the expected number of pins" 0 "All 1 pinned tools" --expect 1 "$W/a.yml"
printf 'env:\n  TOOL_VERSION: v1.0.0\n  TOOL_HASH: %s\n' "$TOOL" > "$W/a0.yml"
expect "--expect: a pin renamed out of the *_SHA256 form is not found" 1 "found 0 pinned tool(s), expected 1" --expect 1 "$W/a0.yml"
expect "--expect without a number" 1 "usage:" --expect x "$W/a.yml"

echo "check-pinned-tools.sh: the pin's form"
printf 'env:\n  TOOL_VERSION: v1.0.0\n  TOOL_SHA256: %s\n' "$TOOL" > "$W/e.yml"
expect "a SHA-256 without the renovate comment" 1 "must follow '# renovate:" "$W/e.yml"
printf 'env:\n  # renovate: datasource=github-release-attachments depName=o/tool\n  TOOL_VERSION: v1.0.0\n  TOOL_SHA256: %s\n' "$TOOL" > "$W/f.yml"
expect "a comment without verify=" 1 "must follow '# renovate:" "$W/f.yml"
printf 'env:\n  # renovate: datasource=github-release-attachments depName=o/tool verify=digest\n  TOOL_SHA256: %s\n  TOOL_VERSION: v1.0.0\n' "$TOOL" > "$W/g.yml"
expect "a SHA-256 before its version" 1 "must follow '# renovate:" "$W/g.yml"
printf 'env:\n  # renovate: datasource=github-release-attachments depName=o/tool verify=digest\n  TOOL_TAG: v1.0.0\n  TOOL_SHA256: %s\n' "$TOOL" > "$W/g2.yml"
expect "a SHA-256 after a line that is not *_VERSION" 1 "must follow '# renovate:" "$W/g2.yml"
printf 'env:\n  # renovate: datasource=github-release-attachments depName=o/tool verify=digest\n  TOOL_VERSION: v1.0.0\n  OTHER_SHA256: %s\n' "$TOOL" > "$W/g3.yml"
expect "a SHA-256 after another name's version" 1 "OTHER_SHA256 follows TOOL_VERSION" "$W/g3.yml"
pin "$W/h.yml" o/tool v1.0.0 "$(printf '%s' "$TOOL" | tr a-f A-F)" digest
expect "an uppercase SHA-256" 1 "must follow '# renovate:" "$W/h.yml"
pin "$W/i.yml" o/tool v1.0.0 "${TOOL:0:63}" digest
expect "a short SHA-256" 1 "64 lowercase hex" "$W/i.yml"
pin "$W/j.yml" o/tool v1.0.0 "$TOOL" trust-me
expect "an unknown method" 1 "unknown verify method 'trust-me'" "$W/j.yml"
pin "$W/k.yml" o/tool v1.0.0 "$TOOL" gpg:abc
expect "a gpg fingerprint that is not 40 uppercase hex" 1 "40-character uppercase fingerprint" "$W/k.yml"

echo "check-pinned-tools.sh: attestation"
pin "$W/l.yml" o/tool v1.0.0 "$TOOL" attestation
expect "no attestation" 1 "has no valid attestation from o/tool" "$W/l.yml"
echo "o/tool refs/tags/v0.9.0 $TOOL" >> "$FIX/attested"
expect "an attestation built from another tag does not count" 1 "built from refs/tags/v1.0.0" "$W/l.yml"
echo "o/tool refs/tags/v1.0.0 $TOOL" >> "$FIX/attested"
expect "an attestation from the repository" 0 "(attestation)" "$W/l.yml"
pin "$W/m.yml" p/fork v1.0.0 "$TOOL" attestation
release p/fork v1.0.0 false tool-linux.tar.gz
expect "an attestation from another repository does not count" 1 "has no valid attestation from p/fork" "$W/m.yml"
cp "$WORK/files/other.tar.gz" "$FIX/o/tool/v1.0.0/serve/tool-linux.tar.gz"
expect "a download that differs from GitHub's digest" 1 "as downloaded does not have the pinned SHA-256" "$W/l.yml"
cp "$WORK/files/tool-linux.tar.gz" "$FIX/o/tool/v1.0.0/serve/tool-linux.tar.gz"

echo "check-pinned-tools.sh: release attestation"
pin "$W/r.yml" o/tool v1.0.0 "$TOOL" release
expect "a release that is not immutable (no release attestation)" 1 "is not attested as part of the immutable release v1.0.0" "$W/r.yml"
echo "o/tool v0.9.0 $TOOL" >> "$FIX/released"
expect "an asset attested for another tag does not count" 1 "immutable release v1.0.0" "$W/r.yml"
echo "o/tool v1.0.0 $TOOL" >> "$FIX/released"
expect "an asset of the immutable release" 0 "ok: o/tool v1.0.0 tool-linux.tar.gz (release)" "$W/r.yml"
pin "$W/r2.yml" p/fork v1.0.0 "$TOOL" release
expect "a release attestation of another repository does not count" 1 "is not attested as part of the immutable release" "$W/r2.yml"
cp "$WORK/files/other.tar.gz" "$FIX/o/tool/v1.0.0/serve/tool-linux.tar.gz"
expect "release: a download that differs from GitHub's digest" 1 "as downloaded does not have the pinned SHA-256" "$W/r.yml"
cp "$WORK/files/tool-linux.tar.gz" "$FIX/o/tool/v1.0.0/serve/tool-linux.tar.gz"

echo "check-pinned-tools.sh: checksums file"
printf '%s  tool-linux.tar.gz\n%s  other.tar.gz\n' "$TOOL" "$OTHER" > "$WORK/files/CHECKSUMS"
printf '%s *tool-linux.tar.gz\n' "$TOOL" > "$WORK/files/star.txt"
printf '%s  tool-linux.tar.gz.sig\n' "$TOOL" > "$WORK/files/near.txt"
release o/sums v1.0.0 false tool-linux.tar.gz CHECKSUMS star.txt near.txt
pin "$W/n.yml" o/sums v1.0.0 "$TOOL" checksums:CHECKSUMS
expect "listed in the release's checksums file" 0 "(checksums:CHECKSUMS)" "$W/n.yml"
pin "$W/o.yml" o/sums v1.0.0 "$TOOL" checksums:star.txt
expect "listed in binary mode (*name)" 0 "(checksums:star.txt)" "$W/o.yml"
pin "$W/p.yml" o/sums v1.0.0 "$TOOL" checksums:near.txt
expect "listed only for another file name" 1 "near.txt does not list" "$W/p.yml"
pin "$W/q.yml" o/sums v1.0.0 "$TOOL" checksums:SHA256SUMS
expect "a checksums file the release lacks" 1 "the release has no SHA256SUMS" "$W/q.yml"

echo "check-pinned-tools.sh: gpg"
if command -v gpg >/dev/null 2>&1; then
    G="$WORK/gnupg"; mkdir -m 700 "$G"
    gen() { gpg --homedir "$G" --batch --quiet --passphrase '' --quick-gen-key "$1" ed25519 sign never 2>/dev/null
            gpg --homedir "$G" --batch --with-colons --fingerprint "$1" 2>/dev/null | awk -F: '$1 == "fpr" { print $10; exit }'; }
    FPR=$(gen "Signer <s@example.org>")
    BAD=$(gen "Someone Else <e@example.org>")
    { echo "Header text a key file may carry before the armor."; gpg --homedir "$G" --armor --export "$FPR"; } > "$WORK/keys/$FPR.asc"
    gpg --homedir "$G" --armor --export "$BAD" > "$WORK/keys/$BAD.asc"
    sign() { gpg --homedir "$G" --batch --quiet --yes --local-user "$1" --clearsign --output "$3" "$2"; }
    printf '%s  tool-linux\n' "$TOOL" > "$WORK/sums"
    cp "$WORK/files/tool-linux.tar.gz" "$WORK/files/tool-linux"
    sign "$FPR" "$WORK/sums" "$WORK/files/sha256sums.txt.asc"
    release o/gpg v1.0.0 false tool-linux sha256sums.txt.asc
    pin "$W/r.yml" o/gpg v1.0.0 "$TOOL" "gpg:$FPR"
    expect "signed by the pinned key" 0 "(gpg:$FPR)" "$W/r.yml"
    pin "$W/s.yml" o/gpg v1.0.0 "$TOOL" "gpg:$BAD"
    expect "signed by a key other than the pinned one" 1 "is not validly signed by $BAD" "$W/s.yml"
    # A key file that also holds the signer's key: gpg verifies the signature, and only the fingerprint check
    # tells it is not by the pinned key.
    gpg --homedir "$G" --armor --export "$BAD" "$FPR" > "$WORK/keys/$BAD.asc"
    expect "valid, but by another key in the pinned key's file" 1 "is not validly signed by $BAD" "$W/s.yml"
    sed 's/tool-linux$/tool-linux2/' "$FIX/o/gpg/v1.0.0/serve/sha256sums.txt.asc" > "$WORK/t" && mv "$WORK/t" "$FIX/o/gpg/v1.0.0/serve/sha256sums.txt.asc"
    expect "a signed file changed after signing" 1 "is not validly signed by $FPR" "$W/r.yml"
    printf '%s  something-else\n' "$TOOL" > "$WORK/sums"
    sign "$FPR" "$WORK/sums" "$FIX/o/gpg/v1.0.0/serve/sha256sums.txt.asc"
    expect "signed, but not listing the asset" 1 "does not list $TOOL for tool-linux" "$W/r.yml"
    pin "$W/u.yml" o/gpg v1.0.0 "$TOOL" "gpg:0000000000000000000000000000000000000000"
    expect "no key file for the fingerprint" 1 "no key file" "$W/u.yml"
    printf '%s  tool-linux\n' "$TOOL" > "$WORK/sums"
    sign "$FPR" "$WORK/sums" "$FIX/o/gpg/v1.0.0/serve/sha256sums.txt.asc"
    { cat "$FIX/o/gpg/v1.0.0/serve/sha256sums.txt.asc"; printf -- '-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n%s  tool-linux\n' "$OTHER"; } > "$WORK/two.asc"
    cp "$WORK/two.asc" "$FIX/o/gpg/v1.0.0/serve/sha256sums.txt.asc"
    expect "a second message appended to the signed file" 1 "not exactly one signed message" "$W/r.yml"
    # Unsigned text around the one message: gpg verifies the signature and writes only the signed text to
    # --output, so the asset's line outside it never reaches lists() (verified with gpg 2.4.4).
    printf '%s  something-else\n' "$TOOL" > "$WORK/around"
    sign "$FPR" "$WORK/around" "$WORK/around.asc"
    { printf '%s  tool-linux\n' "$TOOL"; cat "$WORK/around.asc"; } > "$FIX/o/gpg/v1.0.0/serve/sha256sums.txt.asc"
    expect "the asset's line unsigned, before the signed message" 1 "does not list $TOOL for tool-linux" "$W/r.yml"
    { cat "$WORK/around.asc"; printf '%s  tool-linux\n' "$TOOL"; } > "$FIX/o/gpg/v1.0.0/serve/sha256sums.txt.asc"
    expect "the asset's line unsigned, after the signature" 1 "does not list $TOOL for tool-linux" "$W/r.yml"
    # An unsigned armored message (`gpg --store`) next to the signed one: before it, gpg writes its text to
    # --output; either way gpg exits non-zero, and that exit refuses it.
    printf '%s  tool-linux\n' "$TOOL" > "$WORK/literal"
    gpg --homedir "$G" --batch --quiet --yes --armor --store --output "$WORK/literal.asc" "$WORK/literal"
    cat "$WORK/literal.asc" "$WORK/around.asc" > "$FIX/o/gpg/v1.0.0/serve/sha256sums.txt.asc"
    expect "an unsigned armored message before the signed one" 1 "is not validly signed by $FPR" "$W/r.yml"
    cat "$WORK/around.asc" "$WORK/literal.asc" > "$FIX/o/gpg/v1.0.0/serve/sha256sums.txt.asc"
    expect "an unsigned armored message after the signed one" 1 "is not validly signed by $FPR" "$W/r.yml"
    sign "$FPR" "$WORK/sums" "$FIX/o/gpg/v1.0.0/serve/sha256sums.txt.asc"
    cp "$WORK/keys/$FPR.asc" "$WORK/good.asc"
    printf -- '-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nnot a key\n-----END PGP PUBLIC KEY BLOCK-----\n' > "$WORK/keys/$FPR.asc"
    expect "a key file gpg cannot import" 1 "gpg cannot import" "$W/r.yml"
    cp "$WORK/good.asc" "$WORK/keys/$FPR.asc"
    SOON=$(gpg --homedir "$G" --batch --quiet --passphrase '' --quick-gen-key "Soon <soon@example.org>" ed25519 sign 10d 2>/dev/null
           gpg --homedir "$G" --batch --with-colons --fingerprint "Soon <soon@example.org>" 2>/dev/null | awk -F: '$1 == "fpr" { print $10; exit }')
    gpg --homedir "$G" --armor --export "$SOON" > "$WORK/keys/$SOON.asc"
    mkdir -p "$FIX/o/soon/v1.0.0/serve"
    cp "$FIX/o/gpg/v1.0.0/release.json" "$FIX/o/soon/v1.0.0/release.json"
    cp "$FIX/o/gpg/v1.0.0/serve/tool-linux" "$FIX/o/soon/v1.0.0/serve/"
    sign "$SOON" "$WORK/sums" "$FIX/o/soon/v1.0.0/serve/sha256sums.txt.asc"
    pin "$W/v.yml" o/soon v1.0.0 "$TOOL" "gpg:$SOON"
    expect "a key that expires within 30 days passes, with a warning" 0 "::warning file=" "$W/v.yml"
    # A key made in 2020, valid one year, expired today: gpg still verifies its signature (dated 2020 here),
    # reporting EXPKEYSIG, and the checker refuses it.
    OLD=$(gpg --homedir "$G" --batch --quiet --faked-system-time 20200101T000000 --passphrase '' --quick-gen-key "Old <old@example.org>" ed25519 sign 1y 2>/dev/null
          gpg --homedir "$G" --batch --with-colons --fingerprint "Old <old@example.org>" 2>/dev/null | awk -F: '$1 == "fpr" { print $10; exit }')
    gpg --homedir "$G" --armor --export "$OLD" > "$WORK/keys/$OLD.asc"
    mkdir -p "$FIX/o/old/v1.0.0/serve"
    cp "$FIX/o/gpg/v1.0.0/release.json" "$FIX/o/old/v1.0.0/release.json"
    cp "$FIX/o/gpg/v1.0.0/serve/tool-linux" "$FIX/o/old/v1.0.0/serve/"
    gpg --homedir "$G" --batch --quiet --yes --faked-system-time 20200601T000000 --local-user "$OLD" --clearsign \
        --output "$FIX/o/old/v1.0.0/serve/sha256sums.txt.asc" "$WORK/sums"
    pin "$W/x.yml" o/old v1.0.0 "$TOOL" "gpg:$OLD"
    expect "signed by a key that has since expired" 1 "signed by an expired or revoked key" "$W/x.yml"
    # gpg writes a revocation certificate for every key it generates; importing it revokes the key, and the
    # key file committed after that carries the revocation.
    printf '%s  tool-linux\n' "$TOOL" > "$WORK/sums"
    sign "$FPR" "$WORK/sums" "$FIX/o/gpg/v1.0.0/serve/sha256sums.txt.asc"
    expect "signed again by the pinned key (control for the next case)" 0 "(gpg:$FPR)" "$W/r.yml"
    sed 's/^:-----/-----/' "$G/openpgp-revocs.d/$FPR.rev" | gpg --homedir "$G" --batch --quiet --import 2>/dev/null
    gpg --homedir "$G" --armor --export "$FPR" > "$WORK/keys/$FPR.asc"
    expect "signed by a key since revoked" 1 "signed by an expired or revoked key" "$W/r.yml"
else
    echo "  skipped: gpg is not installed (CI's runner runs these)"
fi

echo "check-pinned-tools.sh: which files it reads"
# With no arguments: every workflow and every composite action, as renovate.json's custom manager.
T="$WORK/tree"
mkdir -p "$T/.github/scripts" "$T/.github/workflows" "$T/.github/actions/x"
cp "$SCRIPT" "$T/.github/scripts/"
pin "$T/.github/workflows/w.yml" o/tool v1.0.0 "$TOOL" digest
printf 'runs:\n  using: composite\n  steps:\n    - env:\n        TOOL_SHA256: %s\n      run: true\n' "$TOOL" > "$T/.github/actions/x/action.yml"
out=$(PINNED_TOOLS_KEYS_DIR="$WORK/keys" "$T/.github/scripts/check-pinned-tools.sh" 2>&1); rc=$?
if [ "$rc" -ne 0 ] && [[ "$out" == *"file=.github/actions/x/action.yml"* ]] && [[ "$out" == *"o/tool v1.0.0"* ]]; then
    ok "a composite action's pin is read too (here refused: no renovate comment)"
else
    fail "a composite action's pin: exit $rc: $out"
fi

if [ "$failures" -gt 0 ]; then echo "$failures pinned-tools test(s) failed"; exit 1; fi
echo "All pinned-tools tests passed."
