#!/usr/bin/env bash
# ============================================================
# Tests for scripts/factory/owner-login.sh (backlog #0-237), in a temporary repository: the owner is the one admin of
# repository.json at the commit given (never the working tree's, a replace ref's or a graft's) or on GitHub's main
# (`--github`, gh a stub serving a file), and anything but exactly one login is no owner.
# Run: .github/scripts/test-owner-login.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")/../.." && pwd)/scripts/factory/owner-login.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }

echo "owner-login.sh"
cd "$WORK" || exit 1
git init -q -b main
mkdir -p .github/repository-settings
F=.github/repository-settings/repository.json
# at <json>: commit the file with this content, print the commit
at() { printf '%s\n' "$1" > "$F"; git add "$F"; git -c user.email=t@t -c user.name=t commit -q -m x; git rev-parse HEAD; }
# expect <name> <commit> <exit> <text stdout or stderr must equal (exit 0) or contain (exit 1)>
expect() {
    local out err rc
    out=$("$SCRIPT" "$2" 2>"$WORK/err"); rc=$?; err=$(cat "$WORK/err")
    if [ "$rc" -ne "$3" ]; then fail "$1: exit $rc, wanted $3 (out: $out, err: $err)"; return; fi
    if [ "$3" -eq 0 ]; then
        if [ "$out" = "$4" ]; then ok "$1"; else fail "$1: printed '$out', wanted '$4'"; fi
    else
        case "$err" in *"$4"*) ok "$1" ;; *) fail "$1: stderr '$err' lacks '$4'" ;; esac
        [ -z "$out" ] || fail "$1: printed '$out' on a failure"
    fi
}

one=$(at '{"settings":{},"admins":["lukaszplawiak"],"repository_secrets":[]}')
expect "the one admin is the owner" "$one" 0 lukaszplawiak
# A branch adds its own login: the working tree and a later commit say so, the commit given does not.
printf '{"admins":["bot"]}\n' > "$F"
expect "the working tree does not count, only the commit given" "$one" 0 lukaszplawiak
later=$(at '{"admins":["bot"]}')
expect "a later commit is read only when it is the one given" "$later" 0 bot
# Code run in a session replaces the blob, or grafts the commit onto another parent: neither changes what is read
# (review round 1, arc-3c71).
blob=$(git rev-parse "$one:$F")
git replace "$blob" "$(printf '{"admins":["forged"]}\n' | git hash-object -w --stdin)"
expect "a replace ref of the file's blob is not honoured" "$one" 0 lukaszplawiak
git replace -d "$blob" >/dev/null
git replace "$one" "$later"
expect "a replace ref of the commit is not honoured" "$one" 0 lukaszplawiak
git replace -d "$one" >/dev/null
printf '.github/repository-settings/repository.json diff=forge\n' > .gitattributes
git config diff.forge.textconv 'sed s/lukaszplawiak/forged/'
expect "a textconv driver is not applied" "$one" 0 lukaszplawiak
rm .gitattributes; git config --unset diff.forge.textconv
expect "two admins: no owner"        "$(at '{"admins":["lukaszplawiak","bot"]}')" 1 "names 2 admins, not one"
expect "no admin: no owner"          "$(at '{"admins":[]}')"                      1 "names 0 admins, not one"
expect "no admins key: no owner"     "$(at '{"settings":{}}')"                    1 "no \"admins\" list"
expect "admins not a list: no owner" "$(at '{"admins":"lukaszplawiak"}')"          1 "no \"admins\" list"
expect "a number is no login"        "$(at '{"admins":[5]}')"                     1 "no \"admins\" list"
expect "not JSON: no owner"          "$(at 'admins: [x]')"                        1 "no \"admins\" list"
expect "not a login: no owner"       "$(at '{"admins":["a b"]}')"                 1 "is not a GitHub login"
expect "a leading hyphen is no login" "$(at '{"admins":["-a"]}')"                 1 "is not a GitHub login"
expect "40 characters is no login"   "$(at "{\"admins\":[\"$(printf 'a%.0s' $(seq 40))\"]}")" 1 "is not a GitHub login"
git rm -q "$F"; git -c user.email=t@t -c user.name=t commit -q -m rm
expect "a commit without the file: no owner" "$(git rev-parse HEAD)" 1 "no $F at"
expect "an unknown commit: no owner" "refs/remotes/origin/main" 1 "no $F at refs/remotes/origin/main"
if "$SCRIPT" >/dev/null 2>&1; then fail "no argument: exit 0"; else ok "no argument: refused"; fi

echo "owner-login.sh --github"
# gh stub: serves $SRV/<owner>/<repo>.json for `api -H <accept> repos/<owner>/<repo>/contents/<file>?ref=refs/heads/main`, else
# fails as a 404. Anything else it is asked is a failure of the test.
mkdir -p "$WORK/bin" "$WORK/srv/org"
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
want="api -H Accept: application/vnd.github.raw repos/"
case "$*" in "$want"*"/contents/.github/repository-settings/repository.json?ref=refs/heads/main") ;; *) echo "gh stub: unexpected $*" >&2; exit 2 ;; esac
p=${4#repos/}; r=${p%%/contents/*}
[ -f "$SRV/$r.json" ] || { echo "HTTP 404" >&2; exit 1; }
cat "$SRV/$r.json"
STUB
chmod +x "$WORK/bin/gh"
# gh_expect <name> <repo> <exit> <text>: as expect, through --github
gh_expect() {
    local out err rc
    out=$(SRV="$WORK/srv" PATH="$WORK/bin:$PATH" "$SCRIPT" --github "$2" 2>"$WORK/err"); rc=$?; err=$(cat "$WORK/err")
    if [ "$rc" -ne "$3" ]; then fail "$1: exit $rc, wanted $3 (out: $out, err: $err)"; return; fi
    if [ "$3" -eq 0 ]; then
        if [ "$out" = "$4" ]; then ok "$1"; else fail "$1: printed '$out', wanted '$4'"; fi
    else
        case "$err" in *"$4"*) ok "$1" ;; *) fail "$1: stderr '$err' lacks '$4'" ;; esac
    fi
}
printf '{"admins":["lukaszplawiak"]}\n' > "$WORK/srv/org/r.json"
# The local clone says otherwise: --github does not read it.
git update-ref refs/remotes/origin/main "$later"
gh_expect "the one admin on GitHub's main, whatever the local refs say" org/r 0 lukaszplawiak
gh_expect "GitHub cannot be read: no owner" org/missing 1 "cannot read"
printf '{"admins":["lukaszplawiak","bot"]}\n' > "$WORK/srv/org/r.json"
gh_expect "two admins on GitHub's main: no owner" org/r 1 "names 2 admins, not one"
gh_expect "a repository that is no owner/repo is refused" 'org/r?ref=x' 1 "usage"
gh_expect "no repository is refused" "" 1 "usage"
gh_expect "a part that is .. is refused" '../r' 1 "usage"
gh_expect "a part that is . is refused" 'org/.' 1 "usage"

if [ "$failures" -gt 0 ]; then echo "$failures owner-login test(s) failed"; exit 1; fi
echo "All owner-login tests passed."
