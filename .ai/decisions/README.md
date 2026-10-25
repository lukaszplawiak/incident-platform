# Architecture Decision Records

Decisions an agent must respect unless a new ADR changes them. Read only the ADRs whose area your
task touches; the area column is there so that nobody has to read all of them.

- New decision: copy `_template.md` to `NNNN-short-slug.md` (next free number), status `Proposed`.
  The human owner sets it to `Accepted`. In the autopilot, the `architect` agent writes `Proposed` ADRs
  for reversible decisions on its own; an irreversible one stops the item (`.ai/rules/ready.md`).
- Changing a decision: a new ADR whose status says `Supersedes ADR-NNNN`; the old one gets
  `Superseded by ADR-MMMM`. ADRs are never deleted.
- ADR-0001..0022 were migrated verbatim from `.ai/context/project.md` on 2026-10-05, ADR-0023..0025 on
  2026-10-09 (written there by the owner in the meantime); they keep their original prose rather than the
  template sections.

| ADR | Decision | Area | Backlog |
|---|---|---|---|
| [0001](0001-escalation-is-an-attribute-not-a-lifecycle-state.md) | Escalation is an attribute, not a lifecycle state | incident FSM, escalation | — |
| [0002](0002-rate-limiting-is-redis-backed.md) | Rate limiting is Redis-backed (decision reversed, backlog #67) | rate limiting, Redis, ingestion | #67 |
| [0003](0003-service-to-service-auth-per-tenant-service-tokens.md) | Service-to-service auth: per-tenant service tokens (backlog #0-11) | service tokens, s2s HTTP, tenant | #0-11 |
| [0004](0004-alert-sources-authenticate-with-integration-api-keys-platfor.md) | Alert sources authenticate with Integration API keys; platform alerts go out of band (backlog #0-16) | API keys, introspection, Alertmanager, reserved tenants | #0-16 |
| [0005](0005-tenant-provisioning.md) | Tenant provisioning (#0-80) | tenants, platform operator | #0-80 |
| [0006](0006-platform-api-step-up-and-limit.md) | Platform API step-up and limit (#0-83) | platform API, MFA, rate limit | #0-83 |
| [0007](0007-admin-mfa-reset.md) | Admin MFA reset (#0-88) | MFA, admin reset | #0-88 |
| [0008](0008-mfa-recovery-of-a-customer-tenant-s-only-admin.md) | MFA recovery of a customer tenant's only admin (#0-90) | MFA recovery, operator | #0-90 |
| [0009](0009-tenant-suspension.md) | Tenant suspension (#0-82, done; offboarding is #0-101) | tenant status, suspension, filters, pausing background work | #0-82, #0-101, #0-102 |
| [0010](0010-bulk-updates-flush-before-they-clear.md) | Bulk UPDATEs flush before they clear | JPA, @Modifying, outbox | #0-83 |
| [0011](0011-operator-tenant-bootstrap.md) | Operator tenant bootstrap | operator tenant, bootstrap, scheduler | #0-80, #0-49 |
| [0012](0012-api-keys-in-auth-service-deny-by-default.md) | API keys in auth-service: deny by default (#0-89) | API keys, scopes, auth-service security | #0-89 |
| [0013](0013-tenant-id-and-kafka-tenant.md) | Tenant id and Kafka tenant | tenant id, Kafka headers, dead-letter | #0-91, #0-92, #0-96 |
| [0014](0014-audit-outbox.md) | Audit outbox | audit, outbox, Kafka | #0-84, #0-4, #0-93, #0-103 |
| [0015](0015-auth-email-outbox-intent-to-send.md) | Auth email outbox = intent to send | auth email, outbox, retries | #0-52, #0-83, #0-88 |
| [0016](0016-escalation-notifications-go-to-the-escalation-target.md) | Escalation notifications go to the escalation target (backlog #0-1) | notifications, escalation, on-call, Slack | #0-1 |
| [0017](0017-notification-idempotency-is-keyed-on-tenant-and-escalation-l.md) | Notification idempotency is keyed on tenant and escalation level | notifications, idempotency | — |
| [0018](0018-coverage-is-enforced-twice-per-module-and-on-a-pr-s-changed.md) | Coverage is enforced twice: per module, and on a PR's changed lines (backlog #0-57) | tests, coverage, CI | #0-57 |
| [0019](0019-every-workflow-declares-its-token-scope.md) | Every workflow declares its token scope (backlog #0-59) | GitHub Actions, permissions | #0-59 |
| [0020](0020-actions-are-pinned-by-commit-sha.md) | Actions are pinned by commit SHA (backlog #0-60) | GitHub Actions, pinning | #0-60 |
| [0021](0021-the-snyk-cli-is-pinned-by-version-and-checksum.md) | The Snyk CLI is pinned by version and checksum (backlog #0-61) | Snyk, CI supply chain | #0-61 |
| [0022](0022-the-services-database-role-is-not-a-superuser.md) | The services' database role is not a superuser (backlog #0-78) | Postgres roles, migrations | #0-78 |
| [0023](0023-logs-are-one-json-object-per-line-in-ecs.md) | Logs are one JSON object per line, in ECS (backlog #0-94, step 1) | logging, ECS, `shared`, startup guard | #0-94 |
| [0024](0024-logs-are-collected-by-alloy-into-loki-in-docker-compose-only.md) | Logs are collected by Alloy into Loki, in docker-compose only (backlog #0-94, step 2) | log collection, Loki, Alloy, Grafana, docker-compose | #0-94, #0-72 |
| [0025](0025-a-resilience4j-annotation-works-only-on-a-call-from-another.md) | A Resilience4j annotation works only on a call from another bean (backlog #0-21, #0-103) | Resilience4j, Spring proxies, retry, Slack | #0-21, #0-103 |
| [0026](0026-mailpit-runs-in-the-dev-overlay-only.md) | Mailpit runs in the Kubernetes dev overlay only; staging and prod have no mail relay (backlog #0-42) | k8s overlays, mail, Mailpit, CI checks | #0-42 |
| [0027](0027-k8s-validation-inputs-are-pinned.md) | The k8s job's validation inputs are pinned: kubeconform by checksum, its schemas by commit, one Kubernetes version (backlog #0-76) | kubeconform, kubectl, Kubernetes version, CI supply chain | #0-76, #0-134, #0-135 |
| [0028](0028-the-hpas-own-the-services-replica-counts.md) | The HPAs own the services' replica counts; overlays size an environment through `minReplicas`/`maxReplicas`, prod's floor is 2 (backlog #0-137) | k8s overlays, HPA, replicas, availability, CI checks | #0-137, #0-79, #0-138, #0-139 |
| [0029](0029-budgets-and-node-spread-protect-the-floors.md) | A PodDisruptionBudget of one pod (`maxUnavailable: 1`, `AlwaysAllow`) in base and a spread over nodes (hostname, `ScheduleAnyway`) protect every service's replica floor (backlog #0-138) | k8s base, PodDisruptionBudget, topology spread, availability, CI checks | #0-138, #0-137, #0-140 |
| [0030](0030-ci-packages-the-jar-build-and-test-tested.md) | Maven builds the services once; the Dockerfiles only package the jar (layered, JRE only), in CI the one build-and-test tested, built into images once by the smoke test; Maven cache restored by prefix, saved on `main` (backlog #0-141) | Dockerfiles, CI, layered jar, Maven cache, Maven Central | #0-141, #0-100, #0-142, #0-143, #0-71 |
| [0031](0031-the-maven-distribution-is-pinned-by-checksum.md) | The Maven distribution `./mvnw` runs is pinned by SHA-256 (checked in CI against Apache's SHA-512) and cached in CI by its exact key, apart from the Maven repository (backlog #0-142) | Maven wrapper, CI supply chain, Maven cache, Renovate, module wrappers removed, cache actions | #0-142, #0-141, #0-145 |
| [0032](0032-workflows-are-linted-by-pinned-actionlint.md) | CI lints every workflow, and its calls of local actions, with actionlint and shellcheck, both pinned by version and SHA-256 (backlog #0-146) | actionlint, shellcheck, workflow lint, CI supply chain | #0-146, #0-147, #0-148, #0-149 |
| [0033](0033-ci-tools-are-pins-renovate-bumps-and-ci-verifies.md) | Every CI tool downloaded from a GitHub release is a pin Renovate bumps and CI checks against its publisher (attestation, signature, checksums file or digest), installed by one script; zizmor audits the workflows (backlog #0-147) | CI supply chain, Renovate, Snyk CLI, kubeconform, actionlint, shellcheck, zizmor, workflow security | #0-147, #0-150, #0-152, #0-134, #0-135 |
| [0034](0034-main-is-guarded-by-a-ruleset-kept-in-the-repository.md) | `main` is guarded by one ruleset kept in the repository, bypassed at merge only by the owner's admin role (never by a Claude Code session); CI compares GitHub with the files (backlog #0-113) | GitHub rulesets, required checks, CODEOWNERS, repository settings, admins, AI factory | #0-113, #0-116, #0-153, #0-154 |
| [0035](0035-workflow-secrets-live-in-an-environment-only-main-may-use.md) | A workflow's secrets live in an environment only `main` may use, never in the repository; CI checks every secret a job reads; the NVD key is read from an environment variable (backlog #0-154, #0-150) | GitHub environments, secrets, workflow_dispatch, Snyk, OWASP, repository settings | #0-154, #0-150, #0-155 |
| [0036](0036-ci-scans-every-commit-for-secrets-with-pinned-betterleaks.md) | CI scans every commit a pull request or a push to main brings for secrets, with pinned betterleaks and its default rules; a fifth pin method, `release` (backlog #0-109) | secret scanning, betterleaks, push protection, CI supply chain | #0-109, #0-156 |
| [0037](0037-a-verifier-checks-what-the-panel-could-not.md) | A verifier agent checks the claims the review panel could not, never seeing the diff, with WebFetch from an allow-list that a session-level hook enforces by `agent_type` (backlog #0-160) | AI factory, review panel, WebFetch, hooks, devcontainer firewall | #0-160, #0-116 |
| [0038](0038-the-factory-is-reviewed-by-its-own-rule-file.md) | A change to the factory is judged by its own rule file, `factory.md` (`FAC-S` for security, `FAC-A` for architecture), read only when the diff touches an Owner-only path of `protected-paths.md` (backlog #0-161) | AI factory, review panel, review rules | #0-161, #0-136 |
| [0039](0039-claude-code-blocks-a-call-whose-hook-fails.md) | Every hook entry (both settings files, the agents' frontmatter) says `onFailure: "block"`, so Claude Code blocks a call whose hook cannot start, crashes or times out; Factory guards checks every entry, and the autopilot needs Claude Code 2.1.295 or newer (backlog #0-162) | AI factory, hooks, preflight, Factory guards | #0-162, #0-164 |
| [0040](0040-ci-pins-ruby-like-java-and-python.md) | CI pins Ruby like Java and Python: every job that runs a `.github/scripts/*.rb` check sets up the exact version in `.ruby-version` with `ruby/setup-ruby` (backlog #0-166) | CI, Ruby checks, supply chain | #0-166, #0-147 |
| [0041](0041-agent-guards-run-in-two-layers.md) | An agent's guard runs in two layers, its frontmatter and a session-level twin keyed on `agent_type` in both settings files; Factory guards keeps them equal and checks that the session-wide guards are wired (backlog #0-164) | AI factory, hooks, agents, Factory guards | #0-164, #0-160 |
| [0042](0042-the-owners-review-trusts-nothing-of-the-branch.md) | The owner's review trusts nothing of the branch: `/review` and `/accept` pre-approve exact commands, reviewers read their rules from `origin/main`, and the owner reads someone else's branch's `.claude/` changes before reviewing it; the hook refusing git's writing options is #0-167 (backlog #0-165) | AI factory, review panel, skills | #0-165, #0-161, #0-167 |
| [0043](0043-ci-pins-node-like-ruby.md) | CI pins Node like Ruby: the JavaScript tests run on `.node-version` through `.github/actions/setup-node`, with Node's built-in modules only (no npm); `check-ruby-setup.rb` becomes `check-runtime-setup.rb` for both, and `ci-local.sh` copies the same Node from its official image (backlog #0-163) | CI, Node, supply chain | #0-163, #0-166 |
