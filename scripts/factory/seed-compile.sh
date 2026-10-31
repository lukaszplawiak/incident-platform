#!/usr/bin/env bash
# ============================================================
# Compiles the planted module of a /seed-bugs run, after seed-fold.sh and before the panel (backlog #0-181). Prints one
# JSON object:
#   {"ok": true, "compiled": true|false, "modules", "reason"?}   or   {"ok": false, "reason"}
#
#   seed-compile.sh <branch> <change-head> <folded-head>
#
# Nothing compiled a plant before: in docs-after-p17 (2026-10-27) the seeder's `private static` method read the instance
# field `id`, so incident-service did not build. Security and architecture said so, and the panel was measured on a
# defect easier to see than the pattern's, a reviewer free to block for the build and not for the rule. So the modules
# the seeder's edit reaches are compiled with their tests (`test-compile`, `-am` for what they depend on, and `-amd`
# too when the edit reaches shared or service-parent: changing shared changes all 7 services), offline (`-o`: the
# change's own dependencies are already in the local repository, and a plant must not fetch anything), through
# _common.sh's factory_mvn, the test gate's hardened call. The edit is the diff of <change-head> (seed-branch.sh's
# head, the change without the defect) to <folded-head> (seed-fold.sh's head): the same files seed-check.sh confirmed.
#   - compiled: false (ok) when there is nothing to compile: no .java in the edit, or none in a Maven module; or the
#     edit changes a pom.xml (the k8s patterns P-20/P-21 plant there, and an offline build of a POM the seeder made
#     unbuildable on purpose would measure the build, not the panel).
#   - ok: false when the build fails: the compiler's first error ("the plant does not compile" when it is in a file the
#     seeder edited, the seeder's mistake, FAILED and not SKIPPED as #0-170 decided; "outside the seeder's edit" when it
#     is not: a caller the plant broke, or a host that no longer compiles), the environment's (_common.sh's MAVEN_ENV_ERRORS: a dependency not in
#     the local repository offline, no disk), or the first [ERROR] of any other failure. Its log stays in .ai/runs/;
#     on success the log is removed, so nothing of the run waits there for a reviewer.
#   - refused, with nothing built: a tree that is not on <branch> at <folded-head>, a <folded-head> whose parent is not
#     <change-head>'s (not the fold of this change), a tree that is not clean, no edit; and a build that changed
#     HEAD or a tracked file (a reviewer would not read what was compiled).
# Not the full test gate (minutes and Docker per run, and a defect the tests catch would never reach the panel in the
# autopilot), and no retry by the seeder. ADR-0044 decision 9.
# Run by the seed-bugs workflow through factory-ops. Tested by .github/scripts/test-seed-branch.sh.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"

refuse() { jq -n --arg r "$1" '{ok: false, reason: $r}'; exit 0; }
# skip <reason> — nothing to compile: the panel runs
skip() { jq -n --arg r "$1" '{ok: true, compiled: false, modules: "", reason: $r}'; exit 0; }

[ $# -eq 3 ] || refuse "usage: seed-compile.sh <branch> <change-head> <folded-head>"
branch=$1 change=$2 folded=$3
[[ $branch =~ $SEED_BRANCH_RE ]] || refuse "not a seed branch name (review/<8 hex>): ${branch:0:40}"
for c in "$change" "$folded"; do [[ $c =~ ^[0-9a-f]{40}$ ]] || refuse "not a commit: ${c:0:40}"; done

on=$(git rev-parse --abbrev-ref HEAD 2>/dev/null) || refuse "cannot read HEAD"
[ "$on" = "$branch" ] || refuse "the tree is on $on, not on $branch"
[ "$(git rev-parse HEAD)" = "$folded" ] || refuse "HEAD is not the folded commit $folded"
parent=$(git rev-parse --verify --quiet "$change^1") || refuse "$change has no parent"
[ "$(git rev-parse --verify --quiet "$folded^1")" = "$parent" ] || refuse "$folded is not the fold of $change: their parents differ"
clean() { [ -z "$(git status --porcelain --untracked-files=normal -- . ':!.ai/runs')" ]; }
clean || refuse "the working tree is not clean: the fold left the seeder's edit committed and nothing else"

edit=$(git diff --name-only "$change" "$folded") || refuse "cannot read the seeder's edit"
[ -n "$edit" ] || refuse "the fold holds no edit of the seeder's"
if grep -qE '(^|/)pom\.xml$' <<<"$edit"; then skip "the edit changes a POM: not compiled"; fi
java=$(grep -E '\.java$' <<<"$edit" || true)
[ -n "$java" ] || skip "no Java file in the edit: nothing to compile"
mods=$(modules_of "$java" | paste -sd, -)
[ -n "$mods" ] || skip "no Java file of the edit is in a Maven module: nothing to compile"
also=()
needs_all_modules "$java" && also=(-amd)

log="$RUNS_DIR/seed-compile-${branch#review/}.log"
factory_mvn -o test-compile -pl "$mods" -am "${also[@]+"${also[@]}"}" > "$log" 2>&1
rc=$?

if [ "$(git rev-parse HEAD)" != "$folded" ] || ! clean; then
    refuse "the build changed the working tree or HEAD: a reviewer would not read what was compiled"
fi
if [ "$rc" -eq 0 ]; then
    rm -f "$log"
    jq -n --arg m "$mods" '{ok: true, compiled: true, modules: $m}'
    exit 0
fi
# The compiler names a file by its absolute path: the reason names it from the repository's root.
first() { grep -m1 -E "$1" "$log" | sed "s|$FACTORY_ROOT/||g" | cut -c1-300; }
# The plant's error only when its file is one the seeder edited (review round 1): a first error elsewhere is a caller the
# plant broke or a host that no longer compiles on main, and the owner reads the two differently.
if line=$(first '^\[ERROR\] .*\.java:\[[0-9]+,[0-9]+\]') && [ -n "$line" ]; then
    file=$(sed -E 's/^\[ERROR\] (.*\.java):\[[0-9]+,[0-9]+\].*/\1/' <<<"$line")
    if grep -qxF -- "$file" <<<"$java"; then refuse "the plant does not compile (log $log): $line"; fi
    refuse "the build fails outside the seeder's edit, in $file: a caller the plant broke, or the change no longer compiles on main (log $log): $line"
elif line=$(first "$MAVEN_ENV_ERRORS") && [ -n "$line" ]; then
    refuse "the build environment failed (log $log): $line"
fi
line=$(first '^\[ERROR\]')
refuse "the build failed with exit code $rc (log $log): ${line:-no [ERROR] line}"
