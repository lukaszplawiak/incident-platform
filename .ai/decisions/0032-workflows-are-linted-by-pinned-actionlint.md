# ADR-0032: CI lints every workflow, and its calls of local actions, with actionlint and shellcheck, both pinned by version and SHA-256 (backlog #0-146)

- **Status:** Accepted (2026-10-17); amended by ADR-0033 (bumps by Renovate, checked against the publisher, one installer)
  and by #0-149 (the same shellcheck on the `.sh` scripts and the composite actions' steps, every severity)
- **Backlog:** #0-146 (follow-ups #0-147, #0-148, #0-149)
- **Date:** 2026-10-17
- **Author:** human (owner chose option B; written up by Claude Code in the owner's session)
- **Reversible:** yes

## Context

Nothing in CI linted `.github/workflows/*.yml` or `.github/actions/*/action.yml`. GitHub itself rejects only a
malformed file, and only when it runs it: a misspelt `steps.<id>.outputs.<name>` is an empty string, not an
error. Since #0-145 build-and-test passes `restore-maven-caches`' outputs into `save-maven-caches`' inputs, so a typo
there would silently stop the Maven caches from being saved (a miss on every run, Maven Central again, #0-141).
Checked on 2026-10-17: actionlint 1.7.12 reads local actions' metadata and refuses a misspelt input, a missing
required input and a misspelt output in a workflow's call of one; it runs shellcheck on every workflow `run:`
block and flags untrusted input (`github.event.*`) used inline in a script. It does not lint a composite action's
own steps (a shell error put in `restore-maven-caches`' `run:` passed; an `action.yml` given to it by name is read
as a broken workflow), nor any `.sh` file. On this repository, with shellcheck 0.11.0, it reports nothing.

actionlint's documented way to run in CI is `bash <(curl …/main/scripts/download-actionlint.bash)`: a script from
a moving branch, run unchecked, the pattern ADR-0021 (Snyk CLI) and ADR-0027 (kubeconform) removed. The runner
image's shellcheck is 0.9.0 from apt, not pinned.

## Decision

- A job `lint-workflows` ("Lint Workflows") in `ci.yml`, always run (seconds, no JVM, `contents: read`), as
  `validate-k8s-manifests` is.
- actionlint and shellcheck follow ADR-0027: `ACTIONLINT_VERSION`/`ACTIONLINT_SHA256` and
  `SHELLCHECK_VERSION`/`SHELLCHECK_SHA256` in the job's `env`, downloaded into `RUNNER_TEMP`,
  `sha256sum --check --strict` before unpacking, no `sudo`. actionlint gets `-shellcheck=<that binary>`, so the
  runner's shellcheck is never used.
- actionlint's sum is committed when the release's `checksums.txt` and GitHub's digest of the asset agree.
  shellcheck publishes no checksum file: GitHub's digest of the `linux.x86_64.tar.gz` asset is the only source.
  Either way the check catches a swapped file, not a malicious release.
- Findings are `::error` annotations (the format template is in the step's `env`, not in the script: there
  shellcheck would flag its own `$err`, SC2016, and an `-ignore` would silence that rule everywhere). No baseline
  and no ignore list: the repository is clean.
- (Amended by #0-149, 2026-10-18.) `check-shell.rb` runs the same pinned shellcheck on every tracked `.sh` file
  and on every composite action's `bash`/`sh` step (its `${{ }}` expressions turned into underscores, as
  actionlint does, each finding reported at its line in `action.yml`). Every severity counts: the most useful
  check, SC2086 (a variable unquoted), is a note, and a `-S warning` floor would have dropped it from the guards
  under `.claude/hooks/` and `scripts/factory/`. `--norc`: shellcheck looks for a `.shellcheckrc` upwards from
  every script, and that file is on no protected list (actionlint, checked, reads none). The findings, 99 in
  35 files on 2026-10-18 (98 the day before), were resolved: the meant ones are disabled where they are, each with its reason on the line above
  (file-wide only in tests built from literal text). Changed instead of disabled: the test repository's setup in
  `test-hooks.sh` (a failed `cd` there ran its commits in the developer's repository; the one real bug),
  `$NODIFF` as an array, `A && B || C` written `{ A && B; } || C` where either half failing means C, jq's
  `'done'` quoted, empty assignments as `''`.
- Bumped by hand, as for the Snyk CLI; Renovate tracks neither (amended by ADR-0033: Renovate bumps both, and
  CI checks each against its publisher). `ci.yml`'s job `env` is the only copy. CLAUDE.md's local command
  prints those versions from it and runs the actionlint and shellcheck on the developer's PATH: a local result
  matches CI only when those are the same versions.

## Alternatives considered

- **The runner's shellcheck** (actionlint pinned only): a new runner image could change shellcheck's rules and
  turn CI red with no change here, what ADR-0027 removed for kubeconform.
- **`download-actionlint.bash`, or a Marketplace action** (`raven-actions/actionlint`): unpinned or another
  publisher's code in the job; `allowed_actions: all` is an open gap (#0-62).
- **The `rhysd/actionlint` image by digest** (`uses: docker://…`): Renovate could bump the digest, but the image
  brings its own shellcheck, at a version this repository does not choose; ADR-0027 rejected the same for
  kubeconform.
- **zizmor in the same change**: it reports findings today (a `workflow_run` trigger rated high in
  `main-guard.yml`, the new `$/` self-repository syntax that actionlint 1.7.12 predates), so it needs its own
  decisions: backlog #0-147, and #0-148 for `main-guard.yml`.

## Consequences

- A PR whose workflows actionlint refuses (a workflow's own content, or how it calls a local action) is red, with
  one annotation per finding.
- A bump changes version and sum together, in `ci.yml`'s `env`, and the sum comes from the sources above.
  actionlint does not know syntax newer than its release (GitHub's `$/` self-repository references, July 2026,
  postdate 1.7.12): adopting new syntax waits for a release that knows it.
- Knowingly left open: security-focused linting (zizmor, #0-147); `main-guard.yml`'s `workflow_run` trigger
  (#0-148; since closed: main-guard and the preflight count only a push to this repository, BACKLOG-DONE);
  shellcheck of the composite actions' steps and of the `.sh` scripts (#0-149: 98 findings on 2026-10-17, 16
  of them warnings, partly under protected paths; since closed, see Decision).
- (#0-149.) A new script is shellchecked by being a tracked `.sh` file; one without the extension is not seen.
  A finding is fixed, or disabled at its line with the reason above it; a global exclude or a severity floor is
  a change to this decision.
