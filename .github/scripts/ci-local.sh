#!/usr/bin/env bash
# ============================================================
# Runs CI's Lint Workflows and Factory guards jobs locally, in Docker, on the working tree (backlog #0-166 PR 2).
#
#   .github/scripts/ci-local.sh [--job lint-workflows|factory-guards]... [--step <text>]... [--rebuild]
#
# Until #0-166 these checks were run by hand in a throwaway ubuntu container (428 s on the owner's Mac, measured
# 2026-10-25): everything emulated as x86_64, apt and the tools downloaded on every run. Now:
#   - the steps are the workflows' own: ci-local-steps.rb runs each job's `run:` steps in order, so a step added to
#     CI runs here with nothing to copy, and the linters come from the job's own install step (ci.yml's pins);
#   - the image is ruby:<.ruby-version>-slim-trixie with the packages the steps need and the node binary of
#     node:<.node-version>-trixie-slim (backlog #0-163, ADR-0043: the same Debian, so the same C library), built once
#     per platform and reused (tagged with a hash of its definition, which holds both versions: a change of either
#     rebuilds it, nothing else does);
#   - both jobs run on the host's own architecture, at once; on an arm64 Mac only lint-workflows' pinned linters,
#     x86_64 builds, are emulated (Docker Desktop runs an x86_64 binary in an arm64 container through Rosetta),
#     not the bash and Ruby of every step (which made test-repository-settings.sh take minutes, review round 1).
#     Where an x86_64 binary cannot run in a native container, CI_LOCAL_LINT_PLATFORM=linux/amd64 runs
#     lint-workflows whole as amd64.
# The container gets a copy of the tree and nothing else: a tar of the tracked and untracked files that are not
# ignored, and .git (check-shell.rb lists files with git: a new file counts once `git add -N`), built on the host and
# read from stdin, with no mount (review round 1: a mounted tree lets every step, with network, read the ignored
# docker/.env and application-local.yml). Nothing is written back. Not in a git worktree, whose .git is a file.
# .git goes whole, so the run is refused while it could carry what the tree does not (review round 2): a stash (the
# objects of `git stash -u`/`--all` hold untracked and ignored files, docker/.env among them), and credentials in
# the repository's git config (a remote URL with a user or token, an http extraheader). lint-workflows needs the
# network (its install step downloads the pinned linters); factory-guards runs with none, so its steps send nothing.
#
# Each job prints one line per step with its exit code and time (ci-local-steps.rb); every step runs even after a
# failure; the exit code is 1 when a step failed, 2 on a usage error or when Docker is not reachable. Steps CI runs
# with the event, a secret or a token are skipped and named: lint-workflows' check-pinned-tools.sh and
# `repository-settings.rb check` need gh and a GitHub token, and the image has neither (a gh with `attestation`
# would need GitHub's own apt repository in it), so they run on the host, as CLAUDE.md "Commands" shows.
# Not inside a container (the devcontainer among them, /.dockerenv): its Docker is the host's
# (docker-outside-of-docker), so these containers would run on the host's network, outside init-firewall.sh.
# Tested by .github/scripts/test-ci-local.sh (ci-local-steps.rb, and this script's arguments).
# ============================================================
set -uo pipefail

usage() {
    echo "usage: .github/scripts/ci-local.sh [--job lint-workflows|factory-guards]... [--step <text>]... [--rebuild]" >&2
    exit 2
}

jobs=() steps=() rebuild=0
while [ $# -gt 0 ]; do
    case "$1" in
        --job) [ $# -ge 2 ] || usage
               case "$2" in lint-workflows|factory-guards) jobs+=("$2") ;; *) echo "ci-local: unknown job '$2'" >&2; usage ;; esac
               shift 2 ;;
        --step) [ $# -ge 2 ] && [ -n "$2" ] || usage; steps+=(--step "$2"); shift 2 ;;
        --rebuild) rebuild=1; shift ;;
        -h|--help) usage ;;
        *) echo "ci-local: unknown argument '$1'" >&2; usage ;;
    esac
done
[ "${#jobs[@]}" -gt 0 ] || jobs=(lint-workflows factory-guards)
[ "${CI_LOCAL_DRY_RUN:-}" = 1 ] && { echo "jobs: ${jobs[*]}; steps: ${steps[*]:-all}; rebuild: $rebuild"; exit 0; }

[ -e "${CI_LOCAL_CONTAINER_MARKER:-/.dockerenv}" ] \
    && { echo "ci-local: inside a container (the devcontainer?): its containers would run on the host's network, outside its firewall; run it on the host" >&2; exit 2; }
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "ci-local: not inside the repository" >&2; exit 2; }
cd "$ROOT" || exit 2
[ -d .git ] || { echo "ci-local: .git is not a directory (a git worktree?): run it from the main checkout" >&2; exit 2; }
[ -z "$(git stash list 2>/dev/null)" ] \
    || { echo "ci-local: the stash is not empty: .git goes to the container whole, and a stash's objects can hold untracked and ignored files (docker/.env); pop or drop it first" >&2; exit 2; }
creds=$(git config --local --get-regexp '^(remote\..*\.url|url\..*\.insteadof|http\..*extraheader)$' 2>/dev/null \
    | grep -iE 'extraheader|://[^/[:space:]]*@' | sed -E 's/ .*//')
[ -z "$creds" ] \
    || { echo "ci-local: the repository's git config holds credentials ($(tr '\n' ' ' <<<"$creds")): .git goes to the container whole; move them to a credential helper first" >&2; exit 2; }

