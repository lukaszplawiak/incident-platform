#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash) in the frontmatter of the review and audit agents: an allow-list of read-only
# commands. The `tools:` field of an agent cannot restrict Bash to some commands (a specifier there does
# not narrow the tool), so this hook does.
#
#   readonly-bash.sh            git diff/log/show/status/rev-parse/merge-base/ls-files/blame/cat-file/grep
#                               (git grep: tracked files only, never -O, --no-index, --untracked, -f)
#   readonly-bash.sh --k8s      ... plus `kubectl kustomize` and `kubeconform` (review-k8s)
#   readonly-bash.sh --gh-read  ... plus `gh pr list/view/diff`, `gh issue list/view`, `gh run list/view` (auditors)
#   readonly-bash.sh --architect ... plus `git add` of .ai/decisions/ and .ai/work/ paths, and `git commit`
#
# One command per call: no pipes, chaining, redirects or substitutions. Fails closed.
# Every refusal an agent can hit while reading says what to use instead (backlog #0-123): an agent that is
# only told "no" spends a turn guessing, and one gave up on `git diff HEAD` and reviewed half a change. Files
# are read with the Read tool and searched with `git grep -e … -- <paths>` (tracked files only, so never a
# secret; the Grep and Glob tools do not exist in every Claude Code version, .ai/rules/review/_common.md), so
# `cat` and plain `grep` stay off the list on purpose; see .claude/skills/review-procedure/SKILL.md, "Tools".
# Note: frontmatter hooks run only in a trusted workspace and not in `claude -p` (docs); in a headless
# run the session-level hooks in settings.autopilot.json are the guard.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

mode=${1:-}
HINT="Read a file with the Read tool. Search with git grep -n -e <pattern> -e <other> -- <paths> (each alternative its own -e, not a|b) and list files with git ls-files; one command per call, from the repository root (no git -C)."
case "$mode" in
    --k8s)       HINT="$HINT Also allowed here: kubectl kustomize <dir>, kubeconform." ;;
    --gh-read)   HINT="$HINT Also allowed here: gh pr list/view/diff, gh issue list/view, gh run list/view." ;;
    --architect) HINT="$HINT Also allowed here: git add of .ai/decisions/ and .ai/work/ paths, plain git commit." ;;
esac
input=$(cat)
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
[ "$rc" -eq 3 ] && block "jq or python3 is required for this agent's Bash guard"
[ -n "$cmd" ] || exit 0

case "$cmd" in
    *'|'*|*';'*|*'&'*|*'>'*|*'<'*|*'$('*|*'`'*|*$'\n'*)
        block "one read-only command per call: no pipes, chaining, redirects or substitutions (a | inside quotes counts too). $HINT" ;;
esac

case "$cmd" in
    *--output*|*--ext-diff*|*--textconv*|*--no-index*) block "git option not allowed here (it can write files, run programs or read outside the repository). $HINT" ;;
esac

why=$(git_grep_unsafe "$cmd"); [ -z "$why" ] || block "$why. $HINT"

set -f  # no glob expansion while splitting the command into words
# shellcheck disable=SC2086
set -- $cmd
case "$1 ${2:-}" in
    "git diff"|"git log"|"git show"|"git status"|"git rev-parse"|"git merge-base"|"git ls-files"|"git blame"|"git cat-file"|"git grep")
        exit 0 ;;
    "git branch")
        case "$cmd" in
            *" -d"*|*" -D"*|*" --delete"*|*" -m"*|*" -M"*|*" --move"*|*" -c"*|*" -C"*|*" --copy"*|*" -u"*|*" --set-upstream"*|*" -f"*|*" --force"*)
                block "git branch may only list branches. $HINT" ;;
        esac
        [ "$#" -le 2 ] || case "${3:-}" in --list|-a|-r|--all|--remotes|--contains|--merged) ;; *) block "git branch may only list branches. $HINT" ;; esac
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
block "not on this agent's read-only allow-list: $1 ${2:-}. $HINT"
