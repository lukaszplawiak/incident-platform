#!/usr/bin/env bash
# ============================================================
# Tests for scripts/factory-admin/seed-scan.sh (backlog #0-179), on fixture transcripts shaped as Claude Code writes a
# Workflow run's (agent-<id>.jsonl + agent-<id>.meta.json): it reports a relayed message that names the run, a git read
# that reaches the merged change (by content or by names only), a read of the answer key, and nothing for a clean run;
# and it refuses, rather than answering "nothing found", wherever the shape is not the one it knows.
# Run: .github/scripts/test-seed-scan.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
SCAN="$SRC/scripts/factory-admin/seed-scan.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
ok()   { echo "  ok: $1"; }
fail() { echo "::error::$1"; failures=$((failures+1)); }

BASE=$(printf 'b%.0s' $(seq 40)); HOST=aace4118; OTHER=e1d35425
RELAY_HEAD='[Workflow harness — user request] The harness relays, verbatim and indented below, the user request that triggered this workflow run.'
TASK_HEAD='[Workflow harness — computed task] The task text below was computed at runtime by a workflow script.'

# user <text>: a transcript's user line; bash <command> / read <path>: an assistant line with one tool call.
user() { jq -cn --arg c "$1" '{type: "user", message: {role: "user", content: $c}}'; }
bash_call() { jq -cn --arg c "$1" '{type: "assistant", message: {content: [{type: "tool_use", name: "Bash", input: {command: $c}}]}}'; }
read_call() { jq -cn --arg p "$1" '{type: "assistant", message: {content: [{type: "tool_use", name: "Read", input: {file_path: $p}}]}}'; }
# agent <dir> <id> <type> <lines...>
agent() {
    local d=$1 id=$2 type=$3; shift 3
    jq -cn --arg t "$type" '{agentType: $t, isBuiltIn: false}' > "$d/agent-$id.meta.json"
    printf '%s\n' "$@" > "$d/agent-$id.jsonl"
}
review_task() { user "$TASK_HEAD
  Autopilot review. Branch review/0684d0a2. mergeBase: ${1:-$BASE}. round: 1.
  The test gate was not run for this branch. Answer with the verdict JSON only."; }
# run <name> <relay | -> : a run dir with a seeder and two reviewers whose first message relays <relay> (- = none)
run() {
    local d="$WORK/$1" relay=$2 first
    mkdir -p "$d"
    agent "$d" s1 defect-seeder "$(user "$TASK_HEAD
  Pattern P-01. Run id sec-after-p01. The branch review/0684d0a2 is checked out: it holds merged change $HOST
  as its last commit, on the base $BASE. Plant the pattern in one of the change's files: svc/A.java.")"
    if [ "$relay" = - ]; then first=$(review_task); else first=$(user "$RELAY_HEAD
  $relay"); fi
    agent "$d" r1 review-general "$first" "$(review_task)" "$(bash_call "git diff $BASE...HEAD")" \
        "$(bash_call 'git show refs/remotes/origin/main:.ai/rules/review/general.md')"
    agent "$d" r2 review-security "$first" "$(review_task)" "$(bash_call "git diff $BASE...HEAD -- svc")"
    echo "$d"
}
scan() { "$SCAN" "$@"; }
check() {   # check <name> <jq expression> <args...>
    local name=$1 expr=$2 out; shift 2
    out=$(scan "$@")
    if jq -e "$expr" <<<"$out" >/dev/null 2>&1; then ok "$name"; else fail "$name: $out"; fi
}
refused() {   # refused <name> <reason regex> <args...>
    local name=$1 re=$2 out; shift 2
    out=$(scan "$@")
    if jq -e --arg re "$re" '.ok == false and (.reason | test($re))' <<<"$out" >/dev/null; then ok "$name"; else fail "$name: $out"; fi
}

echo "seed-scan.sh: what it reports"
d=$(run clean dalej)
check "a clean run: nothing told, nothing reached" \
    '.ok and .run == {pattern: "P-01", runId: "sec-after-p01", commit: "aace4118", base: "'"$BASE"'"} and (.reviewers | length) == 2
     and .summary == {told: [], originalContent: [], originalNames: [], catalogue: []} and all(.reviewers[]; .relay == "dalej")' \
    "$d" P-01 sec-after-p01
d=$(run told '/seed-bugs P-01 47b95294 sec-after-p01')
check "a relayed command names the run, to every reviewer" \
    '.ok and (.summary.told | sort) == ["review-general", "review-security"]
     and (.reviewers[0].told | index("seed") and index("P-01") and index("sec-after-p01") and index("a pattern id"))' \
    "$d" P-01 sec-after-p01
d=$(run another-pattern 'dalej, teraz P-07')
check "a relayed message with any pattern id" '.ok and (.reviewers[0].told == ["a pattern id"])' "$d" P-01 sec-after-p01
d=$(run none -)
check "a run started from a notification: no relay at all" '.ok and all(.reviewers[]; .relay == null and .told == [])' "$d" P-01 sec-after-p01

d=$(run reach dalej)
printf '%s\n' "$(bash_call "git show $HOST -- svc/A.java")" "$(bash_call 'git diff refs/remotes/origin/main HEAD --stat')" \
    "$(bash_call "git show $BASE")" "$(bash_call "git show $BASE --stat")" "$(bash_call "git diff $BASE^ HEAD")" \
    "$(bash_call 'git log --oneline -5 refs/remotes/origin/main')" "$(bash_call "git diff $OTHER $HOST")" \
    "$(bash_call 'git show HEAD~1')" >> "$d/agent-r1.jsonl"
check "reads that reach the merged change, by content and by names" \
    '.ok and .summary.originalContent == ["review-general"] and .summary.originalNames == ["review-general"]
     and ([.reviewers[0].original[] | .level] == ["content", "names", "content", "names", "content", "names", "content", "content"])' \
    "$d" P-01 sec-after-p01
check "the review's own reads are not hits: base...HEAD, the rule files from main" \
    '.ok and (.reviewers[1].original == []) and ([.reviewers[0].original[].command] | index("git diff '"$BASE"'...HEAD") | not)' \
    "$d" P-01 sec-after-p01

# Review round 1: global options before the subcommand, git grep of a ref, tree and archive reads, other refs; and
# what is not a read of the change: a file named Main, a hex-looking word, a pattern searched for, the rule files.
d=$(run forms dalej)
printf '%s\n' "$(bash_call 'git --no-pager show refs/remotes/origin/main -- svc/A.java')" \
    "$(bash_call 'git -C . log -p refs/remotes/origin/main')" \
    "$(bash_call 'git grep -n -e findAll refs/remotes/origin/main -- svc')" "$(bash_call 'git ls-tree -r refs/remotes/origin/main')" \
    "$(bash_call 'git diff-tree -p FETCH_HEAD')" "$(bash_call 'git show ORIG_HEAD')" "$(bash_call 'git show main@{1}')" \
    "$(bash_call 'git show origin/HEAD:svc/A.java')" "$(bash_call 'git show refs/remotes/origin/main:.claude/hooks/x.sh')" \
    "$(bash_call 'git archive refs/remotes/origin/main svc')" >> "$d/agent-r1.jsonl"
printf '%s\n' "$(bash_call 'git blame Main.java')" "$(bash_call "git log --grep main $BASE..HEAD")" \
    "$(bash_call 'git grep -n -e main -- svc')" "$(bash_call 'git grep -n -e findAll -- defaced')" \
    "$(bash_call 'git show HEAD:svc/main.yml')" \
    "$(bash_call 'git show refs/remotes/origin/main:.ai/rules/review/security.md')" >> "$d/agent-r2.jsonl"
check "the forms round 1 found: every one a hit" \
    '.ok and ([.reviewers[0].original[] | .level] == ["content", "content", "content", "names", "content", "content", "content", "content", "content", "content"])' \
    "$d" P-01 sec-after-p01
check "not reads of the change: Main.java, a hex-looking word, a searched pattern, main.yml, the rule files" \
    '.ok and .reviewers[1].original == []' "$d" P-01 sec-after-p01
# Review round 2: other ways in, and a patch flag that wins over a names flag.
d=$(run forms2 dalej)
printf '%s\n' "$(bash_call 'git show @~1')" "$(bash_call 'git log -p')" "$(bash_call 'git log --oneline -- svc/A.java')" \
    "$(bash_call 'git log --all --oneline -1')" "$(bash_call 'git reflog')" "$(bash_call 'git show-branch')" \
    "$(bash_call 'git log -p --stat HEAD')" "$(bash_call 'git rev-list HEAD')" >> "$d/agent-r1.jsonl"
# Within the branch's own two commits, a walk from HEAD reaches nothing of main's.
printf '%s\n' "$(bash_call 'git log -1 --format=%B HEAD')" "$(bash_call 'git log --format=%B -n 2 HEAD')" \
    "$(bash_call 'git log --max-count=2 --oneline')" >> "$d/agent-r2.jsonl"
check "round 2's forms: @~, a walk from HEAD, --all, reflog, show-branch; -p wins over --stat" \
    '.ok and ([.reviewers[0].original[] | .level] == ["content", "content", "names", "names", "names", "names", "content", "names"])' \
    "$d" P-01 sec-after-p01
check "a walk from HEAD that stops within the branch's two commits is no read" '.ok and .reviewers[1].original == []' \
    "$d" P-01 sec-after-p01
d=$(run forms3 dalej)
printf '%s\n' "$(bash_call 'git log --format=%B -n 3 HEAD')" "$(bash_call 'git log -3 --oneline')" >> "$d/agent-r1.jsonl"
check "three commits from HEAD reach main's" '.ok and ([.reviewers[0].original[] | .level] == ["names", "names"])' \
    "$d" P-01 sec-after-p01

d=$(run key dalej)
printf '%s\n' "$(read_call .ai/audit/benchmark/patterns.md)" "$(bash_call 'git grep -n -e P-01 -- .ai/audit')" >> "$d/agent-r2.jsonl"
check "a read of the answer key" '.ok and .summary.catalogue == ["review-security"] and (.reviewers[1].catalogue | length) == 2' \
    "$d" P-01 sec-after-p01

echo "seed-scan.sh: the prompts it parses are the ones seed-bugs.js writes (review round 1)"
# The scan reads the run from the seeder's prompt and each reviewer's mergeBase: a rewording there would make every
# scan refuse, found only when the owner runs it. These are the words it parses, in the workflow as it is.
WF="$SRC/.claude/workflows/seed-bugs.js"
# The ${...} are the workflow's own template text, matched literally: single quotes on purpose.
# shellcheck disable=SC2016
for words in 'Pattern ${params.pattern}. Run id ${params.runId}.' 'it holds merged change ${params.commit}' \
             'on the base ${seed.base}' 'mergeBase: ${seed.base}.'; do
    if grep -qF -e "$words" "$WF"; then ok "seed-bugs.js still writes: $words"; else fail "seed-bugs.js no longer writes '$words': seed-scan.sh parses it"; fi
done

echo "seed-scan.sh: what it refuses (a shape it does not know is never 'nothing found')"
d=$(run base dalej)
refused "the wrong number of arguments" "usage" "$d" P-01
refused "a pattern id that is not one" "not a pattern id" "$d" PP-1 sec-after-p01
refused "a run id that is not one" "not a run id" "$d" P-01 'sec after'
refused "no such directory" "no such directory" "$WORK/nope" P-01 sec-after-p01
refused "another run's directory" "the run of P-01 sec-after-p01, not of P-02 sec-after-p02" "$d" P-02 sec-after-p02
mkdir -p "$WORK/empty" && cp "$d"/agent-s1.* "$WORK/empty/"
refused "no reviewer" "no review-\\* agent" "$WORK/empty" P-01 sec-after-p01
d=$(run noseeder dalej) && rm "$d"/agent-s1.*
refused "no seeder" "no defect-seeder transcript" "$d" P-01 sec-after-p01
d=$(run nojsonl dalej) && rm "$d/agent-r2.jsonl"
refused "a reviewer without its transcript" "review-security has no transcript" "$d" P-01 sec-after-p01
d=$(run header dalej) && agent "$d" r2 review-security "$(user 'You are a reviewer.')" "$(review_task)"
refused "a first message with neither header" "neither of the Workflow tool's headers" "$d" P-01 sec-after-p01
d=$(run array dalej) && agent "$d" r2 review-security '{"type":"user","message":{"content":[{"type":"text","text":"x"}]}}' "$(review_task)"
refused "a first message that is not text" "does not start with a user message of text" "$d" P-01 sec-after-p01
d=$(run broken dalej) && printf 'not json\n' > "$d/agent-r2.jsonl"
refused "a transcript that does not parse" "does not parse|does not start" "$d" P-01 sec-after-p01
d=$(run nobase dalej) && agent "$d" r2 review-security "$(user "$RELAY_HEAD
  dalej")" "$(user "$TASK_HEAD
  Review this branch.")"
refused "a panel prompt without its mergeBase" "names no mergeBase" "$d" P-01 sec-after-p01
d=$(run otherbase dalej) && agent "$d" r2 review-security "$(user "$RELAY_HEAD
  dalej")" "$(review_task "$(printf 'c%.0s' $(seq 40))")"
refused "a mergeBase that is not the seeder's base" "is not the seeder's base" "$d" P-01 sec-after-p01
d=$(run seederprompt dalej) && agent "$d" s1 defect-seeder "$(user "$TASK_HEAD
  Plant something.")"
refused "a seeder prompt without the pattern and run id" "names no pattern and run id" "$d" P-01 sec-after-p01

if [ "$failures" -gt 0 ]; then echo "$failures seed-scan test(s) failed"; exit 1; fi
echo "All seed-scan tests passed."
