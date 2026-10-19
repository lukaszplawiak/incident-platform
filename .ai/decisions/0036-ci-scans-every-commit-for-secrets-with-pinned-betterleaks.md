# ADR-0036: CI scans every commit a pull request or a push to main brings for secrets, with pinned betterleaks and its default rules (backlog #0-109)

- **Status:** Accepted (2026-10-18)
- **Backlog:** #0-109 (follow-up #0-156)
- **Date:** 2026-10-18
- **Author:** human (owner chose betterleaks, its own required job, and the `release` verify method; written up by Claude Code in the owner's session)
- **Reversible:** yes
- **Amends:** ADR-0033 (a fifth `verify=` method, `release`)

## Context

Checked on 2026-10-18 through the API: the repository (public) has GitHub's secret scanning and push protection
on, non-provider patterns off. Push protection blocks a push that carries a known provider's token in its
current format (Slack `xoxb-`, Google `AIza`, GitHub, AWS); the pusher can bypass it with a reason, and nothing in
the repository records the setting (ADR-0034 lists it among those checked by hand). It does not see a generic
secret, and this project's are generic: `jwt.secret` (64 characters or more), `mfa.encryption-key` and
`slack.encryption-key` (32 bytes), `DB_PASSWORD`, `POSTGRES_ADMIN_PASSWORD`, the NVD key (a UUID). They sit in
the gitignored `application-local.yml` and `docker/.env`, the files a person or an agent copies from into a
test, a log excerpt or `.ai/work/<id>/proofs.md`. In the repository only `check-packaged-profiles.sh` (#0-81)
looks for secrets, and only under `src/main/resources`. README said both "secret scanning with push
protection" and "no secret scanning (#0-109)".

#0-109 proposed gitleaks. Its last release is v8.30.1 (2026-03-21); its original author lost control of the
project and the name and released betterleaks (MIT, v1.9.0 on 2026-09-29, v2 in release candidates), a drop-in
successor: the same rule format and flags, better generic-secret detection. Neither release carries a GitHub
build attestation (SLSA provenance): gitleaks has a checksums file only; betterleaks' releases are immutable,
with GitHub's release attestation (`https://in-toto.io/attestation/release/v0.2`) and a Sigstore bundle on
`checksums.txt`. `gh attestation verify` refuses it (404, no provenance); `gh release verify-asset` accepts it,
and refuses gitleaks' (not immutable) and an asset checked against another tag.

The whole history, 1170 commits scanned on 2026-10-18 with the default rules: 139 findings, classified by file,
rule and key name (values redacted): test fixtures, placeholders, documented dev values and the Alertmanager token committed on 2026-07-19 (a dev-only token,
expired, untracked since ff2b4da, a history rewrite judged disproportionate then). Nothing to rotate.

## Decision

- A job `secret-scan` ("Secret Scan") in `ci.yml`, always run, `contents: read`, a required check of the ruleset
  of `main` (`rulesets/main.json`, applied by the owner, ADR-0034).
- It scans commits, not the tree: `secret-scan.sh <betterleaks> <from> <to>` runs `betterleaks git --log-opts
  "-m <from>..<to>"`, the pull request's base and head, or a push's `before` and `after`: what each commit adds.
  A secret added and deleted within one pull request is in no tree but in main's history once merged (merge
  commits keep every commit), and fails it in the commit that added it (a case of `test-secret-scan.sh`). `-m`
  gives a merge commit its diff against each parent: `git log -p` alone shows none, and a secret written while
  resolving a conflict passed (found in the security review; a case now). The same finding can be reported
  once per parent. The commits already on `main` are not scanned again: they were
  reviewed once, above.
- Betterleaks' default rules, and nothing in the repository changes them: the script fails on a
  `.betterleaks.toml`/`.gitleaks.toml` (read in place of the defaults) and on a `.betterleaksignore`/`.gitleaksignore`
  (findings hidden by fingerprint; read from the root even when `--gitleaks-ignore-path` names another
  directory, which its test found) at the root, and unsets the config variables. A finding that is no secret is
  allowed on its line, `gitleaks:allow` (or `betterleaks:allow`) with its reason in the same comment, where the
  reviewer sees both. A line allowed in one commit and deleted in a later one is in no final diff (security
  review), so each commit of the range that adds or removes an allow comment gets a `::warning` naming the
  commit and the file, never the line.
- Fails closed: an all-zero `before` (a new branch or a force-push, which the ruleset of `main` refuses), a range
  end that is not in the clone (`fetch-depth: 0`), a scanner exit other than 0 or its findings code (3).
  Findings are `::error` annotations with the rule, file, line and commit; values are never printed
  (`--redact`, and the annotations are built from the report without them). Validation against live APIs is off
  unless `--validation` is given, which the script never does: it would send what it finds to the provider.
- Pinned per ADR-0033 with a new method: `verify=release` (`gh release verify-asset <tag>`): the asset is in the
  release's attestation, and the release is immutable, so neither its tag nor its assets could have been
  replaced since publication. Weaker than `attestation` (nothing about how or from what it was built), stronger
  than `checksums:` (a file of the same release).

## Alternatives considered

- **GitHub's push protection alone, non-provider patterns on**: still no generic password or key of this
  project's form, bypassable by the pusher, and a setting outside the files; no required check.
- **gitleaks v8.30.1** (as #0-109 said): `checksums:` with the version in the file's name
  (`gitleaks_8.30.1_checksums.txt`), which Renovate does not bump with the tag, so every bump fails until edited
  by hand, or a templated name in the checker; a project its author left.
- **trufflehog**: a cosign signature the checker cannot verify (a new tool to pin), and its strength, checking
  that a secret is live, sends the secret to the provider.
- **Verifying betterleaks' Sigstore bundle with cosign**: proves the release workflow's identity, the strongest
  check, but adds a tool to pin and a method to maintain; `release` covers replacement after publication.
- **The tree at the head, or the pull request's diff**: misses a secret added and then deleted.
- **A step in Lint Workflows** (no ruleset change): the job's name would no longer say what it checks.
- **A config file with an allow list** for fixtures: the pull request it scans could widen it; an allow comment
  sits on the one line it allows.

## Consequences

- In this public repository a branch is public once pushed: both this check and push protection run after the
  push. A finding is a secret to rotate, not to delete; what the check stops is the secret reaching `main`
  unnoticed. Stopping it before the push (a pre-commit hook for developers, the factory's push path for agents)
  is #0-156.
- An allow comment passes whatever its line holds: a reviewer reads each one with its reason, which the
  warnings point to (a real secret allowed is still public). The comment's reason is not enforced by the
  script.
- A test that needs a secret-shaped value builds it at run time, or allows its line (`test-secret-scan.sh`
  does both).
- betterleaks v2 changes the CLI and the config: its first stable release comes as a Renovate major bump, read
  against `docs/v2_migration.md` and `secret-scan.sh`'s flags.
- A push to `main` that brings a secret turns `main` red, which stops the autopilot (main-guard, ADR-0034), as any
  red check does.
