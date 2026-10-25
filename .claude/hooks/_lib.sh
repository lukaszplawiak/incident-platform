#!/usr/bin/env bash
# Shared helpers for the PreToolUse hooks in this directory. Sourced, not run.
#
# A hook gets the tool call as JSON on stdin (tool_name, tool_input.command / file_path, cwd). Exit 0 lets
# the call go through the normal permission flow, exit 2 blocks it and shows stderr to the agent.

# json_get <json> <jq path> — prints the value, or nothing when the field is absent (exit 0). Uses jq, falls back
# to python3. Exit 3: neither is installed; exit 4: the parser failed or the input is empty. Callers check with json_checked: before
# backlog #0-162 a parser that failed printed nothing with a status no hook looked at, and the Bash hooks read
# that empty command as "nothing to check" and let the call through.
json_get() {
    local json=$1 path=$2
    # jq reads empty input as no input at all and prints nothing with exit 0, which would read as "no such
    # field"; python3 refuses it. A tool call is never empty, so both refuse it (security review of #0-162).
    [ -n "$json" ] || return 4
    if command -v jq >/dev/null 2>&1; then
        printf '%s' "$json" | jq -r "$path // empty" 2>/dev/null || return 4
    elif command -v python3 >/dev/null 2>&1; then
        printf '%s' "$json" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for key in sys.argv[1].lstrip(".").split("."):
    d = d.get(key) if isinstance(d, dict) else None
print("" if d is None else d)' "$path" 2>/dev/null || return 4
    else
        return 3
    fi
}

# json_checked <json_get's exit status> <what reads the call> — refuses unless json_get read the tool call.
json_checked() {
    case "$1" in
        0) return 0 ;;
        3) block "jq or python3 is required for $2" ;;
        *) block "$2 could not read the tool call (the JSON parser failed, exit $1), so the call is refused" ;;
    esac
}

block() {
    echo "BLOCKED by $(basename "$0"): $*" >&2
    exit 2
}

# applies_to_agent <names> <input> — for a guard's session-level twin, `<hook> --agent <name>[,<name>]...` in the
# settings files (backlog #0-164, ADR-0041): 0 when the call's `agent_type` is one of the names, 1 when it is
# another agent's or the main session's (which has no agent_type). Frontmatter hooks are documented for agents of
# the Agent tool, not for the agents a Workflow spawns; session-level hooks get every subagent's call with its
# agent_type (spikes of 2026-10-23 and 2026-10-25). A list that is not lowercase names separated by single
# commas, and an input that does not parse, refuse: a twin that cannot tell whom it guards fails closed. What it
# cannot see: a Claude Code that stops sending agent_type, which reads as the main session, so the twin passes;
# the frontmatter hook, which needs no agent_type, stays for that.
applies_to_agent() {
    local names=$1 input=$2 agent rc
    case "$names" in
        ''|*[!a-z0-9,-]*|,*|*,|*,,*) block "--agent needs agent names separated by commas, not '$names'" ;;
    esac
    agent=$(json_get "$input" '.agent_type'); rc=$?
    json_checked "$rc" "the session-level guard"
    [ -n "$agent" ] || return 1
    case ",$names," in *",$agent,"*) return 0 ;; esac
    return 1
}

