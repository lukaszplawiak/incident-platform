# ADR-0035: A workflow's secrets live in an environment only `main` may use, never in the repository; CI checks every secret a job reads (backlog #0-154, #0-150)

- **Status:** Accepted (2026-10-18)
- **Backlog:** #0-154, #0-150 (follow-up #0-155)
- **Date:** 2026-10-18
- **Author:** human (owner chose the environment kept as a file with its check, and #0-150 in the same change; written up by Claude Code in the owner's session)
- **Reversible:** yes
- **Amends:** ADR-0034 (the files and the check of `.github/repository-settings/` also hold the environments and which secrets exist)

## Context

`SNYK_TOKEN` and `NVD_API_KEY` were repository secrets, which every run of every workflow can read. `snyk.yml` and
`owasp-dependency-check.yml` had `workflow_dispatch` and nothing tying their jobs to `main`. Anyone with Write, the
autopilot's machine account included (its classic `repo` token may dispatch), could run either on a branch of their
own, and that run executes the branch's code in the job that gets the secret:

- in `snyk.yml`, `check-pinned-tools.sh` and `install-pinned-tool.sh` (the pin check and the installer of #0-147),
  the local action `restore-maven-caches` and the Maven build all come from the branch, so the binary that receives
  `SNYK_TOKEN` is whatever the branch installs as `snyk`;
- in the OWASP scan the branch's `mvnw`, POM and plugin get the key, on the Maven command line at that (#0-150).

The machine account's missing `workflow` scope stops it changing `.github/workflows/`, not `.github/scripts/`,
`.github/actions/` or `pom.xml`; the ruleset of ADR-0034 guards merges into `main`, not runs on other branches.

## Decision

- **An environment, `scans`**, whose deployment branch policy allows the branch `main` only, which admins may not
  bypass (`can_admins_bypass: false`), with no reviewers and no wait timer. It holds `SNYK_TOKEN` and `NVD_API_KEY`;
  the repository holds no secret at all. GitHub matches the policy against the run's ref, so a run on another
  branch is refused before any step. An old copy of a workflow without `environment:`, on a branch cut from
  `main`'s history, finds no repository secret to read.
- **Both Snyk jobs and the OWASP scan job** run in it (`environment: {name: scans, deployment: false}`: they are not
  deployments, so its history stays empty), and every job of both workflows has `if: github.ref ==
  'refs/heads/main'`, so a dispatch elsewhere shows as skipped. The `if:` is the readable half; the environment is
  the binding one, since a branch may carry an older file.
- **The environment is a file**, `.github/repository-settings/environments/scans.json`, with `repository.json`'s
  `repository_secrets: []`, kept and checked like the ruleset (ADR-0034): `check` fails when GitHub's environment,
  its branch policies or its protection rules differ, and, with the owner's token, when the environment's secrets or
  the repository's are not the names the files give; `apply` creates and updates environments and their branch
  policies, never deletes one (its secrets would go with it) and never writes a secret (the owner sets the values in
  Settings). Before asking GitHub anything, `check` refuses a job that reads `secrets.<NAME>` (GITHUB_TOKEN aside)
  outside an environment whose file names it, a workflow-level `env:` with a secret, `secrets: inherit`, and the
  secrets context read whole or by index (`toJSON(secrets)`, `secrets[...]`); and an environment file that names
  secrets but allows a branch other than `main`, or an admin's bypass. zizmor (lint-workflows, ADR-0033) flags
  `secrets: inherit` and the whole-context reads too; this check is kept for what zizmor cannot know, whether the
  environment's file names the secret and allows `main` alone, and neither copy should be dropped for the other.
- **#0-150**: the NVD key is the step's `NVD_API_KEY` environment variable, which the plugin reads itself
  (`nvdApiKeyEnvironmentVariable` in the root POM); `nvdApiKey` and the `nvd.api.key` property are gone. The
  plugin's own documentation (12.2.2) calls this the recommended form in CI and warns that `nvdApiKey` can show the
  key in Maven's debug log (GHSA-qqhq-8r2c-c3f5). Locally: `NVD_API_KEY=<key> ./mvnw dependency-check:aggregate`.

## Alternatives considered

- **Only `if: github.ref == 'refs/heads/main'`.** Rejected as the control: a dispatch reads the workflow from the
  branch it runs on, and the machine account can cut a branch from a `main` commit older than the `if:` and add a
  commit that changes only `.github/scripts/`, which its token may push. Kept as the second line.
- **Removing `workflow_dispatch`.** Rejected: the same old copies keep it, and the owner loses manual runs.
- **The environment set in the UI only.** Rejected: the setting that decides who gets a secret would be outside
  every diff again, which ADR-0034 ended for the ruleset.
- **Required reviewers on the environment.** Rejected: every weekly scheduled run would wait for the owner.

## Consequences

- A run of either scan on a branch other than `main` gets no secret and runs no step (AC1 of #0-154).
- A new workflow, or a job, that reads a secret fails Lint Workflows until it runs in an environment whose file
  names that secret, and that environment's policy is reviewed with it.
- A job that calls a reusable workflow cannot set `environment:`, so passing it a named secret always fails the
  check: the secret must be read by a job of the called workflow, inside the environment, not handed down.
- CI's token sees neither the environment's secrets nor the repository's; `check` names them as not compared, and
  run by the owner it compares both. A secret added in the UI at the repository level is seen only there.
- The owner moves the values: the secrets are created in the environment and deleted from the repository by hand,
  after `apply` creates the environment; until then `check` lists what is left to do.
- Not covered: what a job on `main` itself runs. Its code went through the ruleset of ADR-0034.
