# ADR-0033: Every CI tool downloaded from a GitHub release is a pin Renovate bumps and CI checks against its publisher, installed by one script; zizmor audits the workflows (backlog #0-147)

- **Status:** Accepted (2026-10-17); amended by #0-149 (the one installer is enforced: `check-downloads.rb`)
- **Backlog:** #0-147 (follow-ups #0-149, #0-150)
- **Date:** 2026-10-17
- **Author:** human (owner chose the pattern and asked for it in all places; written up by Claude Code in the owner's session)
- **Reversible:** yes
- **Amends:** ADR-0021, ADR-0027, ADR-0032 (how their pins are bumped, checked and installed; what they pin stays)

## Context

ADR-0021 (Snyk CLI), ADR-0027 (kubeconform) and ADR-0032 (actionlint, shellcheck) each pinned a tool by
version and SHA-256 in a job's `env` and checked the download before running it. Three gaps remained:

- **Nothing bumped them.** ADR-0021 recorded that Renovate "could raise the version but not the checksum",
  so all four were bumped by hand and none was tracked; security tools aged silently.
- **The SHA-256's source was checked once, by hand, at the bump**, and the record of it lived only in a commit
  message. Publishers differ (checked 2026-10-17): actionlint and zizmor attach a GitHub artifact attestation
  (Sigstore) to every asset; the Snyk CLI publishes `sha256sums.txt.asc`, clearsigned by its code-signing key
  `467717A30B2B4658415975629691DA64D0025194`; kubeconform a `CHECKSUMS` file; shellcheck nothing.
- **Five copies** of the same `curl`/`sha256sum`/`tar` steps (two identical in `snyk.yml`).

A dry run of Renovate 44.132.2 on 2026-10-17 showed that its `github-release-attachments` datasource, from a
`customManagers` regex, bumps a tag and the asset's SHA-256 together: on a fixture it moved actionlint,
kubeconform, zizmor and the Snyk CLI to their next releases, with SHA-256s equal to GitHub's digests (and, for
Snyk, to `snyk-linux.sha256` on downloads.snyk.io), and `doAutoReplace` rewrote both lines. It looks a release up
by a tag equal to the pinned value, so the pin must be the tag (`v1.7.12`), not the bare version (404). It
computes the new SHA-256 from the file it downloads: trust on first use, as #0-142 found for the Maven wrapper.

## Decision