# repo_rel <path> — the path relative to the repository root, or the absolute path when it is outside.
# Symlinks are resolved on both sides first: on macOS /var is /private/var, and a project can sit under a
# symlinked directory; git reports the resolved root, a tool may pass the unresolved path. Comparing them
# as strings would put a file of this repository "outside" it — a guard keyed on the path would then let
# it through (fail open) or refuse a legitimate write. Works for files that do not exist yet: the nearest
# existing directory is resolved, and the rest is normalised lexically (`.` dropped, `..` removes the
# segment before it), as a tool that resolves the path before writing would. Kept as given, the rest made
# `nosuchdir/../../x` look like a path inside the repository (review of the factory hardening, arc-5e1d).
# Returns 1 outside a git repository.
repo_rel() {
    local f=$1 root d rest="" seg
    root=$(git rev-parse --show-toplevel 2>/dev/null) || return 1
    root=$(cd "$root" 2>/dev/null && pwd -P) || return 1
    case "$f" in
        /*) ;;
        *) f="$(pwd -P)/${f#./}" ;;
    esac
    d=$f
    while [ ! -d "$d" ]; do rest="/$(basename "$d")$rest"; d=$(dirname "$d"); done
    d=$(cd "$d" 2>/dev/null && pwd -P) || return 1
    local IFS=/
    for seg in ${rest#/}; do
        case "$seg" in
            ''|.) ;;
            ..) d=$(dirname "$d") ;;
            *) [ "$d" = / ] && d=""; d="$d/$seg" ;;
        esac
    done
    unset IFS
    f=$d
    case "$f" in
        "$root"/*) printf '%s\n' "${f#"$root"/}" ;;
        *) printf '%s\n' "$f" ;;
    esac
}

# require_in_repo <path> [prefix ...] — blocks unless the path is a file of this repository (and, with
# prefixes, under one of them, relative to the root). The one check behind every write guard
# (guard-write-in-repo.sh, write-scope.sh) and of read-scope.sh, so a fix lands in all of them. Refused: any `..`
# (no legitimate write needs one), a final component that is a symlink (the write would follow it out of the
# repository), and a path outside the repository. $SCOPE_VERB names the access in the refusal (default: write).
require_in_repo() {
    local file=$1 rel prefix; shift
    [ -n "$file" ] || block "no file path in the tool call"
    case "/$file/" in */../*) block "path with '..' refused: $file" ;; esac
    [ -L "$file" ] && block "the file is a symlink, a ${SCOPE_VERB:-write} would follow it: $file"
    rel=$(repo_rel "$file") || block "not in a git repository"
    case "$rel" in /*) block "${SCOPE_VERB:-write}s outside the repository are refused: $rel" ;; esac
    [ "$#" -eq 0 ] && return 0
    for prefix in "$@"; do
        case "$rel" in "$prefix"*) return 0 ;; esac
    done
    block "this agent may ${SCOPE_VERB:-write} only under: $* (tried $rel)"
}

# shell_words <command> — splits a command into words the way bash would, one per line, quotes and
# backslashes removed; a word holding an unquoted glob character is prefixed with a tab. Prints
# "REFUSE <reason>" (and returns 1) for what bash would expand into words it does not show: `$` (variables,
# command substitution, $'..' quoting) outside single quotes, an unquoted `{` (brace expansion), an
# unterminated quote, a line continuation (bash drops a backslash-newline, so the words it runs differ);
# and for an unquoted command separator or redirect (`;` `&` `|` `(` `)` `<` `>`, a
# newline), so that what it returns is the words of exactly one command. Bash 3.2 compatible.
shell_words() {
    local s=$1 i=0 n c q="" tok="" have=0 glob=0 nl=$'\n'
    n=${#s}
    while [ "$i" -lt "$n" ]; do
        c=${s:$i:1}
        if [ "$q" = "'" ]; then
            if [ "$c" = "'" ]; then q=""; else tok="$tok$c"; fi
        elif [ "$q" = '"' ]; then
            # '\' is one literal backslash, matched as a character (backlog #0-149).
            # shellcheck disable=SC1003
            case "$c" in
                '"') q="" ;;
                '$'|'`') echo "REFUSE expansion inside double quotes"; return 1 ;;
                '\') i=$((i + 1))
                     [ "${s:$i:1}" = "$nl" ] && { echo "REFUSE a line continuation (backslash-newline)"; return 1; }
                     tok="$tok${s:$i:1}" ;;
                *) tok="$tok$c" ;;
            esac
        else
            # '\' is one literal backslash, matched as a character (backlog #0-149).
            # shellcheck disable=SC1003
            case "$c" in
                "'"|'"') q=$c; have=1 ;;
                '\') i=$((i + 1))
                     [ "${s:$i:1}" = "$nl" ] && { echo "REFUSE a line continuation (backslash-newline)"; return 1; }
                     tok="$tok${s:$i:1}"; have=1 ;;
                ' '|"$(printf '\t')")
                    if [ "$have" = 1 ]; then
                        if [ "$glob" = 1 ]; then printf '\t%s\n' "$tok"; else printf '%s\n' "$tok"; fi
                    fi
                    tok=""; have=0; glob=0 ;;
                '$'|'`') echo "REFUSE expansion (\$ or a backtick)"; return 1 ;;
                '{') echo "REFUSE an unquoted { (brace expansion)"; return 1 ;;
                ';'|'&'|'|'|'('|')'|'<'|'>'|"$nl") echo "REFUSE a command separator or redirect: run it as a command of its own"; return 1 ;;
                '*'|'?'|'[') tok="$tok$c"; glob=1; have=1 ;;
                *) tok="$tok$c"; have=1 ;;
            esac
        fi
        i=$((i + 1))
    done
    [ -z "$q" ] || { echo "REFUSE unterminated quote"; return 1; }
    if [ "$have" = 1 ]; then
        if [ "$glob" = 1 ]; then printf '\t%s\n' "$tok"; else printf '%s\n' "$tok"; fi
    fi
}

# The command checks below (git_grep_unsafe, gh_label_unsafe) answer in three ways: exit 0 and no output — the
# command may run; exit 0 and a reason — refused; any other exit status — the check itself failed, and the caller
# refuses (`why=$(check "$cmd") || block …`).
# Fixed (backlog #0-130): a caller used to look only at the output, and a check run in `$(…)` reports a failure
# only by its exit status: an unset variable under `set -u` aborts it with nothing printed, which read as "may
# run". A failure that does not abort was the same: `grep -q … || return 0` took grep's error (2) for "no match",
# and a `sed` or `tr` that failed left an empty word list that matched nothing. Every such step now returns 2.
# Matching is bash's own (`matches`, `case`), not grep on a here-string: a here-string bash cannot write (bash 3.2
# puts it in a file in $TMPDIR) fails the command with exit 1, which is grep's "no match" again.
# The checks rely on the caller's `set -o pipefail` (both hooks set it) for a pipeline's failure to show.

# matches <text> <ERE> — 0 the text matches, 1 it does not, 2 the expression is invalid. In the C locale: bash returns
# 1 for an error while matching too, and in UTF-8 a byte that is not valid UTF-8 is one (`printf '\xff git grep'`
# did not match `git[[:space:]]`); in C every byte is a character (review of #0-130, sec-9c27). The expressions
# are ASCII, and a non-ASCII letter next to `grep` is then not [[:alnum:]], which only refuses more.
matches() {
    local LC_ALL=C
    [[ $1 =~ $2 ]]
    case $? in 0) return 0 ;; 1) return 1 ;; *) return 2 ;; esac
}

# shell_words_refusal <output of a shell_words that exited non-zero> — prints the reason it refused (the last line,
# after the words it had already printed), or returns 1 when there is none: then it did not refuse, it failed.
shell_words_refusal() {
    local last=${1##*$'\n'}
    case "$last" in "REFUSE "*) printf '%s\n' "${last#REFUSE }" ;; *) return 1 ;; esac
}

# git_grep_unsafe <command> — prints why a `git grep` command is refused, or nothing when it may run.
# `git grep` is the agents' only search (Claude Code has no Grep or Glob tool in this version, and plain
# `grep -r` would read gitignored secrets such as docker/.env). It searches tracked files only, and an
# allow-list of options keeps it that way: no --untracked/--no-exclude-standard (ignored files: the
# secrets), --no-index, -f/--file, -O/--open-files-in-pager (runs a program). An allow-list, not a list of
# refusals: git accepts any unique prefix of a long option (`--untr`), and the first version, which matched
# exact names, let those through (review of the factory hardening, sec-4c1e). The command is split as bash
# splits it (shell_words), so quoting (`'-Osh'`) cannot hide an option, and an unquoted glob before `--` is
# refused: bash would expand it into file names, and a file named `--untracked` would become an option.
git_grep_unsafe() {
    local cmd=$1 plain words w value=0 paths=0 glob first second why what nl=$'\n'
    # Detected on the text without quotes, backslashes and newlines: bash removes them, so `git gr\ep`,
    # `git "grep"` and a line continuation (`git gr\<newline>ep`, review round 2, sec-b7d2) are git grep too.
    plain=$(printf '%s' "$cmd" | tr -d "'\"\\\\\n") || return 2
    # git by its name or a path (`/usr/bin/git`), or a glob that bash may expand into it (`/usr/bin/gi[t]`).
    matches "$plain" '(^|[[:space:];&|(/])git[[:space:]]|[][*?]' || { [ $? -eq 1 ] && return 0; return 2; }
    # The word grep, or a glob made only of grep's letters that bash may expand into it (`gr?p`, `g[r]ep`, `*`) when a
    # file of that name exists (review round 4 of #0-130). A glob with other letters (`src/*.java`) cannot become grep.
    matches "$plain" '(^|[^[:alnum:]_-])grep([^[:alnum:]_-]|$)' || {
        [ $? -eq 1 ] || return 2
        matches "$plain" '(^|[[:space:]])[]grep*?!^[]*[][*?][]grep*?!^[]*([[:space:]]|$)' || { [ $? -eq 1 ] && return 0; return 2; }
    }
    # The words "git" and "grep" appear somewhere. Split as bash would: with no unquoted separator this is one
    # command, and unless its first two words are `git grep` the words were text (a commit message saying
    # "use git grep"), which runs nothing. A real git grep after `&&` or `;` is refused with the separator.
    words=$(shell_words "$cmd") || {
        why=$(shell_words_refusal "$words") || return 2
        # Only a glob let the command this far (no word git in it): say so, not "git grep" (review round 3 of #0-130).
        what="git grep"
        matches "$plain" '(^|[[:space:];&|(/])git[[:space:]]' || { [ $? -eq 1 ] || return 2; what="a command with grep and a glob"; }
        echo "$what: $why"; return 0
    }
    first=$(printf '%s\n' "$words" | sed -n 1p) || return 2
    second=$(printf '%s\n' "$words" | sed -n 2p) || return 2
    case "$first" in
        git) ;;
        # git grep run by another program (`env git grep …`, `sh -c "git grep …"`): its options are not visible here.
        sh|bash|zsh|dash|env|xargs|eval|exec|command|nohup|timeout|nice|sudo|time)
            echo "run git grep as the command itself, not through $first"; return 0 ;;
        *)
            # A word `grep` with git behind something (`/usr/bin/git grep`, `GIT_DIR=x git grep`) or a glob that bash may
            # expand into git (`/usr/bin/gi[t] grep`) is git grep to bash, but not to the checks below, which start at
            # word 1 (review round 2 of #0-130, as gh_label_unsafe does for gh). `--grep=…` is one word, not this.
            case "$nl$words$nl" in
                *"${nl}grep$nl"*)
                    case "$nl$words$nl" in
                        *"${nl}git$nl"*|*"/git$nl"*|*"$nl$(printf '\t')"*)
                            echo "run git grep as the command itself (no path, program, assignment or glob before it)" ;;
                    esac ;;
            esac
            return 0 ;;
    esac
    # A glob where the subcommand goes (`git gr?p`): bash may expand it into grep, which the checks below would not see.
    case "$second" in "$(printf '\t')"*) echo "git: a glob where the subcommand goes (${second#?}): write the subcommand"; return 0 ;; esac
    if [ "$second" != grep ]; then
        # Git options before the subcommand and a glob that may become grep (`git -C . gr?p`): refused like `git -C . grep`.
        case "$second" in
            -*) case "$nl$words$nl" in *"$nl$(printf '\t')"*) echo "git options before a glob that may expand to a subcommand: write the subcommand first"; return 0 ;; esac ;;
        esac
        # A git command with a word `grep` elsewhere (`git -C . grep`, `git -c x=y grep`) is git grep with
        # git options before it: refused. `--grep=…` or a message containing "grep" is one word, not this.
        case "$nl$words$nl" in *"${nl}grep$nl"*) echo "only 'git grep' itself (no git options before it)" ;; esac
        return 0
    fi
    # The words are read one per line below, so a newline inside a quoted word would make one argument two words
    # here: `'x<newline>--'` read as `--`, every option after it as a path (review of #0-130, sec-4e1a). An unquoted
    # newline is already refused (shell_words), so any newline left is inside a word.
    case "$cmd" in *"$nl"*) echo "git grep: a newline inside a quoted word: write the command on one line (one -e per pattern)"; return 0 ;; esac
    words=$(printf '%s\n' "$words" | sed 1,2d) || return 2
    [ -n "$words" ] || return 0
    while IFS= read -r w; do
        glob=0
        case "$w" in "$(printf '\t')"*) glob=1; w=${w#?} ;; esac
        if [ "$paths" = 1 ]; then continue; fi
        if [ "$glob" = 1 ]; then echo "an unquoted glob before -- ($w): quote the pattern, put paths after --"; return 0; fi
        if [ "$value" = 1 ]; then value=0; continue; fi
        case "$w" in
            --) paths=1 ;;
            -e|-A|-B|-C|--max-depth|--max-count|-m) value=1 ;;
            -[ABCm][0-9]*)
                case "${w#-?}" in *[!0-9]*) echo "git grep option not allowed: $w"; return 0 ;; esac ;;
            --context=[0-9]*|--after-context=[0-9]*|--before-context=[0-9]*|--max-depth=[0-9]*|--max-count=[0-9]*)
                case "${w#*=}" in *[!0-9]*) echo "git grep option not allowed: $w"; return 0 ;; esac ;;
            --line-number|--column|--ignore-case|--word-regexp|--files-with-matches|--name-only|--files-without-match|--count|--no-filename|--with-filename|--extended-regexp|--basic-regexp|--fixed-strings|--perl-regexp|--invert-match|--only-matching|--quiet|--heading|--break|--show-function|--function-context|--full-name|--null|--cached|--and|--or|--not|--all-match|-I|--text)
                ;;
            -[niwlLchHEGFPvoqzpW]*)
                case "${w#-}" in *[!niwlLchHEGFPvoqzpW]*) echo "git grep option not allowed: $w"; return 0 ;; esac ;;
            -*) echo "git grep option not on the allow-list: $w (tracked files only; no -O, --no-index, --untracked, -f)"; return 0 ;;
        esac
        :   # a pass ends in status 0, whatever the branch above, so the status below is only the here-string's
    done <<<"$words" || return 2   # a here-string bash could not write
    return 0
}

# gh_label_unsafe <command> — prints why a `gh pr|issue create|new|edit` command is refused, or nothing when it
# may run. The audits count a `human:*` label on a PR as the owner's word (.ai/rules/audit.md), so an autopilot
# session may add or remove only the shipper's own labels (backlog #0-126). An allow-list of label values, not a
# refusal of `human:`: the same reasoning as git_grep_unsafe — gh accepts --label, --label=, -l, -l attached, -l in a
# short-flag cluster and comma lists, and bash joins quoted pieces (`"hu"man:x`). The command is split as bash
# splits it (shell_words), which refuses any expansion: a label or a label flag from a variable cannot be checked.
# `gh issue edit` sets labels on a PR too (a PR is an issue to GitHub) and is checked the same way.
GH_LABELS_ALLOWED='autopilot shadow blocked risk-high'   # the labels .claude/agents/shipper.md sets (test-hooks.sh checks it)
# Removing is narrower: the shipper only ever removes `blocked`. Removing `autopilot` would take a PR out of the audit's
# data (it lists PRs by that label); removing a human:* label would erase the owner's word (review round 2).
GH_LABELS_REMOVABLE='blocked'
gh_label_unsafe() {
    local cmd=$1 plain head words w first second third value="" skip=0 rest why nl=$'\n'
    plain=$(printf '%s' "$cmd" | tr -d "'\"\\\\\n") || return 2
    matches "$plain" '(^|[^[:alnum:]_-])(create|new|edit)([^[:alnum:]_-]|$)' || { [ $? -eq 1 ] && return 0; return 2; }
    matches "$plain" '(^|[^[:alnum:]_-])(pr|issue)([^[:alnum:]_-]|$)' || { [ $? -eq 1 ] && return 0; return 2; }
    # A glob before the `pr`/`issue` word (`g? pr edit`, `/opt/homebrew/bin/g[h] pr edit`) is gh to bash but no word
    # `gh` to the checks below (review round 4).
    head=$(printf '%s' "$plain" | sed -E 's/(^|[[:space:]])(pr|issue)([[:space:]]|$).*//') || return 2
    case "$head" in
        *[\*\?\[]*) echo "a glob before the gh subcommand: run gh by its name"; return 0 ;;
    esac
    matches "$plain" '(^|[^[:alnum:]_-])gh([^[:alnum:]_-]|$)' || { [ $? -eq 1 ] && return 0; return 2; }
    words=$(shell_words "$cmd") || {
        why=$(shell_words_refusal "$words") || return 2
        echo "gh pr create/edit: $why (labels are checked; write the command plainly)"; return 0
    }
    first=$(printf '%s\n' "$words" | sed -n 1p) || return 2
    second=$(printf '%s\n' "$words" | sed -n 2p) || return 2
    third=$(printf '%s\n' "$words" | sed -n 3p) || return 2
    case "$first" in
        gh)
            # Read one word per line below, as in git_grep_unsafe: a newline inside a quoted word (`'a<newline>--title'`)
            # would turn one argument into two words here (review of #0-130, sec-4e1a). A multi-line body goes in a file.
            case "$cmd" in *"$nl"*) echo "gh: a newline inside a quoted word (a multi-line --title or --body): write the body to a file and use --body-file"; return 0 ;; esac
            ;;
        */gh) echo "run gh by its name, not by a path"; return 0 ;;
        # gh run by another program (`sh -c "gh pr edit …"`, `env gh`): the label it sets is not visible here.
        sh|bash|zsh|dash|env|xargs|eval|exec|command|nohup|timeout|nice|sudo|time)
            echo "run gh as the command itself, not through $first"; return 0 ;;
        *)
            # One command (shell_words refused separators). A word `gh` that is not the first one is gh run behind
            # something (`GH_REPO=x gh`); `gh` inside a quoted message is part of one word, not this.
            case "$nl$words$nl" in *"${nl}gh$nl"*|*"/gh$nl"*) echo "run gh as the command itself (no prefix before it)" ;; esac
            return 0 ;;
    esac
    # gh finds the subcommand behind flags (`gh --repo x pr edit`, `gh pr -R x edit`, review round 1): any flag before
    # it is refused, so the subcommand is always words 2-3.
    case "$second" in -*) echo "put the subcommand first (gh pr edit …): no flag before it"; return 0 ;; esac
    case "$second" in pr|issue) case "$third" in -*) echo "put the subcommand first (gh $second edit …): no flag before it"; return 0 ;; esac ;; esac
    # `new` is gh's alias of `create` (gh pr new, gh issue new).
    case "$second $third" in "pr create"|"pr new"|"pr edit"|"issue create"|"issue new"|"issue edit") ;; *) return 0 ;; esac
    label_ok() {   # label_ok <allowed list> <comma-separated values>
        local IFS=, x
        for x in $2; do
            case " $1 " in *" $x "*) ;; *) echo "label '$x' is not one an agent may set or remove here (allowed: $1; human:* labels are the owner's)"; return 1 ;; esac
        done
    }
    words=$(printf '%s\n' "$words" | sed 1,3d) || return 2
    while IFS= read -r w; do
        # An unquoted glob: bash expands it into file names, which an agent can create (`./--remove-label`,
        # `./human:x`), so what runs is not what is read here — refused, as git_grep_unsafe does (review round 3, sec-3a9c).
        case "$w" in "$(printf '\t')"*) echo "an unquoted glob (${w#?}) in gh $second $third: quote it"; return 0 ;; esac
        if [ -n "$value" ]; then label_ok "$value" "$w" || return 0; value=""; continue; fi
        if [ "$skip" = 1 ]; then skip=0; continue; fi
        case "$w" in
            --label|--add-label|-l) value=$GH_LABELS_ALLOWED ;;
            --remove-label) value=$GH_LABELS_REMOVABLE ;;
            --label=*|--add-label=*) label_ok "$GH_LABELS_ALLOWED" "${w#*=}" || return 0 ;;
            --remove-label=*) label_ok "$GH_LABELS_REMOVABLE" "${w#*=}" || return 0 ;;
            # A recover file carries the PR's metadata, labels included, where no word here shows them.
            --recover|--recover=*) echo "gh pr create --recover reads labels from a file: not allowed"; return 0 ;;
            # A flag that takes a value: its value is text (a title may start with a dash), not a flag.
            --title|-t|--body|-b|--body-file|-F|--base|-B|--head|-H|--assignee|-a|--add-assignee|--remove-assignee|--reviewer|-r|--add-reviewer|--remove-reviewer|--milestone|-m|--project|-p|--add-project|--remove-project|--template|-T|--repo|-R)
                skip=1 ;;
            --*) ;;
            *)
                # A short-flag cluster with l (`-dl x`, `-lx`): what follows l is its value, else the next word is.
                if [[ "$w" =~ ^-[A-Za-z]*l(.*)$ ]]; then
                    rest=${BASH_REMATCH[1]}
                    if [ -n "$rest" ]; then label_ok "$GH_LABELS_ALLOWED" "$rest" || return 0; else value=$GH_LABELS_ALLOWED; fi
                fi ;;
        esac
        :   # a pass ends in status 0, whatever the branch above, so the status below is only the here-string's
    done <<<"$words" || return 2   # a here-string bash could not write
    return 0
}

