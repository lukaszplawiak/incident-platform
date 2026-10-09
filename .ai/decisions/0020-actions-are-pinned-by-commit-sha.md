# ADR-0020: Actions are pinned by commit SHA (backlog #0-60)

- **Status:** Accepted
- **Backlog:** #0-60
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

Every `uses:` names a full 40-character commit SHA with its tag in a comment
(`actions/checkout@<sha> # v4.4.0`). A tag is a movable ref (the `tj-actions/changed-files`
compromise, CVE-2025-30066, repointed every tag); a SHA is not. Not inferable from the files:

- The repository setting `sha_pinning_required` (Settings → Actions → General) makes a workflow
  with a tag-pinned `uses:` fail to start. It is on since 2026-09-29, turned on after the #0-60 PR
  (#437) merged and its run on `main` was green, not with it: it could be enabled only once `main` had
  no tag-pinned `uses:` left. Nothing in a diff shows its state: read it with
  `gh api repos/{owner}/{repo}/actions/permissions` (README "Infrastructure Hardening" records it) instead of
  assuming it. If it is ever off, only review catches a tag.
- Resolve a SHA with `git ls-remote --tags https://github.com/<owner>/<repo>`: for an annotated tag
  take the dereferenced `refs/tags/vX^{}` line (the commit), never the tag object's SHA. The comment
  names the most specific tag on that commit.
- Renovate's `helpers:pinGitHubActionDigests` preset updates the SHA and the comment together; the
  `github-actions` package rule only groups those updates.
- Every `actions/checkout` sets `persist-credentials: false`: no job pushes, and otherwise
  `GITHUB_TOKEN` stays in `.git/config` for every later step to read. A future job that must push
  should pass its token to that one step instead of re-enabling persistence.
- Without persisted credentials, any later `git fetch` in a job is anonymous. That works only
  because the repository is public: `dorny/paths-filter` on a `push` event may fetch history
  itself (on a `pull_request` it lists files through the API). If the repository ever becomes
  private, that step needs its own credentials.