# tree_list — what the container gets, NUL-separated: .git, then every tracked or untracked file that is not
# ignored and exists (a tracked file deleted in the working tree is left out, as CI's checkout would not have it).
tree_list() {
    printf '.git\0'
    git ls-files -z -co --exclude-standard | while IFS= read -r -d '' f; do
        [ -e "$f" ] || [ -L "$f" ] && printf '%s\0' "$f"
    done
}
if [ "${CI_LOCAL_LIST_ONLY:-}" = 1 ]; then tree_list | tr '\0' '\n'; exit 0; fi
docker info >/dev/null 2>&1 || { echo "ci-local: Docker is not reachable" >&2; exit 2; }
ruby_version=$(tr -d '[:space:]' < .ruby-version) && [ -n "$ruby_version" ] || { echo "ci-local: cannot read .ruby-version" >&2; exit 2; }
node_version=$(tr -d '[:space:]' < .node-version) && [ -n "$node_version" ] || { echo "ci-local: cannot read .node-version" >&2; exit 2; }

# The image: what the steps need besides Ruby (gnupg for check-pinned-tools' gpg cases, python3 for the hooks'
# fallback cases), and on arm64 the x86_64 C library, without which Rosetta cannot start zizmor's x86_64-gnu build
# ("failed to open elf at /lib64/ld-linux-x86-64.so.2"; shellcheck and actionlint are static). Node is only its
# binary, copied from the official image (Node's built-in modules are inside it; no npm, ADR-0043), on the same
# Debian release as the Ruby image: a node built for another C library would not start. The tag hashes this text,
# which holds both versions, so it is rebuilt only when it changes.
dockerfile="FROM node:${node_version}-trixie-slim AS node
FROM ruby:${ruby_version}-slim-trixie
COPY --from=node /usr/local/bin/node /usr/local/bin/node
RUN set -e; extra=''; if [ \"\$(dpkg --print-architecture)\" = arm64 ]; then dpkg --add-architecture amd64; extra=libc6:amd64; fi; \\
 apt-get update -qq; apt-get install -y -qq --no-install-recommends ca-certificates curl git gnupg jq python3 \$extra; \\
 rm -rf /var/lib/apt/lists/*; git config --system --add safe.directory '*'"
tag_of() {   # tag_of <platform>
    printf '%s\n%s\n' "$dockerfile" "$1" | git hash-object --stdin | cut -c1-12 | sed 's/^/incident-platform-ci-local:/'
}
build() {    # build <platform>
    local tag ctx
    tag=$(tag_of "$1")
    if [ "$rebuild" = 0 ] && docker image inspect "$tag" >/dev/null 2>&1; then echo "$tag"; return 0; fi
    ctx=$(mktemp -d) || return 1
    printf '%s\n' "$dockerfile" | docker build -q --platform "$1" -t "$tag" -f - "$ctx" >/dev/null
    local rc=$?
    rm -rf "$ctx"
    [ $rc -eq 0 ] && echo "$tag"
}

native="linux/$(docker version --format '{{.Server.Arch}}' 2>/dev/null)"
case "$native" in linux/amd64|linux/arm64) ;; *) native=linux/amd64 ;; esac

out_dir=$(mktemp -d) || exit 2
trap 'rm -rf "$out_dir"' EXIT
platform_of() {   # platform_of <job>
    case "$1" in lint-workflows) echo "${CI_LOCAL_LINT_PLATFORM:-$native}" ;; *) echo "$native" ;; esac
}
# The images first, once per platform: two jobs on one platform would otherwise both build it on a cold cache.
for job in "${jobs[@]}"; do
    platform=$(platform_of "$job")
    image=$(build "$platform") || { echo "ci-local: building the $platform image failed" >&2; exit 2; }
    printf -v "image_${job//-/_}" '%s' "$image"
done
# One archive for both jobs; a file that cannot be read fails the run instead of being left out (COPYFILE_DISABLE:
# no macOS ._ files).
tree_list | COPYFILE_DISABLE=1 tar --null -T - -cf "$out_dir/tree.tar" \
    || { echo "ci-local: cannot archive the working tree" >&2; exit 2; }

# The copy of the tree inside the container, from stdin, then the job's steps.
inner='set -euo pipefail
mkdir -p /work && tar --warning=no-unknown-keyword -xf - -C /work && cd /work
exec ruby .github/scripts/ci-local-steps.rb "$@"'
start=$SECONDS
pids=()
for job in "${jobs[@]}"; do
    case "$job" in
        lint-workflows) wf=.github/workflows/ci.yml network=bridge ;;
        factory-guards) wf=.github/workflows/factory-guards.yml network=none ;;
    esac
    platform=$(platform_of "$job")
    image_var="image_${job//-/_}"
    image=${!image_var}
    (
        t=$SECONDS
        # No GH_TOKEN, not even an empty one: zizmor refuses to start with an empty token.
        docker run -i --rm --platform "$platform" --network "$network" "$image" \
            bash -c "$inner" ci-local "$wf" "$job" ${steps[@]+"${steps[@]}"} <"$out_dir/tree.tar"
        rc=$?
        echo "== $job ($platform): exit $rc, $((SECONDS - t)) s"
        exit $rc
    ) >"$out_dir/$job.log" 2>&1 &
    pids+=($!)
done

worst=0
for i in "${!jobs[@]}"; do
    wait "${pids[$i]}"; rc=$?
    echo "### ${jobs[$i]}"
    cat "$out_dir/${jobs[$i]}.log"
    [ $rc -gt $worst ] && worst=$rc
done
echo "ci-local: ${jobs[*]} in $((SECONDS - start)) s, exit $worst"
exit $worst