# The base an autopilot run compares against is the commit the preflight recorded in .ai/runs/LOCK
# (`<runId> <sha>`), not a ref: a ref can be moved by the session (`git fetch . HEAD:refs/remotes/origin/main`,
# a local branch named origin/main), a recorded sha cannot. Outside a run: $FACTORY_BASE_REF, origin/main, main.
base_ref() {
    local root lock sha
    root=$(git rev-parse --show-toplevel 2>/dev/null) || root=.
    lock="$root/.ai/runs/LOCK"
    if [ -f "$lock" ]; then
        sha=$(awk '{print $2}' "$lock" 2>/dev/null)
        case "$sha" in [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) echo "$sha"; return ;; esac
    fi
    if [ -n "${FACTORY_BASE_REF:-}" ]; then echo "$FACTORY_BASE_REF"; return; fi
    if git rev-parse --verify --quiet refs/remotes/origin/main >/dev/null 2>&1; then echo refs/remotes/origin/main; else echo refs/heads/main; fi
}

# The factory's own scripts must be exactly what the base branch has: the item branch is untrusted code,
# and a gate that the branch can rewrite is no gate. Prints a reason and returns 1 when they differ.
factory_scripts_intact() {
    local base; base=$(base_ref)
    git rev-parse --verify --quiet "$base" >/dev/null 2>&1 || { echo "base ref $base not found"; return 1; }
    if ! git diff --quiet "$base" -- scripts/factory 2>/dev/null; then echo "scripts/factory differs from $base"; return 1; fi
    if [ -n "$(git ls-files --others --exclude-standard -- scripts/factory 2>/dev/null)" ]; then echo "untracked files in scripts/factory"; return 1; fi
    return 0
}
