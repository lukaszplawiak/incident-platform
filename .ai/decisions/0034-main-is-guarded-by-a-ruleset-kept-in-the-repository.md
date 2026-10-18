# ADR-0034: `main` is guarded by one ruleset kept in the repository, bypassed at merge only by the owner's admin role; CI compares GitHub with the files (backlog #0-113)

- **Status:** Accepted (2026-10-18)
- **Backlog:** #0-113 (follow-ups #0-153, #0-154)
- **Date:** 2026-10-18
- **Author:** human (owner chose one ruleset, every CI job as a required check, and that only their own account may bypass it, at merge; written up by Claude Code in the owner's session)
- **Reversible:** yes

## Context

The autopilot's merge safety was in GitHub's settings, invisible in any diff. Read on 2026-10-18: one
ruleset, `Protect main`, forbade deleting and force-pushing `main` and nothing else; no branch protection;
no required pull request, check or review; `delete_branch_on_merge` off, `allow_auto_merge` off. The machine
account `lukaszplawiakbot` (Write, a classic token with `repo`) could therefore push to `main` directly:
only the client-side hook `guard-git-push.sh` refused it, and hooks do not bind code an agent runs (#0-116).
Its token has no `workflow` scope, so GitHub refuses a push that changes `.github/workflows/`, and nothing
else: services, `.github/scripts/`, `.ai/rules/`, `CLAUDE.md`.

Three facts shaped the decision:

- **A check in CI only advises whoever can change its code.** A `pull_request` run from a branch of this
  repository runs the PR's own workflow and scripts: the bot cannot change `factory-guards.yml`, but it can
  change `check-factory-guards.sh`, which that workflow runs. Only GitHub's own rules bind: required checks,
  and code owners read from `CODEOWNERS` on the base branch.
- **An author cannot approve their own pull request.** #0-113 as written (required CODEOWNERS review, "no
  bypass for administrators") would make every PR of the owner touching `.github/`, `.claude/`, `CLAUDE.md`
  or `pom.xml`, most of them, unmergeable.
- **The owner's login is also every interactive Claude Code session's.** `gh` on the owner's machine is
  logged in as the owner (admin), so whatever the owner's account may do, a session may do too unless its
  own settings deny it. The autopilot is not affected: it runs with the bot's token in the devcontainer.

## Decision

**One repository ruleset, `main`**, on `refs/heads/main`, written as the file
`.github/repository-settings/rulesets/main.json` (the API's request body, every parameter written out) and
applied by `.github/scripts/repository-settings.rb apply` with the owner's token:

- deletion and force-push forbidden (what `Protect main` held; it is deleted);
- a pull request with one approval, and a code owner's when it touches a code owner's path (`.github/CODEOWNERS`),
  stale approvals dismissed on a push, and the last push approved by someone other than its pusher, so a commit
  the bot adds after the owner's approval needs a new one. **Until phase 3 the owner's approval on every PR:**
  one required approval, and `CODEOWNERS` opens with `* @lukaszplawiak`, so every path is a code owner's. The
  bot's token is in the devcontainer, where code an agent runs is not bound by the session's deny rules
  (#0-116): without the approval a green bot PR touching no owned path (service code, `.ai/context/`) could be
  merged with that token, and without the catch-all the bot's own approval (it has Write) would count on a PR
  it did not open, Renovate's or the owner's (security review of #0-113). So shadow mode holds on GitHub's
  side. Phase 3 removes the catch-all, after #0-153 decides which paths stay owned, and sets the count back to
  0, so `--auto` can merge a PR touching no owned path on green checks;
- every job of `ci.yml` and `Factory guards` as a required check, each pinned to GitHub Actions
  (`integration_id` 15368), so a commit status of the same name set through the statuses API does not satisfy
  it. All eight always report on a pull request: `ci.yml` has no path filter on `on:`, and a job skipped by its
  `if:` (the build on a Markdown-only PR, the smoke test) reports "skipped", which a required check accepts.
  Snyk and OWASP run on `push` only and cannot be required. Not strict (a branch need not be up to date with
  `main`): with one person and Renovate it mostly forces rebases; the push to `main` runs CI again, and a red
  `main` stops the autopilot (`main-guard.yml`);
- **bypass: the repository admin role, in `pull_request` mode.** The owner may merge their own PR without a
  review and, when CI is red or missing for an outside reason (GitHub's API down, the Snyk key, #0-152, a flaky
  test), with a red check; GitHub records the bypass on the PR and in the audit log. Nobody pushes to `main`
  directly, the owner included, and nobody force-pushes or deletes it. The bot (Write) has no bypass.

Around the bypass:

- **Only the owner is an admin.** The bypass names a role, not a login: whoever became an admin would get it.
  `repository.json` lists the admins (`["lukaszplawiak"]`); `check`, with an admin token, fails when GitHub's
  differ, and `plan`/`apply` refuse to run (they never change who is an admin: that is the owner's decision,
  in Settings). A ruleset bypass for a `User` actor would name the login itself; the API documents it, but it
  was not verified on a personal repository, so the role and this check stand.
- **The bypass is the owner's hand, not a session's.** `.claude/settings.json` denies interactive sessions
  `gh pr merge … --admin`, a merge through the API (which bypasses without asking), and every write that
  would remove the ruleset rather than bypass it: `gh api` on the rulesets (REST or GraphQL), a `PATCH` of the
  repository, `gh repo edit`, `gh api` on the collaborators (an admin gets the bypass), and
  `repository-settings.rb plan`/`apply` (`check` stays allowed); and `gh pr review` or the reviews API, since a
  session is the code owner too and could give the approval the ruleset asks of the owner. The owner runs those themselves, the merge as
  `! gh pr merge <n> --merge --admin --match-head-commit <sha>`, with the head commit they read: the bypass
  also skips the review of the last push and Factory guards passes a PR the owner opened, so without the SHA
  a commit the bot pushed onto the owner's branch after their reading (#0-131) would merge unread. A plain
  `gh pr merge` respects the ruleset. Like the hooks, these deny patterns stop a mistake, not a command shaped
  to get past them; the autopilot's settings deny `gh pr merge` and `gh api` altogether.
- **Repository settings** (`repository.json`): `delete_branch_on_merge` on, so a PR merged by `--auto` leaves
  no branch; `allow_auto_merge` stays **off** until phase 3 (`docs/ai-factory.md`), which needs #0-116 and
  #0-153 first.

`repository-settings.rb check` runs last in Lint Workflows on every pull request and push to `main`, and fails
when GitHub's settings and the files differ, in either direction; on a required check that no job of a
workflow running on every pull request reports (a renamed job, a path filter, activity `types:` without a new
push); on one that is the name of a job in two workflows, whatever their trigger (a push or a manual dispatch
on the PR's branch would report a check of that name on the same commit); on one without its
`integration_id`; on a job of such a workflow that no required check names (the other way round); and on a
workflow or job whose token may write checks or statuses (see Consequences). A pull request that does not change `.github/repository-settings/` is checked against
`main`'s files, fetched when the job runs (`--main FETCH_HEAD`; the script compares the merge commit with its
first parent to tell), not its own copy: otherwise applying one PR's
change would turn every other open PR's required Lint Workflows red until each took in `main`, and a re-run
would not help. Changing a setting is a PR that changes its file, then `apply` from that branch just before
its merge: between the two, other open PRs are red (main's files no longer match GitHub), and a re-run once it
is merged makes them green when the change was to a parameter. When it adds or renames a required job, a
re-run keeps the other PR's merge commit, with its old workflows, against main's new ruleset, and stays red:
that PR needs a new push or an update from `main`.

## Alternatives considered

- **#0-113 as written, set in the UI** (required CODEOWNERS review, no bypass for administrators). Rejected:
  the owner's own PRs could never merge, so the rule would be switched off for each one, and the settings
  would stay outside every diff.
- **Two rulesets: checks with no bypass at all, the review with the admin's bypass** (this ADR's first draft).
  Rejected by the owner: with CI red for an outside reason not even an urgent fix could merge, short of editing
  the ruleset. With the bypass on both, two rulesets would add nothing over one.
- **A bypass in `always` or `exempt` mode.** Rejected: `always` lets the owner push to `main` without a PR (no
  review, Factory guards only after the fact), `exempt` leaves no record of the bypass.
- **The whole policy in Factory guards** (no rulesets). Rejected: it is CI, and the bot can change the
  script it runs. It stays as a second line, anchored by the code owner review of `.github/`.
- **Terraform's `github` provider or the safe-settings app.** Rejected for one repository: a state, an app or
  an admin secret in CI, the last a larger risk than what it closes.
- **Branch protection instead of a ruleset.** Rejected: it cannot be read without admin rights, so CI could not
  compare it, and its admin bypass cannot be limited to merging a pull request.

## Consequences

- The bot cannot push to `main`, merge with a red check (AC1) or merge without the owner's approval; a PR
  touching a code owner's path waits for the owner's (AC2). None of it depends on what runs in the autopilot's
  session. Renovate's PRs wait for the owner's approval too, until phase 3: the catch-all code owner, not the
  approval count, is what makes the approval the owner's.
- The owner's PRs that touch a code owner's path, most of them, merge only through the bypass, so only by the
  owner's own command (`! gh pr merge <n> --merge --admin --match-head-commit <sha>`), never by a session.
- Whoever holds the owner's token can merge any PR with a red check. The token never enters the devcontainer
  (the bot's does), and a merge to `main` runs CI again, where a red result stops the autopilot.
- A renamed job, or a new required check, changes `ci.yml` and `main.json` in the same PR; `check` fails on a
  required check no job reports before GitHub would leave every PR waiting for it.
- What `check` cannot see with CI's token: the ruleset's `bypass_actors` (shown to admins only), the
  repository settings and the admins. It names them in its summary; run by the owner it compares all three,
  and `plan`/`apply` refuse to run without an admin token. A bypass actor or an admin added in the UI is seen
  only there.
- GitHub may add a parameter to a rule; `check` then fails until it is written in the file.
- On a push to `main`, too, `check` compares GitHub with the files: a setting changed in the UI, or GitHub's API
  down, turns `main` red, and `main-guard.yml` then stops the autopilot (`autopilot-stop`). That is meant: the
  factory must not run while what guards its merges differs from what was reviewed.
- Pinning a required check to GitHub Actions' `integration_id` stops a commit status set through the API, not a
  check run or status that a workflow's own token reports: GitHub writes those in the Actions app's name, so a run
  on any branch with `checks: write` or `statuses: write` could report a required check without its job (security
  review of #0-113). Two things close it: `check` fails on a workflow or job that gives its token either scope (or
  `write-all`), and the bot's token has no `workflow` scope, so it cannot push a new workflow to any branch. The
  second is a property of the token, not of this repository: phase 3, where one approval stops being required,
  rechecks it (`docs/ai-factory.md`).
- Only the settings that guard `main` are files: the ruleset, `delete_branch_on_merge`, `allow_auto_merge` and the
  admins, read from the rulesets, repository and collaborators endpoints. The rest of README "GitHub and CI"
  (Actions' `sha_pinning_required` and allowed actions, the default token permissions, secret scanning, fork PR
  approval) stays a list checked by hand: each comes from another endpoint, most need an admin token to read, and
  each workflow already declares its own `permissions:`. A setting that decides who can merge belongs in the files.
- Not covered by the ruleset, which guards merges into `main`: a workflow run on another branch. `snyk.yml` and
  `owasp-dependency-check.yml` can be dispatched by hand on any branch, which runs that branch's unreviewed
  build next to their secrets (#0-154).
- The CLAUDE.md rule "delete the branch after every merge" now finds the remote branch deleted by GitHub.
