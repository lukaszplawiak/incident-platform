# ADR-0043: CI pins Node like Ruby: the JavaScript tests run on the version in `.node-version`, set up by one local action, with Node's built-in modules only (backlog #0-163)

- **Status:** Accepted (2026-10-25)
- **Backlog:** #0-163
- **Date:** 2026-10-25
- **Author:** human (owner chose B + R1 of the research, in two PRs, then the image copy and the shared check; written up by Claude Code in the owner's session)
- **Reversible:** yes

## Context

#0-163 tests the autopilot workflow's logic (`.claude/workflows/backlog-autopilot.js`). The workflow is JavaScript
and runs only in Claude Code's workflow runtime, so its tests need a JavaScript engine: Node, the fourth runtime of
CI. No job used Node before, and the local image of `ci-local.sh` (`ruby:<.ruby-version>-slim`) had none.

ADR-0040 pinned Ruby because "the same Ruby version is not the same Ruby" (PR #517, the json gem), and pins Java and
Python through their setup actions. A Node taken from the runner image would be the one runtime left unpinned, and
`ci-local.sh` needs a Node anyway.

## Decision

1. `.node-version` at the repository root names one exact version: **24.22.1** (the 24 line, "Krypton", the active
   LTS on 2026-10-25; 26 is not LTS yet). An exact patch, so CI and the local image cannot drift apart.
2. One local action sets it up, `.github/actions/setup-node`: `actions/setup-node@<SHA> # v7.1.0` with
   `node-version-file: .node-version` only (no `node-version` input) and `package-manager-cache: false`, then prints
   `node --version`. Factory guards uses it (the tests), and lint-workflows: it runs no JavaScript, but
   `test-runtime-setup.sh` names `node` in its cases, and the check (decision 4) reads a script one level down without
   telling a case's text from a command; a download per run, where an exception would be a way past the check.
3. **No npm.** The tests load Node's built-in modules only (such as `node:test` and `node:assert`): no
   `package.json`, no lockfile, nothing installed, so no new dependency surface.
4. `check-ruby-setup.rb` becomes `check-runtime-setup.rb`, with Ruby and Node as rows of one table (FAC-A01: one
   check per control, not a copy): it refuses a step that runs Node (the `node` command, an `.mjs` file, or a
   `.github/scripts`/`.claude/hooks` script that does) before `uses: ./.github/actions/setup-node` in its job, any
   other use of `actions/setup-node`, and a `node-version` input. A `.js` name alone does not count: it is mostly a
   file Node never runs (an action's `main:` in a test fixture was the first false alarm).
5. `.node-version` is on the build-config list of `.ai/rules/protected-paths.md`, beside `.ruby-version`, and the
   autopilot denies writing it (`settings.autopilot.json`, `guard-protected-bash.sh`).
6. Locally, `ci-local.sh` copies the `node` binary from the official image `node:<.node-version>-trixie-slim` into
   its Ruby image, now `ruby:<.ruby-version>-slim-trixie`: the same Debian release on both sides, so the binary finds
   the C library it was built for (checked 2026-10-25: Debian 13.7 in both, `node:test` loads with no network).

## Alternatives considered

- **The runner's own Node (R2 of the research).** No work in CI, but the one unpinned runtime, and `ci-local.sh`
  would still need one: the gap ADR-0040 closed for Ruby.
- **Locally, Node's release tarball through `install-pinned-tool.sh`,** with a SHA-256 per architecture and
  `check-pinned-tools.sh` checking them against the signed `SHASUMS256.txt`. Stronger than an image tag, about
  twice the work; the owner chose the image copy, which trusts the official image as the Ruby base already does.
- **A second check, `check-node-setup.rb`.** A copy of the Ruby check with other constants; two checks of one
  pattern drift apart.
- **No check.** One step runs Node today; review would have to see the next one.

## Consequences

- A Node bump is a pull request: `.node-version` (Renovate's `nodenv` manager, on by default in
  `config:recommended`) and the action's SHA (`helpers:pinGitHubActionDigests`). The local image follows the file.
- "Pinned" is the version, not the binary: `setup-node` downloads Node and checks nothing here, so its integrity
  rests on that action, as Ruby's rests on `setup-ruby` (ADR-0040) and the local one on Docker Hub's official
  `node` image; the same kind of gap as ADR-0033's "Knowingly left open", and #0-135 covers the setup actions.
- A step that runs Node needs `uses: ./.github/actions/setup-node` before it in its job, or lint-workflows fails
  (`check-runtime-setup.rb`, which does not follow a script two levels down or a third-party action).
- The local image's two bases are tags, not digests, as in ADR-0040: the owner's local tool, not a gate.
