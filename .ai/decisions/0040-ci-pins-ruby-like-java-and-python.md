# ADR-0040: CI pins Ruby like Java and Python: every job that runs a `.rb` check sets up the version in `.ruby-version` (backlog #0-166)

- **Status:** Accepted (2026-10-25); decision 5 amended by #0-166 PR 2 (2026-10-25)
- **Backlog:** #0-166
- **Date:** 2026-10-25
- **Author:** human (owner chose to pin Ruby, R2 of the research, and the 4.0 branch; written up by Claude Code in the owner's session)
- **Reversible:** yes

## Context

Before this change eight checks in `.github/scripts/` were Ruby (`check-shell.rb`, `check-downloads.rb`,
`repository-settings.rb`, `check-maven-caches.rb`, `check-k8s-mail.rb`, `check-k8s-environments.rb`,
`check-db-password-config.rb`, `check-hook-entries.rb`), with `run-blocks.rb`, the library two of them load; this
change adds a ninth, `check-ruby-setup.rb`, the third to load it. They run in five jobs: build-and-test,
validate-k8s-manifests, lint-workflows, postgres-roles (`ci.yml`) and Factory guards. They ran on whatever Ruby
the `ubuntu-latest` image brings, with whatever gems it has, while every other input of CI is pinned: the tools
by tag and SHA-256 (ADR-0033), the actions by SHA (backlog #0-60), Java 21 and Python 3.13 by `setup-java` and
`setup-python`.

That Ruby changed a check's result once. In PR #517 `check-hook-entries.rb` found a repeated JSON key by passing
`JSON.parse` an `object_class`; it passed in an `ubuntu:24.04` container (Ruby 3.2.3, json 2.6.3) and failed on
the runner (Ruby 3.2.3, a newer json gem that no longer calls `[]=`; reproduced on json 2.12.2 and 2.15.2, not on 2.6.3 or 2.9.1; 2.10 and 2.11 were not tried). The
same Ruby version is not the same Ruby. And the runner's Ruby 3.2 reached its end of life upstream on 2026-04-01
(ruby-lang.org's branch list; Ubuntu 24.04 still patches its package).

Backlog #0-166 (local CI checks in Docker) needs the local image to run what CI runs; with an unpinned runner it
could only copy versions by hand.

## Decision

1. `.ruby-version` at the repository root names one exact version: **4.0.9** (the 4.0 branch, in normal
   maintenance; an exact patch, not `4.0`, so CI and a local `ruby:<version>-slim` image cannot drift apart).
2. One local action sets that Ruby up, `.github/actions/setup-ruby`: `ruby/setup-ruby@<SHA> # v<tag>` (no input:
   it reads `.ruby-version`; no Bundler, the checks use default gems only: `json`, `psych`/`yaml`, `open3`), then
   prints `RUBY_DESCRIPTION` and the json and psych versions, so a log says what ran. Every job that runs Ruby uses
   it right after checkout: build-and-test, validate-k8s-manifests, lint-workflows, postgres-roles, Factory guards.
3. `check-ruby-setup.rb` (lint-workflows, with its cases in `test-ruby-setup.sh`) holds that: it refuses a step that
   runs Ruby (the `ruby` command, a `.rb` file, or a `.github/scripts`/`.claude/hooks` script that does) before
   `uses: ./.github/actions/setup-ruby` in its job, any other use of `ruby/setup-ruby`, and a `ruby-version` input.
   The list of jobs is the workflows themselves, not a list kept beside them (`FAC-A01`).
4. `.ruby-version` is on the build-config list of `.ai/rules/protected-paths.md`, beside `mvnw` and `.mvn/`: it
   decides what every `.rb` check runs on, so a PR that changes it needs the owner (`check-factory-guards.sh` rule
   6), and the autopilot denies writing it (`settings.autopilot.json`, `guard-protected-bash.sh`).
5. One Ruby, everywhere: the checks run on the Ruby of `.ruby-version`, in CI and locally. Amended by #0-166 PR 2
   (the owner's decision, 2026-10-25): until then they also promised to stay portable to Ruby 2.6, the macOS system
   Ruby, which nothing tested once CI pinned 4.0.9. Locally they run through `.github/scripts/ci-local.sh` (Docker,
   the image `FROM ruby:<.ruby-version>-slim`); run on the host, they need that Ruby (rbenv, asdf or chruby read
   `.ruby-version`), which applies to the owner's own `repository-settings.rb plan`/`apply` too.

## Alternatives considered

- **Keep the runner's Ruby, print its versions, copy them into the local image (R1 of the research).** Cheapest,
  but the runner's gems change without a pull request of ours, which is exactly how PR #517 broke.
- **Run the jobs in our own container image (`jobs.<id>.container`).** The same image locally and in CI pins
  everything at once, but needs an image built, published (GHCR, `packages: write`), signed and kept; a project of
  its own, not this item.
- **`act` to run the workflows locally.** Its runner images are close to GitHub's, not equal, and emulate x86 on
  the owner's Mac: neither faithful nor fast.
- **Ruby 3.4 (also in normal maintenance).** Chosen first, then the owner chose 4.0, the current branch, for the
  longer support ahead. Every check and its tests pass on 4.0.9 with deprecation warnings on, amd64 and arm64
  (2026-10-25).

## Consequences

- A Ruby bump is a pull request: `.ruby-version` (Renovate's `ruby-version` manager, on by default in
  `config:recommended`) and the action's SHA (`helpers:pinGitHubActionDigests`). Read the Ruby release notes for
  the default gems the checks use; CI's run of every check is the test.
- A job that adds a Ruby step needs `uses: ./.github/actions/setup-ruby` before it, or lint-workflows fails
  (`check-ruby-setup.rb`; it does not follow a script two levels down, a Makefile, or a third-party action that runs
  Ruby itself).
- One more third-party action in the supply chain, pinned by SHA and audited by zizmor like the others. "Pinned"
  is the version, not the binary: `setup-ruby` downloads a prebuilt Ruby from `ruby/ruby-builder` and checks no
  SHA-256 or attestation (v1.335.0's `downloadAndExtract`: download, then `tar -xz`), so its integrity rests on that
  action's maintainers instead of GitHub's runner image, as Java's and Python's rest on `setup-java` and
  `setup-python`. It is the same kind of gap as kubectl's in ADR-0033's "Knowingly left open" (`azure/setup-kubectl`
  verifies nothing); backlog #0-135 covers the setup actions too.
- In Factory guards the action runs before the required "Changes that need the owner" step, which stays last: an
  action earlier in a job could change a later step's `PATH` or environment, but so could the pull request's own
  scripts, which that job runs before it anyway (its copy of `check-factory-guards.sh` among them), so moving the step
  first would add no protection, and would skip every test of the job whenever it fails, hiding their results
  exactly when the owner decides whether to approve. What binds such a change is the owner's code owner review of
  `.github/` (review rounds 1 and 2; the owner chose to keep the order).
- A developer whose rbenv, asdf or chruby reads `.ruby-version` gets 4.0.9 in the repository, or an error if it is
  not installed (review round 2); the macOS system Ruby is no longer a supported way to run the checks.
- The local image of `.github/scripts/ci-local.sh` (#0-166 PR 2) starts `FROM ruby:<.ruby-version>-slim`, for both
  architectures, so a local run uses the same Ruby, json and psych as CI (it prints them, as the action does in CI).
  Its base is the image's tag, not a digest, and its apt packages are not pinned: it is the owner's local tool, not a
  gate, the version is what makes it CI's Ruby, and it holds no secret (the tree reaches it as a tar of the files
  git does not ignore, never as a mount: review round 1 of PR 2). Pinning the digest, with Renovate bumping it,
  is the step up if it ever runs anything that decides (owner, 2026-10-25).