- **A pin is three lines in a job's `env`**: `# renovate: datasource=github-release-attachments
  depName=<owner/repo> verify=<method>`, `<NAME>_VERSION: <tag>`, `<NAME>_SHA256: <64 lowercase hex>`.
  `renovate.json` has one `customManagers` regex for that form, and a package rule: 7 days after release, one PR
  per tool.
- **`check-pinned-tools.sh` runs in lint-workflows on every pull request and push** and refuses a `*_SHA256` line
  in any other form, and a pin whose two lines name different tools (`FOO_VERSION`, `BAR_SHA256`: the workflow
  would check one SHA-256 and install with another; renovate.json's regex cannot say it, RE2 has no
  backreference). For each pin: the tag is a published, non-prerelease release, and one of its assets has the
  SHA-256 as GitHub's digest; then `verify=`, the strongest the publisher gives: `attestation` (`gh attestation
  verify --repo <owner/repo> --source-ref refs/tags/<tag>`: built from that release's tag), `gpg:<fingerprint>`
  (the release's `sha256sums.txt.asc` validly signed by that primary key, committed as
  `.github/keys/<fingerprint>.asc`, and listing the asset), `checksums:<file>` (the release's file lists the
  asset), or `digest` (nothing more exists). Renovate's PRs are trusted only once it passes, as
  `check-maven-wrapper-sum.sh` does for the wrapper (ADR-0031). A tool that receives a secret is checked again
  where it runs: both `snyk.yml` jobs run it on `snyk.yml` with `--expect 1` (a pin renamed out of the
  `_SHA256` form would otherwise not be seen, and pass) before installing the CLI, so a bump merged with Lint
  Workflows red (`main` has no required checks, #0-113) never gets `SNYK_TOKEN`.
- **`install-pinned-tool.sh` is the only installer**: downloads the release asset from github.com, compares its
  SHA-256 with the pin before anything reads it, installs one executable into `RUNNER_TEMP`, no `sudo`. The Snyk
  CLI now comes from its GitHub release (the same `snyk-linux` downloads.snyk.io serves).
- **Today's pins**: actionlint `attestation`, zizmor `attestation`, Snyk CLI `gpg:4677…5194`, kubeconform
  `checksums:CHECKSUMS`, shellcheck `digest`. Snyk's key was committed after its fingerprint matched in three
  places: docs.snyk.io, the snyk/cli repository and keys.openpgp.org.
- **zizmor** (zizmorcore) audits the workflows and the composite actions in lint-workflows: v1.30.1 (five weeks
  old; 1.31.1 gives the same findings here), default persona, `--offline`, `--format github`, any finding fails.
  Offline, its five audits that need GitHub's API do not run (`impostor-commit`, `ref-confusion`,
  `known-vulnerable-actions`, `stale-action-refs`, `ref-version-mismatch`).
  Suppressions name their reason: `dangerous-triggers` inline on main-guard's `workflow_run` (its job acts only on
  a push to this repository, #0-148), `self-repository` off in `.github/zizmor.yml` until the pinned actionlint
  accepts GitHub's `$/` syntax. main-guard's `permissions:` moved to its job (`{}` at the workflow level).

## Alternatives considered

- **Keep bumping by hand** (ADR-0021/0027/0032 as they were): sound, but nothing told anyone a tool was old, and
  the source check lived in a commit message.
- **mise (`mise.lock`) or aqua (`aqua-checksums.json`)**: lockfiles with per-platform checksums and built-in
  attestation checks, and the same versions locally. A new tool in the chain that must itself be pinned this way,
  usually run through a third-party action (#0-62); mise's own docs say a recorded provenance "is not proof that
  mise verified the bytes"; whether hosted Renovate regenerates `mise.lock` was not checked. For five tools in CI
  the cost exceeds the gain.
- **Images by digest (`uses: docker://…@sha256:`)**: Renovate-native, rejected by ADR-0027/0032 for reasons that
  still hold (actionlint's image brings its own shellcheck; `sha_pinning_required` and `docker://` unchecked).
- **`zizmorcore/zizmor-action` with SARIF upload**: another publisher's code in the job and `security-events:
  write`, rejected for actionlint by ADR-0032 on the same grounds.
- **zizmor online, or its pedantic persona**: online audits found nothing more on 2026-10-17 and would make every
  run depend on GitHub's API; pedantic adds 12 findings, mostly style (concurrency limits, comment format).
- **Snyk's two channels (ADR-0021) automated** instead of GPG: both are hosts; a signature by Snyk's key holds
  whichever host served the files.

## Consequences

- A new tool is a pin in this form with the strongest `verify=` its publisher offers, installed by
  `install-pinned-tool.sh`; a `curl … | tar` of its own, or a `*_SHA256` outside the form, is a violation (the
  checker refuses the latter; amended by #0-149: `check-downloads.rb` refuses the former, curl, wget, a release
  asset's URL or `gh release download` in any workflow's or composite action's `run:` block, unless the line
  ends with `# not-a-download: <why>`, today only the smoke test's health probe. It is a check against the
  honest mistake, not a boundary: another program, a script the step calls or an action that fetches its own
  binary pass it, and stay with the owner's review of `.github/`).
- Renovate opens a PR per tool bump; a red `check-pinned-tools.sh` on it means the bytes are not the publisher's:
  close it, do not copy a sum.
- A new Snyk signing key is a new `.github/keys/<fingerprint>.asc` and a `verify=gpg:` change, after the same
  three-way check. The current key expires on 2027-01-06: a signature by an expired (or revoked) key is refused,
  so the file must be refreshed before then; the checker warns from 30 days ahead. Accepting an expired key's
  signatures instead was rejected: a leaked expired key could sign a new file with an earlier date. The signed
  file must be exactly one clearsigned message, and any bad or unverifiable signature in it refuses it.
  shellcheck stays on GitHub's digest alone until it signs releases.
- A pin's `verify=` is read from the workflow, so a change that weakens it (`gpg:` to `digest`) passes the checker:
  it is a `.github/` diff the owner reviews (CODEOWNERS), and Renovate never rewrites `verify=`. A second list
  of minimum methods in the script was rejected: one more copy to keep equal to the comments.
- `check-pinned-tools.sh` downloads the attested assets (actionlint, zizmor) on every run, and reads GitHub's API:
  a GitHub outage makes lint-workflows red, and both Snyk scans, which run the same check first.
- A red Lint Workflows on `main` (the Snyk key expired, GitHub's API down) makes `main-guard.yml` open the
  `autopilot-stop` issue with no change in the repository: the autopilot stops until it is green again. The key's
  refresh before 2027-01-06 is backlog #0-152.
- Knowingly left open: kubectl (`azure/setup-kubectl`, version only: #0-135), `diff-cover` (#0-134), the NVD key
  inline in `owasp-dependency-check.yml`'s command line (#0-150), an impostor commit behind an action's pinned SHA
  (zizmor's online audit, #0-62). Outside this pattern by design: the Maven distribution (Apache, ADR-0031) and the
  container images, pinned by tag (#0-71).
