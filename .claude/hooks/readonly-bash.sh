#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash) in the frontmatter of the review and audit agents: an allow-list of read-only
# commands. The `tools:` field of an agent cannot restrict Bash to some commands (a specifier there does
# not narrow the tool), so this hook does.
#
#   readonly-bash.sh            git diff/log/show/status/rev-parse/merge-base/ls-files/blame/cat-file
#   readonly-bash.sh --k8s      ... plus `kubectl kustomize` and `kubeconform` (review-k8s)
#   readonly-bash.sh --gh-read  ... plus `gh pr list/view/diff`, `gh issue list/view`, `gh run list/view` (auditors)
#   readonly-bash.sh --architect ... plus `git add` of .ai/decisions/ and .ai/work/ paths, and `git commit`
#
# One command per call: no pipes, chaining, redirects or substitutions. Fails closed.
# Note: frontmatter hooks run only in a trusted workspace and not in `claude -p` (docs); in a headless
# run the session-level hooks in settings.autopilot.json are the guard.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

mode=${1:-}
input=$(cat)
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
[ "$rc" -eq 3 ] && block "jq or python3 is required for this agent's Bash guard"
[ -n "$cmd" ] || exit 0

case "$cmd" in
    *'|'*|*';'*|*'&'*|*'>'*|*'<'*|*'$('*|*'`'*|*$'\n'*)
        block "one read-only command per call: no pipes, chaining, redirects or substitutions" ;;
esac

case "$cmd" in
    *--output*|*--ext-diff*|*--textconv*|*--no-index*) block "git option not allowed here (it can write files, run programs or read outside the repository)" ;;
esac

set -f  # no glob expansion while splitting the command into words
# shellcheck disable=SC2086
set -- $cmd
case "$1 ${2:-}" in
    "git diff"|"git log"|"git show"|"git status"|"git rev-parse"|"git merge-base"|"git ls-files"|"git blame"|"git cat-file")
        exit 0 ;;
    "git branch")
        case "$cmd" in
            *" -d"*|*" -D"*|*" --delete"*|*" -m"*|*" -M"*|*" --move"*|*" -c"*|*" -C"*|*" --copy"*|*" -u"*|*" --set-upstream"*|*" -f"*|*" --force"*)
                block "git branch may only list branches" ;;
        esac
        [ "$#" -le 2 ] || case "${3:-}" in --list|-a|-r|--all|--remotes|--contains|--merged) ;; *) block "git branch may only list branches" ;; esac
        exit 0 ;;
esac
if [ "$mode" = --k8s ]; then
    case "$1 ${2:-}" in "kubectl kustomize") exit 0 ;; esac
    [ "$1" = kubeconform ] && exit 0
fi
if [ "$mode" = --gh-read ]; then
    case "$1 ${2:-} ${3:-}" in
        "gh pr list"|"gh pr view"|"gh pr diff"|"gh issue list"|"gh issue view"|"gh run list"|"gh run view") exit 0 ;;
    esac
fi
if [ "$mode" = --architect ]; then
    if [ "$1 ${2:-}" = "git add" ]; then
        shift 2
        [ "$#" -gt 0 ] || block "git add needs explicit paths"
        for path in "$@"; do
            case "$path" in
                .ai/decisions/*|.ai/work/*) case "$path" in *..*) block "path with '..'";; esac ;;
                *) block "the architect may stage only .ai/decisions/ and .ai/work/ paths, not $path" ;;
            esac
        done
        exit 0
    fi
    if [ "$1 ${2:-}" = "git commit" ]; then
        case "$cmd" in *" -a"*|*"--all"*|*"--amend"*|*" -n"*|*"--no-verify"*) block "plain git commit only (no -a, --amend, --no-verify)";; esac
        exit 0
    fi
fi
block "not on this agent's read-only allow-list: $1 ${2:-}"
