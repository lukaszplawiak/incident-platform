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
