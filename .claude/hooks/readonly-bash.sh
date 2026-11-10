#!/usr/bin/env bash
# ============================================================
# PreToolUse (Bash) in the frontmatter of the review and audit agents: an allow-list of read-only
# commands. The `tools:` field of an agent cannot restrict Bash to some commands (a specifier there does
# not narrow the tool), so this hook does.
#
#   readonly-bash.sh            git diff/log/show/status/rev-parse/merge-base/ls-files/blame/cat-file/grep
#                               (git grep: tracked files only, never -O, --no-index, --untracked, -f)
#   readonly-bash.sh --gh-read  ... plus `gh pr list/view/diff`, `gh issue list/view`, `gh run list/view` (auditors)
#   readonly-bash.sh --picker   ... plus one `gh pr list` form and `ls .ai/work/0-<n>` (through no symlink), nothing that
#                               writes: the autopilot's picker, which reads PR titles an outsider can write (backlog #0-215)
#   readonly-bash.sh --architect ... plus `git add` of .ai/decisions/ and .ai/work/ paths, and `git commit` in one
#                               form: -m "<subject>", optionally --trailer "Co-Authored-By: <name> <noreply@anthropic.com>"
#   readonly-bash.sh --agent <names> [mode]   the same, at session level, for those agents' calls only (#0-164)
#
# One command per call: no pipes, chaining, redirects or substitutions. Fails closed.
# Every refusal an agent can hit while reading says what to use instead (backlog #0-123): an agent that is
# only told "no" spends a turn guessing, and one gave up on `git diff HEAD` and reviewed half a change. Files
# are read with the Read tool and searched with `git grep -e … -- <paths>` (tracked files only, so never a
# secret; the Grep and Glob tools do not exist in every Claude Code version, .ai/rules/review/_common.md), so
# `cat` and plain `grep` stay off the list on purpose; see .claude/skills/review-procedure/SKILL.md, "Tools".
# Frontmatter hooks are documented for agents of the Agent tool in a trusted workspace, never in `claude -p` nor
# for the agents a Workflow spawns; so both settings files run this hook again with `--agent` (backlog #0-164,
# ADR-0041), and Factory guards checks that the two stay equal (check-hook-entries.rb).
# ============================================================
set -uo pipefail
# Fails closed (backlog #0-162): Claude Code runs the call after any exit status but 2, so a crash (an unset
# variable under set -u, a missing command) and a helper that does not load refuse it here.
trap 'case $? in 0|2) ;; *) echo "BLOCKED by ${0##*/}: the guard failed (exit $?), so the call is refused" >&2; exit 2 ;; esac' EXIT
. "$(dirname "$0")/_lib.sh" || { echo "BLOCKED by ${0##*/}: cannot load _lib.sh" >&2; exit 2; }

# Session-level twin (backlog #0-164): `--agent <names>` first, then the mode; the call of any other agent passes.
only=""
if [ "${1:-}" = --agent ]; then
    [ "$#" -ge 2 ] && [ -n "$2" ] || block "readonly-bash.sh --agent needs agent names"
    only=$2
    shift 2
fi
mode=${1:-}
HINT="Read a file with the Read tool. Search with git grep -n -e <pattern> -e <other> -- <paths> (each alternative its own -e, not a|b) and list files with git ls-files; one command per call, from the repository root (no git -C)."
case "$mode" in
    --gh-read)   HINT="$HINT Also allowed here: gh pr list/view/diff, gh issue list/view, gh run list/view." ;;
    --picker)    HINT="$HINT Also allowed here: gh pr list --state open --json number,title,headRefName,isDraft --limit 300 (that form only) and ls .ai/work/0-<n>; nothing that switches, creates or commits a branch: the workflow does that (start-item.sh, resume-item.sh)." ;;
    --architect) HINT="$HINT Also allowed here: git add of .ai/decisions/ and .ai/work/ paths, and git commit -m \"<subject>\" --trailer \"Co-Authored-By: <name> <noreply@anthropic.com>\"." ;;
    '') ;;
    # Fails closed on a mode it does not know (FAC-S04): `--k8s` is gone (backlog #0-182), and a frontmatter or a
    # settings twin that still passes it, or a typo, must not leave its agent with no guard of its own.
    *) block "readonly-bash.sh: unknown mode $mode" ;;
esac
input=$(cat)
if [ -n "$only" ] && ! applies_to_agent "$only" "$input"; then exit 0; fi
cmd=$(json_get "$input" '.tool_input.command'); rc=$?
json_checked "$rc" "this agent's Bash guard"
[ -n "$cmd" ] || exit 0

