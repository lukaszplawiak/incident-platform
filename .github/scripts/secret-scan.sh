#!/usr/bin/env bash
# ============================================================
# Scans the commits of a range for secrets with betterleaks (backlog #0-109, ADR-0036).
#
#   secret-scan.sh <betterleaks binary> <from> <to>
#
# Scans what every commit in <from>..<to> adds (`git log -p -m`), not the tree at <to>: a secret added in
# one commit of a pull request and deleted in the next is in neither tree, but the merge commit keeps both
# commits in main's history, public for good, and the commit that added it is scanned. `-m` gives a merge
# commit its diff against each parent: without it `git log -p` shows none, and a secret written while
# resolving a conflict (and deleted later) passed unseen (security review of #0-109; a case of the tests).
# The same finding can then be reported once per parent. ci.yml's secret-scan job passes the pull
# request's base and head, or a push's before and after. The rules are betterleaks' own defaults, generic
# passwords and keys included (what GitHub's push protection does not see: the JWT secret, the AES keys,
# the database passwords), and nothing in the repository can change them:
#   - a .betterleaks.toml or .gitleaks.toml at the root, which betterleaks would read in place of its
#     defaults, fails the scan: a pull request could otherwise switch a rule off in the change it
#     scans. The config variables (BETTERLEAKS_CONFIG[_TOML], GITLEAKS_CONFIG[_TOML]) are unset here;
#   - an ignore file at the root (.betterleaksignore or .gitleaksignore, findings by fingerprint) fails it
#     too: betterleaks reads the root's even when --gitleaks-ignore-path names another directory (found by
#     test-secret-scan.sh); that flag names an empty one, so no other is read either.
# A finding that is no secret (a test's fixed password, a placeholder) is allowed on its own line,
# with `gitleaks:allow` (or `betterleaks:allow`) and the reason in the same comment, where the reviewer
# sees both. A line allowed in one commit and deleted in a later one is in no final diff, so every commit
# of the range that adds or removes an allow comment gets a ::warning naming it and the file (not the
# line, which may hold the secret): the reviewer sees each one in the checks. The commits already on main
# were reviewed once, when this check was added (ADR-0036).
# Findings are redacted: one ::error annotation each (rule, file, line, commit), never the value.
# Fails closed: a range end that is no commit, an all-zero <from> (a new branch or a force-push: no
# range to scan), and a scanner that fails, all fail. Validation against live APIs is off unless
# --validation is given, and this script never gives it: it would send what it finds to the provider.
# Needs git and jq. Run from the repository root. Tests: test-secret-scan.sh.
# ============================================================
set -euo pipefail

[ $# -eq 3 ] || { echo "usage: $0 <betterleaks binary> <from> <to>" >&2; exit 2; }
scanner=$1 from=$2 to=$3

[ -x "$scanner" ] || { echo "::error::$scanner is not an executable betterleaks (backlog #0-109)"; exit 2; }
if [[ "$from" =~ ^0+$ ]]; then
    echo "::error::no range to scan: the push has no previous commit (a new branch or a force-push); the ruleset of main allows neither (backlog #0-109)"
    exit 1
fi
for end in "$from" "$to"; do
    if ! git rev-parse --quiet --verify "$end^{commit}" >/dev/null; then
        echo "::error::$end is not a commit of this clone (fetch the full history: fetch-depth 0) (backlog #0-109)"
        exit 1
    fi
done
for config in .betterleaks.toml .gitleaks.toml; do
    if [ -e "$config" ]; then
        echo "::error file=$config::$config would replace the scanner's default rules; a finding that is no secret is allowed on its line with gitleaks:allow and its reason (backlog #0-109, ADR-0036)"
        exit 1
    fi
done
for ignore in .betterleaksignore .gitleaksignore; do
    if [ -e "$ignore" ]; then
        echo "::error file=$ignore::$ignore would hide findings by fingerprint, out of the reviewer's sight; a finding that is no secret is allowed on its line with gitleaks:allow and its reason (backlog #0-109, ADR-0036)"
        exit 1
    fi
done
unset BETTERLEAKS_CONFIG BETTERLEAKS_CONFIG_TOML GITLEAKS_CONFIG GITLEAKS_CONFIG_TOML

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/no-ignore-file"
report=$work/report.json

# Every allow comment the range adds or removes (-G matches either), by commit and file. `leaks:allow` is
# both spellings in a basic regular expression, which is what -G reads (no alternation on BSD's). A commit's
# line starts with a tab, which no path line can: git quotes a path holding a control character (a file
# named `C 0` was taken for a commit line before, security review of #0-109).
git log -m --format='%x09%H' --name-only -G'leaks:allow' "$from..$to" -- \
    | awk '/^\t[0-9a-f]+$/ { c = substr($0, 2, 12); next } NF { print c "\t" $0 }' | sort -u \
    | while IFS=$'\t' read -r commit file; do
        echo "::warning file=$file::commit $commit adds or removes a gitleaks:allow comment in $file: read the line and its reason in that commit (backlog #0-109)"
    done

# --exit-code 3 for findings, so any other non-zero exit is the scanner failing, not a finding.
rc=0
"$scanner" git . --log-opts="-m $from..$to" --gitleaks-ignore-path "$work/no-ignore-file" \
    --redact --no-banner --no-color --log-level warn \
    --report-format json --report-path "$report" --exit-code 3 || rc=$?
commits=$(git rev-list --count "$from..$to")
case "$rc" in
    0)
        echo "No secret in the $commits commit(s) of $from..$to."
        ;;
    3)
        jq -r '.[] | "::error file=\(.File),line=\(.StartLine)::\(.RuleID) in commit \(.Commit[0:12]): a secret, or what looks like one. A real one is rotated now (the branch is public); one that is not is allowed on its line with gitleaks:allow and the reason (backlog #0-109)"' "$report"
        echo "$(jq length "$report") finding(s) in the $commits commit(s) of $from..$to."
        exit 1
        ;;
    *)
        echo "::error::betterleaks failed (exit $rc) (backlog #0-109)"
        exit 1
        ;;
esac
