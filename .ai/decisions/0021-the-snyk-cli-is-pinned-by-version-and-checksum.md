# ADR-0021: The Snyk CLI is pinned by version and checksum (backlog #0-61)

- **Status:** Accepted
- **Backlog:** #0-61
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

`snyk.yml` downloads the standalone `snyk-linux` binary at `SNYK_CLI_VERSION` and checks it
against `SNYK_CLI_SHA256` (workflow-level `env`, one place for both jobs) before it runs.
`SNYK_TOKEN` is set only on the scan step; the CLI reads it from the environment, so there is no
`snyk auth` step (it put the token in argv and in `~/.config/configstore/snyk.json`). Not
inferable from the file:

- Why not the alternatives: `npm install -g snyk@<x.y.z>` still resolves the wrapper's unbundled
  `@sentry/node ^7` range at install time and loads it on every `snyk` call; `snyk/actions/setup`
  checks the binary against a `.sha256` fetched from the same server, which catches a broken
  download but not a replaced binary, and would be one more third-party action for #0-62.
- Bump by hand, version and checksum together. The checksum must come from two independent
  channels and be committed only if they are equal:
  `https://downloads.snyk.io/cli/v<version>/snyk-linux.sha256` (same host as the binary, so on its
  own it cannot tell a replaced binary from a real one) and the
  `snyk-linux` line of `wrapper_dist/generated/sha256sums.txt` in `npm pack snyk@<version>` (npm
  registry). Renovate has no manager for this: a custom one could raise the version but not the
  checksum, so each of its PRs would fail the check.
- Installed into `$RUNNER_TEMP/snyk-cli` and added to `$GITHUB_PATH`, without `sudo`.
- A checksum mismatch is the check working. Do not "fix" it by copying the new hash without
  comparing both sources.
