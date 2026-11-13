#!/usr/bin/env bash
# ============================================================
# Tests for watch-signing-keys.sh (backlog #0-152), offline: gh is a stub on PATH that serves a publisher's key
# file from a temporary directory; the keys are generated here with the real gpg. Without gpg the cases are
# reported as skipped (CI's runner has it).
# Run: .github/scripts/test-watch-signing-keys.sh   (also run by ci.yml's lint-workflows job)
# ============================================================
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/watch-signing-keys.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }

echo "watch-signing-keys.sh"
if ! command -v gpg >/dev/null 2>&1; then
    echo "  skipped: gpg is not installed (CI's runner runs these)"
    exit 0
fi

# gh stub: `api -H <accept> repos/<repo>/contents/<path>` serves $SRV/<repo>/<path>, or fails as a 404.
mkdir -p "$WORK/bin" "$WORK/srv" "$WORK/keys"
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
[ "$1" = api ] || { echo "gh stub: unexpected $*" >&2; exit 1; }
p=${4#repos/}; r=${p%%/contents/*}; f=${p#*/contents/}
[ -f "$SRV/$r/$f" ] || { echo "HTTP 404" >&2; exit 1; }
cat "$SRV/$r/$f"
STUB
chmod +x "$WORK/bin/gh"
export SRV="$WORK/srv" PATH="$WORK/bin:$PATH"

G="$WORK/gnupg"; mkdir -m 700 "$G"
gen() { gpg --homedir "$G" --batch --quiet --passphrase '' --quick-gen-key "$1" ed25519 sign "$2" 2>/dev/null
        gpg --homedir "$G" --batch --with-colons --fingerprint "$1" 2>/dev/null | awk -F: '$1 == "fpr" { print $10; exit }'; }
KEY=$(gen "Pub <p@example.org>" 1y)
NEW=$(gen "Pub next <n@example.org>" 2y)
gpg --homedir "$G" --armor --export "$KEY" > "$WORK/keys/$KEY.asc"
printf 'o/cli keys/signing.pgp\n' > "$WORK/keys/$KEY.source"
mkdir -p "$SRV/o/cli/keys"

# expect <name> <exit> <text the output must contain>
expect() {
    local out rc
    out=$("$SCRIPT" "$WORK/keys" 2>&1); rc=$?
    if [ "$rc" -ne "$2" ]; then fail "$1: exit $rc, wanted $2: $out"; return; fi
    case "$out" in *"$3"*) ok "$1" ;; *) fail "$1: output does not contain '$3': $out" ;; esac
}

cp "$WORK/keys/$KEY.asc" "$SRV/o/cli/keys/signing.pgp"
expect "the publisher's key is the committed one" 0 "Every signing key is as its publisher publishes it"
gpg --homedir "$G" --armor --export "$NEW" > "$SRV/o/cli/keys/signing.pgp"
expect "the publisher rotated to a new key" 3 "rotated: o/cli:keys/signing.pgp has a new key $NEW"
gpg --homedir "$G" --armor --export "$KEY" "$NEW" > "$SRV/o/cli/keys/signing.pgp"
expect "the publisher serves the old and the new key" 3 "has a new key $NEW"
gpg --homedir "$G" --batch --quiet --quick-set-expire "$KEY" 3y 2>/dev/null
gpg --homedir "$G" --armor --export "$KEY" > "$SRV/o/cli/keys/signing.pgp"
expect "the publisher extended the committed key" 3 "extended: o/cli:keys/signing.pgp has $KEY expiring"
rm "$SRV/o/cli/keys/signing.pgp"
expect "the publisher's file cannot be fetched: fails closed" 1 "cannot fetch o/cli:keys/signing.pgp (gh: HTTP 404)"
printf 'not a key\n' > "$SRV/o/cli/keys/signing.pgp"
expect "the publisher's file holds no key: fails closed" 1 "holds no key gpg can read"
cp "$WORK/keys/$KEY.asc" "$SRV/o/cli/keys/signing.pgp"
mv "$WORK/keys/$KEY.source" "$WORK/source.bak"
expect "a key without its source file: fails closed" 1 "naming where the publisher keeps the key"
out=$("$SCRIPT" --sources-only "$WORK/keys" 2>&1); rc=$?
if [ "$rc" -eq 1 ] && [[ "$out" == *"naming where the publisher keeps the key"* ]]; then ok "--sources-only: a key without its source fails"
else fail "--sources-only without a source: exit $rc: $out"; fi
mv "$WORK/source.bak" "$WORK/keys/$KEY.source"
out=$("$SCRIPT" --sources-only "$WORK/keys" 2>&1); rc=$?
if [ "$rc" -eq 0 ] && [[ "$out" == *"Every signing key names where its publisher keeps it"* ]]; then ok "--sources-only: every key has its source"
else fail "--sources-only with every source: exit $rc: $out"; fi
expect "back to the committed key (control)" 0 "Every signing key is as its publisher publishes it"
# The publisher revokes the committed key (gpg wrote a revocation certificate when it made the key): same
# fingerprint, same expiry, so only the validity tells (review round 1 of #0-152, sec-3c7a).
sed 's/^:-----/-----/' "$G/openpgp-revocs.d/$KEY.rev" | gpg --homedir "$G" --batch --quiet --import 2>/dev/null
gpg --homedir "$G" --armor --export "$KEY" > "$SRV/o/cli/keys/signing.pgp"
expect "the publisher revoked the committed key" 3 "revoked: o/cli:keys/signing.pgp has revoked $KEY"

if [ "$failures" -gt 0 ]; then echo "$failures signing key watch test(s) failed"; exit 1; fi
echo "All signing key watch tests passed."