# The architect's commit, in one form only (backlog #0-203): a subject of plain characters and, optionally, the
# attribution trailer every factory commit carries. Matched as a whole command before the metacharacter check below,
# as the trailer's address holds `<` and `>`: inside the double quotes of this exact shape they are text, and nothing
# else of the command can hold one. Fixed: the metacharacter check refused the trailer, so the architect's commits
# were the factory's only ones without it (run 20261102-113931, #0-199; 671a53eb, #0-197). Every other form is
# refused, `-F`/`--file` among them: with a free path the architect could put the content of any file it can read
# into a commit message, which the shipper then pushes.
ARCHITECT_COMMIT='^git commit -m "[A-Za-z0-9 ():#._/,-]+"( --trailer "Co-Authored-By: [A-Za-z0-9.]+( [A-Za-z0-9.]+)* <noreply@anthropic[.]com>")?$'
if [ "$mode" = --architect ]; then
    case "$cmd" in
        'git commit'*)
            [[ $cmd =~ $ARCHITECT_COMMIT ]] && exit 0
            block "the architect commits in one form only: git commit -m \"<subject>\" --trailer \"Co-Authored-By: <name> <noreply@anthropic.com>\" (no -F, --file, -a, --amend, --no-verify or other options; the subject in letters, digits, spaces and ():#._/,-). $HINT" ;;
    esac
fi

# '$(' is the literal text of a substitution, matched, not run (backlog #0-149).
# shellcheck disable=SC2016
case "$cmd" in
    *'|'*|*';'*|*'&'*|*'>'*|*'<'*|*'$('*|*'`'*|*$'\n'*)
        block "one read-only command per call: no pipes, chaining, redirects or substitutions (a | inside quotes counts too). $HINT" ;;
esac

case "$cmd" in
    *--output*|*--ext-diff*|*--textconv*|*--no-index*) block "git option not allowed here (it can write files, run programs or read outside the repository). $HINT" ;;
esac

# A check that fails (exit status not 0) refuses: its empty output does not mean "may run" (backlog #0-130).
why=$(git_grep_unsafe "$cmd") || block "the git grep check failed, so the command is refused${why:+: $why}. $HINT"
[ -z "$why" ] || block "$why. $HINT"

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
# No kubectl (backlog #0-182): the k8s reviewer reads the manifests as files, like every reviewer. Its render could
# read what git does not track (a root or a base above k8s/ printed docker/.env, so did a `!!binary` path and an
# upper-case KUSTOMIZATION.YML), run a program (--helm-command) or fetch (a remote base); CI renders and validates.
if [ "$mode" = --gh-read ]; then
    case "$1 ${2:-} ${3:-}" in
        "gh pr list"|"gh pr view"|"gh pr diff"|"gh issue list"|"gh issue view"|"gh run list"|"gh run view") exit 0 ;;
    esac
fi
# The picker (backlog #0-215): the two reads of its definition beyond git, in the one form each needs. Its branch is
# made and switched to in code (start-item.sh, resume-item.sh), so no git write passes here, `git switch` least of all:
# it reads PR titles an outsider can write, and under the autopilot's allow rules alone `git switch *` let it run
# `git switch -c`, and `git commit *`, `git push -u origin *` came with it.
# `gh pr list` in its one form, matched as a whole command (review round 1 of #0-215): matched by its first words, any
# option passed, `--web` in a form a pattern misses (`-w=true`, `-ws open`) and `-R <host>/<owner>/<repo>`, which sends
# the request, a `--search` text with it, to another host. `ls` of one work directory, never through a symlink: a branch
# that commits one at that path, or at `.ai` or `.ai/work`, would list names outside the repository (review rounds 1, 2).
PICKER_PR_LIST='gh pr list --state open --json number,title,headRefName,isDraft --limit 300'
if [ "$mode" = --picker ]; then
    if [ "$1 ${2:-} ${3:-}" = "gh pr list" ]; then
        [ "$cmd" = "$PICKER_PR_LIST" ] && exit 0
        block "the picker lists pull requests in one form only: $PICKER_PR_LIST. $HINT"
    fi
    if [ "$1" = ls ]; then
        [ "$#" -eq 2 ] && [[ $2 =~ ^\.ai/work/0-[0-9]+$ ]] || block "the picker lists one path only: ls .ai/work/0-<n>. $HINT"
        [ ! -L "$2" ] || block "$2 is a symlink: the picker lists the work directory itself, never what a link points to. $HINT"
        # Every component resolved, not the last one alone (review round 2 of #0-215): a resumed branch's tree, written
        # by an earlier run, could make `.ai` or `.ai/work` a link out of the repository. The path must resolve to
        # itself under the repository root; resolved from the hook's own working directory, a hook started anywhere
        # but the root gets another path and refuses, so nothing rests on where Claude Code starts it.
        rel=$(repo_rel "$2") || block "cannot resolve $2 in this repository. $HINT"
        [ "$rel" = "$2" ] || block "$2 resolves to $rel, not to itself in this repository: the picker lists the work directory only. $HINT"
        exit 0
    fi
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
fi
block "not on this agent's read-only allow-list: $1 ${2:-}. $HINT"
