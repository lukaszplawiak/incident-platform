#!/usr/bin/env bash
# ============================================================
# Prints the owner's GitHub login: the one admin of .github/repository-settings/repository.json (backlog #0-237,
# ADR-0055 stage 1), read where the caller cannot be steered:
#   owner-login.sh --github <owner/repo>   the file on the repository's main, from GitHub's API (audit-data.sh)
#   owner-login.sh <commit>                the file at a commit of a clone nothing local has touched (factory-guards:
#                                          the pull request's base, in CI's fresh checkout)
#
# Why not the repository's owner (`gh repo view`, `github.repository_owner`): once the repository moves to an
# organisation, that is the organisation's name, which never adds a label or opens a pull request. audit-data.sh would
# then treat every `human:*` label and every escape marker as unverified, and factory-guards' "Changes that need the
# owner" would stop recognising the owner's own pull requests.
#
# How true the file is: it is a `.github/` path, which only a pull request the owner reviews changes (CODEOWNERS, the
# ruleset of main, protected paths). GitHub's admins are compared with it only when the owner runs
# `repository-settings.rb check` with the owner's token; CI's read-only token cannot see them (they are listed as
# hidden). So an admin added in GitHub's settings and not in the file goes unseen until the owner's next check
# (review round 1 of #0-237, arc-9a04).
#
# Never from local state an agent's code can write: a local ref (`git update-ref refs/remotes/origin/main …`), a replace
# ref or a graft would let code run in a session (a test, #0-116) name another owner, so the audit reads GitHub, and the
# commit form reads the blob itself (`cat-file`, no textconv) with replace refs and grafts off (review round 1 of
# #0-237, arc-3c71).
#
# Exactly one admin, or no owner: with two, which one's label or pull request counts is a decision nobody made, and
# a wrong owner is the error that costs (fail closed, as audit-data.sh's "owner unknown").
#
# It lives in scripts/factory/ with the factory's other helpers, which CI calls (as _marker.sh, _protected.sh), so it
# moves with the factory in ADR-0055 stage 4 (review round 2 of #0-237). Known limit of --github: audit-data.sh takes
# <owner/repo> from `gh repo view`, i.e. the clone's remote, which code run in a session can repoint (as before this
# change); stage 4's dispatcher names the repository from its environment instead.
#
# Exit 0 and the login on stdout; 1 and the reason on stderr. --github needs gh with a token.
# Tests: .github/scripts/test-owner-login.sh
# ============================================================
set -uo pipefail

FILE=.github/repository-settings/repository.json
command -v jq >/dev/null 2>&1 || { echo "jq is not installed" >&2; exit 1; }
if [ "${1:-}" = --github ]; then
    repo=${2:-}
    # Each part starts with a letter or digit: no `.` or `..` that would take the API path outside one repository
    # (review round 3 of #0-237).
    [[ "$repo" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || { echo "usage: owner-login.sh --github <owner/repo>" >&2; exit 1; }
    where="$repo's main"
    # refs/heads/main, not `main`: no ruleset covers tags, so any account with Write (the machine account too) could
    # push a tag `main` that a bare name might resolve to (review round 2 of #0-237).
    if ! json=$(gh api -H 'Accept: application/vnd.github.raw' "repos/$repo/contents/$FILE?ref=refs/heads/main" 2>/dev/null </dev/null); then
        echo "cannot read $FILE on $where from GitHub" >&2; exit 1
    fi
else
    commit=${1:-}
    [ -n "$commit" ] || { echo "usage: owner-login.sh <commit> | --github <owner/repo>" >&2; exit 1; }
    where=$commit
    if ! json=$(GIT_NO_REPLACE_OBJECTS=1 GIT_GRAFT_FILE=/dev/null git --no-replace-objects cat-file blob "$commit:$FILE" 2>/dev/null); then
        echo "no $FILE at $where" >&2; exit 1
    fi
fi
if ! admins=$(printf '%s' "$json" | jq -r '.admins | if type == "array" then .[] | if type == "string" then . else error("not a login") end else error("not a list") end' 2>/dev/null); then
    echo "$FILE at $where has no \"admins\" list of logins" >&2; exit 1
fi
count=$(printf '%s' "$admins" | grep -c . || true)
if [ "$count" -ne 1 ]; then
    echo "$FILE at $where names $count admins, not one: the owner is ambiguous" >&2; exit 1
fi
# A GitHub login: letters, digits and single hyphens, not at either end, at most 39 characters.
if [ "${#admins}" -gt 39 ] || ! [[ "$admins" =~ ^[A-Za-z0-9]+(-[A-Za-z0-9]+)*$ ]]; then
    echo "$FILE at $where names \"$admins\", which is not a GitHub login" >&2; exit 1
fi
printf '%s\n' "$admins"
