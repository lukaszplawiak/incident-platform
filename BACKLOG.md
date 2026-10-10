# Backlog

Known work that is not done yet: defects, design decisions still to be made, and tech debt.
Code, Javadoc, config comments and commits reference items as `backlog #N`.

## Conventions

- **Numbering:** new items are numbered `0-1`, `0-2`, `0-3`, ... in order of creation and are
  referenced as `backlog #0-N` (always with the word `backlog`). Never reuse a number. The `0-`
  prefix keeps them apart from the legacy references `backlog #1`–`#82` that already exist in code
  and commit history (those items were never recorded in a file and are not backfilled here) and
  from GitHub issue/PR numbers, which use the same `#N` form.
- **Every `TODO`/`FIXME` in code must reference an item** (`TODO (backlog #N)`), per CLAUDE.md.
  Add the item here first, then reference it.
- **Status:** `Open` · `In progress` · `Blocked` · `Done`. A finished item leaves this file: its row
  moves to [`BACKLOG-DONE.md`](BACKLOG-DONE.md) with the PR, so references in code stay resolvable.
  `Blocked` names its reason in one line (`**Status:** Blocked — <reason> (autopilot run <id>)`).
- **Type:** `bug` · `design` (a decision is needed before implementation) · `tech-debt` ·
  `ci` · `docs`.
- **Autopilot fields** (optional; an item without them is never picked by the autopilot). A second
  line under the Type/Priority/Status line, and a third with the predicted reach:
  `**Autopilot:** ready | proposed | not-ready | human-only · **Risk:** low | high · **Complexity:** low | medium | high · **Depends on:** #0-N, …`
  `**Touches:** <module> (<packages>), …` — the modules the change is expected to reach (vocabulary and
  rules: `.ai/rules/planning.md`, "Touches").
  - `ready` is set **only by the owner**, after `.ai/rules/ready.md` holds (the `/ready` skill drafts it);
    the autopilot takes only `ready` items whose dependencies are `Done`, in the order of the approved
    queue `.ai/plan/queue.md` (`/plan-backlog` proposes it; without a queue, by priority).
  - `proposed` marks a follow-up the autopilot created from an implementer's "Follow-up needed"; it also
    carries `**Follow-up of:** #0-N` on the same line. It waits for `/ready` like any draft. Once `ready`
    and its parent is done, the autopilot takes it before the next queue row.
  - `Risk: high` means the owner merges it, whatever the reviewers say. `Complexity: low` allows the
    optional local-model implementer (`docs/ai-factory.md`).
  - An item that a reviewer or the audit later traces back to an escaped defect gets
    `**Fixes:** #0-N · **Escaped from:** review-<dimension>` on the same line; the audit counts these.
- **Acceptance criteria** for an autopilot item are numbered (`AC1.`, `AC2.`, …), each one checkable by a
  test or a CI check script (not a command run by hand), so that `acceptance-reviewer` can map each to evidence. Prose `**Acceptance.**`
  paragraphs stay valid for items done by hand.
- **Priority:** `High` (breaks a core promise or lets a known failure recur) · `Medium` ·
  `Low`.
- Changing what an item says about the code is a docs change: keep README, `CLAUDE.md` and
  `.ai/` in sync (see `.ai/README.md`).

## Open items

| # | Title | Type | Priority | Status |
|---|---|---|---|---|
| [0-2](#0-2-make-the-docker-compose-smoke-test-a-required-check-and-add-context-boot-tests) | Make the compose smoke test a required check; add context-boot tests | ci | High | Open |
| [0-3](#0-3-decide-token-revocation-coverage-across-services) | Decide token-revocation coverage across services | design | Medium | Open |
| [0-4](#0-4-escalation-event-is-published-at-most-once) | Escalation event is published at-most-once | tech-debt | Medium | Open |
| [0-5](#0-5-testcontainers-and-kafka-test-jars-ship-in-every-service-jar) | Testcontainers and Kafka test jars ship in every service jar | tech-debt | Medium | Open |
| [0-6](#0-6-untracked-todos-need-a-backlog-reference) | Untracked `TODO`s need a backlog reference | tech-debt | Low | Open |
| [0-13](#0-13-asymmetric-service-tokens-or-mtls-for-service-identity) | Asymmetric service tokens or mTLS for service identity | design | Medium | Open |
| [0-14](#0-14-by-slack-is-open-to-any-authenticated-role) | `GET /by-slack/{id}` is open to any authenticated role | tech-debt | Low | Open |
| [0-15](#0-15-incidentackclient-is-not-authorized-on-the-status-endpoint) | `IncidentAckClient` is not authorized on the status endpoint | bug | Medium | Open |
| [0-17](#0-17-alert-on-service_client_fallback_totalreasonauth) | Alert on `service_client_fallback_total{reason="auth"}` | tech-debt | Medium | Open |
| [0-20](#0-20-tenant-owned-fallback-contact) | Tenant-owned fallback contact | design | Medium | Open |
| [0-22](#0-22-the-operator-alert-is-email-only) | The operator alert is email only | tech-debt | Low | Open |
| [0-23](#0-23-the-oncall-retry-is-probably-inactive) | The `oncall` `@Retry` is probably inactive | tech-debt | Low | Open |
| [0-24](#0-24-on-call-contacts-are-not-verified-against-tenant-membership) | On-call contacts are not verified against tenant membership | design | Medium | Open |
| [0-25](#0-25-notificationqueueentry-has-no-version) | `NotificationQueueEntry` has no `@Version` | tech-debt | Medium | Open |
| [0-28](#0-28-notification_queue-rows-are-never-purged) | `notification_queue` rows are never purged | tech-debt | Low | Open |
| [0-29](#0-29-staging-and-prod-k8s-overlays-are-wholesale-swapped-not-just-their-namespace) | staging and prod k8s overlays are wholesale swapped, not just their `namespace:` | bug | Medium | Open |
| [0-31](#0-31-auth-service-identityconfig-split-evaluated-and-deferred) | auth-service identity/config split — evaluated and deferred | design | Low | Open |
| [0-32](#0-32-no-notification-channel-is-retried-after-a-failed-send) | No notification channel is retried after a failed send | design | Medium | Open |
| [0-33](#0-33-unify-caching-on-caffeine-once-a-third-cache-appears) | Unify caching on Caffeine once a third cache appears | tech-debt | Low | Open |
| [0-34](#0-34-servicetokenprovider-cache-has-no-metrics) | `ServiceTokenProvider` cache has no metrics | tech-debt | Low | Open |
| [0-35](#0-35-slack-install-via-oauth-add-to-slack-brings-back-ack-via-slack) | Slack install via OAuth ("Add to Slack") brings back ACK via Slack | design | Medium | Open |
| [0-36](#0-36-nothing-checks-entity-index-annotations-against-the-real-schema) | Nothing checks entity `@Index` annotations against the real schema | tech-debt | Low | Open |
| [0-37](#0-37-ingest-rate-limiter-answers-429-which-alertmanager-drops-without-retry) | Ingest rate limiter answers 429, which Alertmanager drops without retry | design | Medium | Open |
| [0-38](#0-38-integration-key-format-has-no-checksum-and-a-separator-inside-its-alphabet) | Integration key format has no checksum and a separator inside its alphabet | tech-debt | Low | Open |
| [0-40](#0-40-dead-letter-topics-have-no-consumer-or-replay-tooling) | Dead-letter topics have no consumer or replay tooling | design | Medium | Open |
| [0-41](#0-41-teamid-from-event-payloads-is-not-validated-against-the-tenants-teams) | `teamId` from event payloads is not validated against the tenant's teams | design | Low | Open |
| [0-42](#0-42-kubernetes-mail_host-points-at-a-mailhog-that-does-not-exist) | Kubernetes `MAIL_HOST` points at a `mailhog` that does not exist | bug | Low | Open |
| [0-43](#0-43-api-key-introspection-can-be-amplified-from-many-ips) | API key introspection can be amplified from many IPs | design | Low | Open |
| [0-44](#0-44-concurrent-cache-misses-for-one-api-key-each-call-auth-service) | Concurrent cache misses for one API key each call auth-service | tech-debt | Low | Open |
| [0-45](#0-45-api-key-usage-write-holds-a-second-auth-service-db-connection) | API key usage write holds a second auth-service DB connection | tech-debt | Low | Open |
| [0-46](#0-46-personal-api-keys-can-be-granted-scopes-their-owners-role-does-not-allow) | Personal API keys can be granted scopes their owner's role does not allow | bug | Low | Open |
| [0-48](#0-48-user-pii-lives-on-the-users-row-erasure-is-in-place-anonymization) | User PII lives on the `users` row; erasure is in-place anonymization | design | Low | Open |
| [0-55](#0-55-auth-email-failures-do-not-tell-an-smtp-outage-from-a-rejected-address) | Auth email failures do not tell an SMTP outage from a rejected address | design | Low | Open |
| [0-56](#0-56-forgot-password-leaks-whether-an-account-exists-through-response-time) | forgot-password leaks whether an account exists through response time | design | Medium | Open |
| [0-58](#0-58-shareds-kafka-tenant-classes-have-no-tests-of-their-own) | `shared`'s Kafka tenant classes have no tests of their own | tech-debt | Medium | Open |
| [0-62](#0-62-any-github-action-from-the-marketplace-is-allowed-to-run) | Any GitHub Action from the Marketplace is allowed to run | ci | Low | Open |
| [0-64](#0-64-kubernetes-pods-run-without-a-securitycontext) | Kubernetes pods run without a `securityContext` | design | Medium | Open |
| [0-65](#0-65-no-networkpolicy-every-pod-can-reach-every-other-pod) | No NetworkPolicy: every pod can reach every other pod | design | Medium | Open |
| [0-66](#0-66-redis-and-kafka-in-kubernetes-have-no-authentication-and-no-data-store-uses-tls) | Redis and Kafka in Kubernetes have no authentication, and no data store uses TLS | design | Medium | Open |
| [0-67](#0-67-all-seven-services-share-one-database-role) | All seven services share one database role | design | Medium | Open |
| [0-68](#0-68-sast-has-never-run-snyk-code-is-not-enabled-and-codeql-is-not-set-up) | SAST has never run: Snyk Code is not enabled, and CodeQL is not set up | ci | Medium | Open |
| [0-69](#0-69-the-snyk-dependency-scan-fails-on-every-run-so-a-new-finding-changes-nothing) | The Snyk dependency scan fails on every run, so a new finding changes nothing | ci | Medium | Open |
| [0-70](#0-70-built-images-are-not-scanned-and-no-sbom-is-produced) | Built images are not scanned, and no SBOM is produced | ci | Low | Open |
| [0-71](#0-71-kubernetes-and-compose-images-use-mutable-tags-and-some-are-not-tracked-by-renovate) | Kubernetes and compose images use mutable tags, and some are not tracked by Renovate | tech-debt | Low | Open |
| [0-72](#0-72-docker-compose-publishes-every-port-on-all-interfaces-with-default-credentials) | docker-compose publishes every port on all interfaces, with default credentials | tech-debt | Low | Open |
| [0-73](#0-73-swagger-ui-and-the-openapi-document-are-public-in-every-profile) | Swagger UI and the OpenAPI document are public in every profile | tech-debt | Low | Open |
| [0-74](#0-74-no-way-to-report-a-vulnerability-privately) | No way to report a vulnerability privately | docs | Low | Open |
| [0-75](#0-75-the-ingress-has-no-tls) | The Ingress has no TLS | design | Low | Open |
| [0-76](#0-76-kubeconform-is-installed-from-releaseslatest-unpinned-and-unchecked) | kubeconform is installed from `releases/latest`, unpinned and unchecked | ci | Low | Open |
| [0-77](#0-77-should-devtoken-require-an-explicit-switch-as-well-as-the-dev-profile) | Should `/dev/token` require an explicit switch as well as the dev profile? | design | Low | Open |
| [0-79](#0-79-at-hpa-maxima-during-a-rolling-update-the-connection-pools-exceed-what-postgres-allows) | At HPA maxima during a rolling update, the connection pools exceed what Postgres allows | design | Medium | Open |
| [0-85](#0-85-a-tenant-id-with-data-in-other-services-but-no-user-can-be-provisioned) | A tenant id with data in other services but no user can be provisioned | design | Low | Open |
| [0-86](#0-86-integration-tests-load-the-web-slice-test-configuration) | Integration tests load the web-slice test configuration | tech-debt | Low | Open |
| [0-87](#0-87-operator-mfa-enrolment-is-not-bound-to-the-invite) | Operator MFA enrolment is not bound to the invite | design | Low | Open |
| [0-94](#0-94-logs-are-plain-text-with-no-structure-escaping-or-collection) | Logs are plain text, with no structure, escaping or collection | design | Medium | Open (steps 1, 2 done: JSON, collection) |
| [0-95](#0-95-kafka-messages-have-no-envelope-correlation-id-schema-version-producer) | Kafka messages have no envelope (correlation id, schema version, producer) | design | Low | Open |
| [0-97](#0-97-kafka-consumers-on-defaulterrorhandler-and-a-dead-letter-replay) | Kafka consumers on `DefaultErrorHandler`, and a dead-letter replay | design | Low | Open |
| [0-98](#0-98-mfa-recovery-verifies-the-person-by-procedure-only) | MFA recovery verifies the person by procedure only | design | Low | Open |
| [0-99](#0-99-public-token-endpoints-have-no-request-limit) | Public token endpoints have no request limit | security | Low | Open |
| [0-100](#0-100-a-locally-built-jar-contains-the-developers-application-localyml) | A locally built jar contains the developer's `application-local.yml` | tech-debt | Low | Open |
| [0-101](#0-101-offboard-a-tenant) | Offboard a tenant | design | Medium | Open |
| [0-102](#0-102-the-pause-of-suspended-tenants-background-work-at-scale) | The pause of suspended tenants' background work at scale | performance | Low | Open |
| [0-105](#0-105-the-email-channels-worst-case-send-is-not-checked-against-the-scheduler-lock) | The email channel's worst-case send is not checked against the scheduler lock | performance | Low | Open |
| [0-106](#0-106-kubernetes-has-no-observability-stack) | Kubernetes has no observability stack | design | Medium | Open |
| [0-107](#0-107-no-alert-reads-log-content) | No alert reads log content | design | Low | Open |
| [0-108](#0-108-architecture-tests-archunit-for-the-invariants-in-claudemd) | Architecture tests (ArchUnit) for the invariants in CLAUDE.md | ci | Medium | Open |
| [0-109](#0-109-no-secret-scanning-in-ci-or-before-a-commit) | No secret scanning in CI or before a commit | ci | Medium | Open |
| [0-110](#0-110-maven-enforcer-allowed-repositories-and-pinned-plugin-versions) | Maven Enforcer: allowed repositories and pinned plugin versions | ci | Low | Open |
| [0-111](#0-111-openapi-contract-diff-on-pull-requests) | OpenAPI contract diff on pull requests | ci | Low | Open |
| [0-112](#0-112-mutation-testing-on-the-classes-a-pr-changes) | Mutation testing on the classes a PR changes | ci | Low | Open |
| [0-113](#0-113-repository-settings-the-ai-factory-depends-on) | Repository settings the AI factory depends on | ci | High | Open |
| [0-115](#0-115-rewrite-the-review-rules-as-invariants) | Rewrite the review rules as invariants | docs | Medium | Open |
| [0-116](#0-116-the-devcontainer-firewall-does-not-bind-code-an-agent-runs) | The devcontainer firewall does not bind code an agent runs | design | High | Open |
| [0-117](#0-117-the-escalation-level-bound-is-hard-coded-in-three-services) | The escalation level bound is hard-coded in three services | tech-debt | Low | Open |
| [0-118](#0-118-an-out-of-order-escalation-event-lowers-a-recorded-level) | An out-of-order escalation event lowers a recorded level | bug | Low | Open |
| [0-119](#0-119-postmortem-service-coerces-durationminutes-with-asint0) | postmortem-service coerces `durationMinutes` with `asInt(0)` | bug | Low | Open |
| [0-120](#0-120-kubernetes-staging-and-prod-overlays-are-swapped) | Kubernetes staging and prod overlays are swapped | bug | Medium | Open |
| [0-121](#0-121-a-pipeline-audit-traces-problems-to-the-stage-that-introduced-them) | A pipeline audit traces problems to the stage that introduced them | design | Medium | Open |
| [0-122](#0-122-protected-paths-one-list-a-touches-gate-and-the-stop-stage) | Protected paths: one list, a Touches gate and the stop stage | bug | High | Open |

---

### 0-2. Make the docker-compose smoke test a required check, and add context-boot tests

**Type:** ci · **Priority:** High · **Status:** Open

**Problem.** The "Docker Compose Smoke Test" was red on `main` for at least six consecutive runs
(2026-09-19) and PRs kept merging. The cause, a missing `TokenRevocationChecker` bean that stopped
incident-service from starting, was fixed in PR #410. It slipped through because
`StompAuthChannelInterceptorTest` mocks the checker, so no unit test boots the real context.

**Work.**
1. GitHub branch protection on `main`: require "Docker Compose Smoke Test" (a repository setting;
   skipped path-filtered jobs count as passing, so this does not block unrelated PRs).
2. Add a fast context-boot test per service (a slim `@SpringBootTest` or context runner) so wiring
   errors fail in "Build, Test & Coverage" (~2 min) and not only in the ~5 min compose run.
3. Optional: notify on failing `main` CI.

**Acceptance.** A PR that breaks service startup cannot merge, and the failure shows in the fast
job first.

---

### 0-3. Decide token-revocation coverage across services

**Type:** design · **Priority:** Medium · **Status:** Open

**Context.** Revocation (Redis `auth:revoked:{jti}`) is enforced only in auth-service. Every other
service, including STOMP `CONNECT` on incident-service, uses the no-op `TokenRevocationChecker`
from `SharedSecurityAutoConfiguration` (PR #410). A logged-out user's access token stays usable on
those services until it expires (default access-token TTL is 15 minutes; service tokens 1 hour).

**Questions to settle first.**
- Is a 15-minute window acceptable for this system (multi-tenant SOC / ops data)?
- If not: check per service, or once at a gateway?

**Options.**
1. Accept and document (current state); shorten the TTL if wanted.
2. Redis-backed checker in `shared`, registered where Redis is configured. Today only ingestion and
   auth have Redis; this adds a per-request lookup and a Redis dependency to the rest, plus a
   fail-open policy to agree on.
3. Enforce at a gateway or ingress once, in front of all services.

**Also decide.** A WebSocket authenticates only at `CONNECT`, so an open connection outlives a
logout under any option: periodic re-validation, a session-revoked event that closes connections,
or accept it.

**Deliverable.** A recorded decision (`.ai/README.md` asks for an ADR for technology decisions).
Implementation, if chosen, becomes its own item.

---

### 0-4. Escalation event is published at-most-once

**Type:** tech-debt · **Priority:** Medium · **Status:** Open

**Problem.** `EscalationScheduler.escalate()` marks the task `ESCALATED` in its own short
transaction and only then publishes `IncidentEscalatedEvent` to Kafka. If the process crashes in
between, the task is `ESCALATED` but the event is never sent: nobody is notified for that level.
The code documents this trade-off in a `TODO` (see backlog #0-6).

**Approach.** Transactional outbox for the escalation event, as already done for
`incidents.lifecycle` in incident-service (backlog #36: `IncidentEventOutbox` and its scheduler are
the reference implementation). Justified because a lost escalation is the failure the platform
exists to prevent.

**Acceptance.** State change and event staging commit atomically; a crash between them no longer
loses the notification; the `TODO` in `EscalationScheduler` is replaced by a `backlog #0-4`
reference or removed.

---

### 0-5. Testcontainers and Kafka test jars ship in every service jar

**Type:** tech-debt · **Priority:** Medium · **Status:** Open

**Problem.** `shared/pom.xml` declares `org.testcontainers:testcontainers`, `postgresql` and `kafka`
with `compile` scope, so `testcontainers-1.21.4.jar` and `kafka-1.21.4.jar` end up in
`BOOT-INF/lib` of every service's fat jar and Docker image.

**Approach.** Move them to `test` scope, or into a separate test-jar/module that services depend on
in `test` scope. Changing `shared` rebuilds all 7 services, so do it as its own PR.

**Acceptance.** The jars are absent from a built service jar (`jar tf`), all services still build,
and existing Testcontainers-based tests still pass.

---

### 0-6. Untracked `TODO`s need a backlog reference

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Problem.** CLAUDE.md forbids `TODO`/`FIXME` without a backlog reference. These have none; some
sit near comments that cite other items, so triage each one:

- `auth-service`: `AuthServiceApplication` (future service split — now linked, backlog #0-31); `User` and
  `UserManagementService` (Data Vault — now linked, backlog #0-48)
- `escalation-service`: `EscalationScheduler` (outbox for the escalation event, see backlog #0-4)
- `incident-service`: `IncidentKafkaConsumer` (per-severity topics); `IncidentCommandService`
- `ingestion-service`: `AlertKafkaProducer` (envelope pattern)
- `shared`: `TenantKafkaRecordInterceptor` (OpenTelemetry)

**Work.** For each: create or link an item and write `TODO (backlog #N)`, or delete it.
Optionally add a CI grep that fails on a new `TODO` without `backlog #`.

---

### 0-13. Asymmetric service tokens or mTLS for service identity

**Type:** design · **Priority:** Medium · **Status:** Open

**Context.** Every service holds the same HMAC secret (`jwt.secret`), so any service can mint a token
with any tenant and any role. Per-tenant service tokens (backlog #0-11) stop a forged header from
outside the platform but not a compromised service. The README "Design Decisions" section records why
RS256/Keycloak was rejected; this item asks whether that still holds.

**Options.** (1) Accept and keep documenting the limitation. (2) Asymmetric signing (RS256/EdDSA +
JWKS), private key only in auth-service, services verify only. (3) mTLS or a service mesh for service
identity, with authorization policy at the mesh. (4) OAuth2 client-credentials tokens issued by
auth-service, with `aud` and scopes.

**Deliverable.** A recorded decision (an ADR, per `.ai/README.md`). Implementation becomes its own item.

---

### 0-14. `GET /by-slack/{id}` is open to any authenticated role

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Problem.** oncall-service's `SecurityConfig` has exact-path rules only for `/current` and
`/current/all`, so `/api/v1/oncall/by-slack/{slackUserId}` falls through to
`anyRequest().authenticated()` and any role can read it. It returns less data than `/current`, but it
is an internal service-to-service lookup.

**Wider than oncall.** The same holds platform-wide: an endpoint that is only `authenticated()`
accepts every principal type. Since backlog #0-11 a service token authenticates, but only in the
service named in its `aud` claim, so an oncall-bound token cannot reach auth-service any more. Within
one service it still reaches every `authenticated()`-only route, and a controller that dereferences
`@AuthenticationPrincipal UserPrincipal` fails with a 500 instead of a 403 for a service caller.

**Decide.** The intended roles per route, and whether the shared chain should deny `ServicePrincipal`
by default and let each service opt routes in, rather than each service excepting itself.

---

### 0-15. `IncidentAckClient` is not authorized on the status endpoint

**Type:** bug · **Priority:** Medium · **Status:** Open (found by code reading, not run)

**Problem.** notification-service's `IncidentAckClient` calls `PATCH /api/v1/incidents/{id}/status`
with a service token (ACK via Slack). That endpoint has `@PreAuthorize("hasRole('RESPONDER') or
hasRole('ADMIN')")` and dereferences `principal.userId()`. After backlog #0-11 the token
authenticates, but as a `ServicePrincipal` with `ROLE_SERVICE`, so the call is answered with 403; and
`@AuthenticationPrincipal UserPrincipal` would be `null` for a service caller.

**Decision needed.** How a service acts on behalf of a user: a dedicated service endpoint that takes
`acknowledgedBy` from the request, or propagating the user's identity. Then add a test that sends a
real service token to this endpoint.

---

### 0-17. Alert on `service_client_fallback_total{reason="auth"}`

**Type:** tech-debt · **Priority:** Medium · **Status:** Open

**Problem.** Fail-open clients count every fallback in `service.client.fallback{client,target,reason}`
(backlog #0-11), and `reason="auth"` (401/403) is a misconfiguration, not an outage. Nothing alerts on
it. Also: `service.client.fallback` does not say which operation fell back (for example a target lookup or a PRIMARY
lookup); an `operation` tag needs a change in `shared`.
**Unblocked by #0-16 (Done):** a rule in `docker/prometheus.rules.yml` with `scope: platform` now reaches the
operator — by email if `severity: critical` (out of band, `docker/alertmanager.yml`), and as an incident of the
reserved `platform-operator` tenant in any case. Earlier it would have become an incident of the unowned `system` tenant.

---

### 0-20. Tenant-owned fallback contact

**Type:** design · **Priority:** Medium · **Status:** Open

**Context.** The proper counterpart of the "fallback users on each escalation level" in PagerDuty: a
tenant configures its own last-resort contact, used before an entry is parked as UNDELIVERABLE (backlog
#0-18 removed the shared fallback address, so today "nobody on call" means UNDELIVERABLE plus an alert to
the operator). No notification setting exists per tenant (`tenant_settings` in auth-service only has
`mfaRequired`).

**Decide.** Where it lives (auth-service tenant settings, or a notification-service table), whether it is per
tenant or per team (see #0-12), who may edit it, and how notification-service reads it (HTTP with the
service token, or a copy kept in sync).

Related, accepted trade-off of #0-18: `NO_ONCALL` is terminal on the first attempt (unlike an oncall-service outage, which is
retried), so a shift-handover gap parks an `INCIDENT_OPENED` notification as UNDELIVERABLE. A short retry for `NO_ONCALL`
is worth weighing together with the tenant-owned contact.

---

### 0-22. The operator alert is email only

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Problem.** The content-free alert for an UNDELIVERABLE notification (backlog #0-18) is sent by email only.
`SlackNotificationChannel.send` posts into the tenant's own workspace (backlog #0-21) and stores the message
against the incident id, which an operator alert must not do, so Slack and SMS operator alerts need a plain-message path of their own.
Without an operator address only the ERROR log and the `notification.undeliverable` metric remain, and an
alert on that metric is backlog #0-17.
The email is limited to one per tenant and reason per interval, in memory and per replica; a cross-replica limit
(for example Redis, which ingestion already uses) or a digest is possible if that proves noisy.
The alert is sent synchronously on the scheduler thread, and the SMTP timeouts bound each socket operation, not the whole send.

---

### 0-23. The `oncall` `@Retry` is probably inactive

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Problem.** `OncallClientImpl` methods carry `@Retry(name = "oncall")` and a `@CircuitBreaker` with a fallback. Each
fallback turns every failure into `OncallLookupUnavailableException` (or, for `findBySlackUserId`, an empty
result), and neither is in `retry-exceptions`, so the retry never sees the original `ResourceAccessException` or
`HttpServerErrorException`. Before #0-19 the fallback returned an empty result, so it did not fire either. The real
retry of the recipient lookup is the scheduler's (the entry stays PENDING). How Resilience4j orders Retry around the
breaker here has not been verified.

**Approach.** Confirm the behaviour with a test that runs the Spring proxies (the client tests call the methods
directly), then either remove the retry on these methods or make it fire. Correct the comment in `application.yml`.

---

### 0-24. On-call contacts are not verified against tenant membership

**Type:** design · **Priority:** Medium · **Status:** Open

**Problem.** The `email`, `phone` and `slackUserId` of an on-call entry are free text entered by whoever creates the
schedule. Nothing checks that they belong to a member of the tenant, so a tenant admin can point its own incident
content at any address or Slack user. It is the tenant's own content going where the tenant chose, so it is not a
cross-tenant leak, but "tenant content only reaches members of that tenant" is then enforced by trust and not by the
platform. Found by security review of #0-18.

**Approach.** Take the contact from the user record in auth-service (a member of the tenant) instead of from free
text, or verify the address on creation (a confirmation email). Hardening in the same area: `SlackNotificationChannel.isSlackUserId` is only `startsWith("U")`; a stricter shape (for
example `^U[A-Z0-9]{8,}$`) would narrow what a tenant-controlled id can address, and the router shares the predicate.
Related: #0-20 (tenant-owned fallback contact).

---

### 0-25. `NotificationQueueEntry` has no `@Version`

**Type:** tech-debt · **Priority:** Medium · **Status:** Open

**Problem.** CLAUDE.md lists optimistic locking (`@Version`) as the decided pattern for mutable entities, but the
notification outbox entity has none. Its writers (`markSent`, `markFailed`, and since #0-18/#0-19 `markUndeliverable`
and `recordLookupFailure`) save a detached entity, which is a merge that writes every mapped column as it was loaded.
That is safe today only because ShedLock serialises the scheduler; a run that outlives the lock, or an old pod during a
rollout, could overwrite another writer's status (for example the catch-all `markFailed` over an UNDELIVERABLE).

**Approach.** Either `@Version` as below, or what auth-service's `AuthEmailOutbox` does since #0-52: the scheduler as
the only writer, each transition a conditional `UPDATE ... WHERE id = ? AND status IN (...)` whose row count says
whether it applied. Add a `version` column (its own Flyway migration — V7 went to backlog #0-12's `team_id`
column, so this one is V8) and `@Version`, and decide how the scheduler treats
an `OptimisticLockingFailureException` (skip the entry; the next cycle reloads it).

---

### 0-28. `notification_queue` rows are never purged

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Problem.** Nothing deletes queue rows in any status: SENT and FAILED were never purged, and since #0-18 UNDELIVERABLE rows
accumulate as well. The partial index `idx_notification_queue_status_created (status, created_at) WHERE status = 'PENDING'`
keeps the scheduler fast, but the table only grows, and an operator browsing UNDELIVERABLE rows has no index for them.

**Approach.** A ShedLock-protected retention job (delete terminal rows older than a configurable age, keeping UNDELIVERABLE
longer), like `NotificationScheduler`'s existing `slack_message_ts` cleanup. Decide retention with the audit requirements in
mind: the audit events are the compliance record, the queue is a work queue.

---

### 0-29. staging and prod k8s overlays are wholesale swapped, not just their `namespace:`

**Type:** bug · **Priority:** Medium · **Status:** Open

**Priority raised from Low (2026-09-30 audit):** together with #0-63 it decided what a prod deployment would
really run; #0-63 is fixed, this one still sends "prod" to the staging namespace with staging images.

**Problem.** `k8s/overlays/staging/kustomization.yml` and `k8s/overlays/prod/kustomization.yml` each contain the
*other* environment's whole configuration, not just a swapped `namespace:` field. The file in `staging/` sets
`namespace: incident-platform-prod`, its resources comment reads "Prod-specific secrets", and its patches give
prod-shaped sizing: `incident-service` 3 replicas with 1Gi/512Mi memory limits, `ingestion-service` 3 replicas,
`notification-service`/`escalation-service`/`postmortem-service`/`oncall-service` 2 replicas each, an
`incident-service-hpa` patch (`maxReplicas: 5`), and every image pinned to `newTag: "1.0.0"` (a release version, not
an environment name). The file in `prod/` is the mirror image: `namespace: incident-platform-staging`, "Staging-specific
secrets", only `incident-service`/`ingestion-service` bumped to 2 replicas each (no HPA patch, no bump for the other
four services), and every image at `newTag: staging`. Kustomize's top-level `namespace:` field overrides every
resource's namespace regardless of what each overlay's own `secrets.yml` declares, so the namespace mismatch is real,
not just cosmetic. Found while investigating backlog #0-26; unrelated to it. (An earlier version of this entry
described only the `namespace:` field as swapped — a review of the full diff showed the whole file bodies are
swapped between directories.)

**Effect.** "Deploying prod" today would land `staging`-tagged images, at staging-level replica counts and no HPA
bump, into the `incident-platform-staging` namespace — and "deploying staging" would land `1.0.0`-tagged images at
prod-level scale into `incident-platform-prod`. Image tag and scale are wrong for whichever environment someone
believes they're targeting, not only the namespace label.

**Decide / do.** Confirm this is unintended (not, for example, a deliberate historical rename the directory names
never caught up to), then swap the two file bodies (or move the files) wholesale — editing only the `namespace:`
line in each, as a narrower read of this bug might suggest, would leave the replica counts, resource limits, HPA
target and image tags mismatched.

---

### 0-31. auth-service identity/config split — evaluated and deferred

**Type:** design · **Priority:** Low · **Status:** Open

**Context.** `AuthServiceApplication`'s own class Javadoc carries a `TODO (Backlog)` sketching a future split
(`auth-service` → authentication only; `identity-service` → users/teams/tenant settings; `apikey-service` →
machine-to-machine credentials, optional), explicitly flagged there as one of backlog #0-6's untracked TODOs. While
scoping backlog #0-21/#0-30, a *different* two-way split (identity/AuthN vs. config/integration, i.e. `ApiKey` +
`Integration` + `TenantSettings` together) was evaluated as an alternative to building #0-21 inside the existing
monolith. **Rejected for now, evidence below.**

**The TODO's own trigger doesn't apply yet.** Its stated criteria: *"Split when: independent scaling is needed,
separate teams own auth vs identity, or compliance (PCI-DSS, SOC2) mandates credential isolation. Do NOT split
prematurely."* None currently hold. Its own listed prerequisites also aren't done: a Redis credential cache for the
login hot path (`AuthService.login()` fetches `User` in the same transaction today), an outbox pattern for the
invite/user-deletion flow, and database separation.

**Why the user-proposed grouping (ApiKey+Integration+TenantSettings) isn't a clean file-move either, found by
reading the code:**
- `ApiKeyLookupServiceImpl.buildPrincipal` eager-fetches `User` (`ApiKeyRepository.findActiveByHash` does
  `LEFT JOIN FETCH k.ownerUser`) and reads `ownerUser.getRoleNames()`/`getEmail()` **in the same transaction, on
  every API-key-authenticated request** — not a one-off reference, a hot-path one.
- `Team` is needed by both proposed halves: `TeamService` (user-team membership, identity side) and
  `Integration.team` + `ApiKeyLookupServiceImpl.resolveTeamId` (config side, same hot path as above).
- `TenantSettings.isMfaRequired()` is read synchronously inside `AuthService.login()` — the TODO's own split
  already anticipated this by keeping `tenant_settings` with `identity-service`, not with `apikey-service`,
  which argues against grouping it with `Integration`/`ApiKey` as "config".
- `UserManagementService.archiveUser()`/`anonymizeUser()` write `User` and revoke the user's personal `ApiKey`s in
  one local `@Transactional` block today — a GDPR-relevant deletion flow that would become a distributed
  transaction post-split, exactly the class of problem the TODO's own "outbox pattern" prerequisite exists to solve
  first.
- Two FK constraints would cross the boundary (`api_keys.owner_user_id → users`, `integrations.team_id → teams`,
  both `ON DELETE SET NULL`) — schema-level, comparatively cheap — but the *ORM object-graph* coupling above is the
  real cost, not the FK syntax.
- The good news: the HTTP layer is already a clean boundary — none of the 6 controllers in auth-service mix both
  concerns.

**Decided.** Build backlog #0-21's new `SlackWorkspace` entity now, inside auth-service, shaped to require near-zero
rework if a split ever happens: no JPA relation to `User`, `teamId` stored as a plain column (not a `@ManyToOne`),
same flat-package convention `Integration`/`ApiKey` already use. Defer the actual split until the TODO's own trigger
conditions are met.

---

### 0-32. No notification channel is retried after a failed send

**Type:** design · **Priority:** Medium · **Status:** Open

**Problem.** Delivery is "at most once" per channel. `NotificationService.processEntry` tries each routed
channel once (plus, for Slack only, the in-call `@Retry` of `SlackApiClient`, which ran on a send only since #0-103;
email and SMS have none), records a failure in `notification_log`,
and marks the queue entry SENT anyway. A Slack API outage, an SMTP timeout or a Twilio error therefore loses
that channel's message for good. Backlog #0-21 adds one more cause: if auth-service cannot answer the
tenant's Slack-workspace lookup, Slack is skipped for that notification while email/SMS go out. That was a
deliberate choice: holding the whole entry (the #0-19 pattern) would make auth-service a hard dependency of
email and SMS, and retrying only the auth-service case would make one config lookup more reliable than the
send itself.

**What already exists.** Idempotency in `notification_log` is keyed per channel (incident + tenant + event type
+ escalation level + channel). If an entry stayed PENDING after a partial send, the next cycle would skip the
channels that already went out, so a per-channel retry needs no migration for that part. Since #0-104 one more cause loses a channel's message without a retry: while the `slack`
circuit breaker is open, Slack messages are recorded FAILED (`SLACK_UNAVAILABLE`, `circuit_open`) without being tried,
accepted until this item; a per-channel retry would send them once it closes. It costs more than a slow Slack did:
an open breaker fails a run's whole batch (up to `batch-size`, 200) in milliseconds, where a hanging Slack used to
reach about three entries per run and leave the rest PENDING (review of #0-104). Leaving such an entry PENDING, as a
failed on-call lookup does, needs this item's per-channel state: the failed Slack row in `notification_log` would
make the next run skip the channel. Since #0-93 every
channel failure carries a `NotificationFailureReason` marked permanent or not (a rejected address, a Slack channel
the bot is not in, versus an unreachable server or a rate limit), so what is worth retrying is already decided;
what is left is the per-channel state and its retry window.

**Open questions.** (1) The router asks oncall-service again on every cycle, and the on-call can change between
cycles: a retried Slack DM could go to someone other than the person who got the email. Pin the resolved
recipient on the entry, or accept it? (2) What does an entry become when the retry window runs out after some
channels were delivered? Not UNDELIVERABLE, since someone was reached. (3) `first_lookup_failure_at` is shared
with the #0-19 lookup window, so a per-channel window probably needs its own column or table. (4) Which failures
are worth retrying (5xx, timeout) and which are permanent (Slack `channel_not_found`, `invalid_auth`)?

**Approach.** Per-channel delivery state (pending / sent / failed-permanent) with backoff and a bounded window,
the same for every channel and cause. Not a Slack-only special case. Precedent since #0-52: auth-service's
`auth_email_outbox` (`next_attempt_at`, `deadline`) with `AuthEmailRetryPolicy` (backoff list, last step repeats,
bounded by a deadline, the last attempt moved to it), one writer with state-guarded conditional UPDATEs, and counters
for sends and give-ups.

---

### 0-33. Unify caching on Caffeine once a third cache appears

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Context.** The codebase has two in-memory caches, both hand-rolled `ConcurrentHashMap`s with a TTL and a
1000-entry cap (at the cap: evict expired, otherwise skip caching): `ServiceTokenProvider` (`shared`, one
token per tenant and audience, valid until token expiry minus a buffer, `synchronized` refresh so one token
is minted per miss) and `CachingSlackWorkspaceClient` (notification-service, backlog #0-21, 60 s TTL, caches
"no workspace" but never a failure, no lock around the HTTP call on purpose). No service configures Spring's
cache abstraction (`@EnableCaching`, `CacheManager`, Caffeine).

**Why not now.** Two caches, both working, each with semantics that would need custom configuration in
Caffeine (a per-entry `Expiry` for the token). Migrating means a new dependency, a change in `shared` (all 7
services rebuild), and ordering the cache aspect against Resilience4j on the same bean — `@Cacheable` is
applied by the same AOP proxy, so a self-call through `this` bypasses it exactly like the #0-21 circuit-breaker
bug. The gain (LRU instead of "skip when full", built-in metrics, less duplicated code) is small for two sites.

**Trigger.** A third cache. The likely one is the API-key lookup (`ApiKeyLookupServiceImpl.findActiveByHash`,
once per API-key-authenticated request) when `ApiKeyAuthFilter` is wired outside auth-service (backlog
#0-16 option 3, #0-30). It needs eviction on revoke (`@CacheEvict`), which is where the Spring abstraction
pays off. At that point introduce one Caffeine `CacheManager` and migrate both existing caches with it.

**Checked and deliberately not cached** (so nobody adds these believing they are missing):
- On-call lookups (`OncallClientImpl`, escalation's `OncallServiceClient`): rotations and overrides change who
  is on call; a stale entry pages the wrong person. Low volume, and #0-19's retry relies on fresh answers.
- `TenantSettings.isMfaRequired` (once per login): a security setting — a cached "false" would let logins skip
  MFA for a TTL after an admin enables it. One primary-key read.
- `IncidentAckClient` (a write), `GeminiClientImpl` (unique input per postmortem).
- Token revocation (`isRevoked`): already in Redis.

---

### 0-34. `ServiceTokenProvider` cache has no metrics

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Problem.** `ServiceTokenProvider` (`shared`) caches one service token per (tenant, audience), bounded at
`MAX_CACHED_TENANTS` = 1000. When the map is full of live entries it returns an *uncached* token, which means
minting a new JWT on every service-to-service call for that tenant. Today the only signal is a WARN log.
There is no hit rate, no size and no count of refused puts, so nobody can tell whether the cap is too small
or the refresh buffer too long. Every service calls another one through this class, so it runs on every
tenant's notification, escalation and ACK path.

**Approach.** Same as `CachingSlackWorkspaceClient` (backlog #0-21): a `CacheMeterBinder` subclass reading
`LongAdder`s, registered under `cache="service-token"`, so the meters use Micrometer's standard names
(`cache.gets{result=hit|miss}`, `cache.puts`, `cache.evictions`, `cache.size`) plus `cache.puts.skipped`.
A miss is a call that reaches `refreshAndGet`. Count it once per minted token, not per thread that waited on
the monitor and then found a fresh token (those are hits). `ServiceTokenProvider` is a `@Component` in `shared`, so
the `MeterRegistry` becomes one more constructor parameter. That touches every test that builds the class with
`new`, and a change in `shared` rebuilds all 7 services, which is why it was not folded into #0-21.

**Relation to #0-33.** If the move to Caffeine happens first, the standard meters come from
`CaffeineCacheMetrics` and this item reduces to tagging the cache. The meter names are the same either way,
so dashboards built on this item survive #0-33.

---

### 0-35. Slack install via OAuth ("Add to Slack") brings back ACK via Slack

**Type:** design · **Priority:** Medium · **Status:** Open

**Problem.** Backlog #0-21 connects a tenant's Slack by a manually pasted bot token. Without OAuth the only way an
admin can get an `xoxb-` token is from a Slack App they created in their own workspace. Slack signs every
interactive callback (a button click) with the signing secret of the App that posted the message, so each tenant's
clicks are signed with a secret the platform does not have. `SlackSignatureVerifier` checks one platform-wide
`SLACK_SIGNING_SECRET` and answers 401. The Acknowledge button was removed from messages rather than left to fail on
every click. `/api/v1/slack/actions`, `SlackActionService` and `updateMessageAfterAck` are kept unchanged. Found by
the #0-21 security review, where the DTO Javadoc (tenant's own App) and #0-21's reasoning for a global signing secret
(one App) contradicted each other.

**Options.**
- **(B, recommended) One platform App, OAuth v2 install.** An admin, logged in with the platform's own JWT, starts
  the install. The backend redirects to `slack.com/oauth/v2/authorize` with a signed, short-lived, single-use
  `state` bound to tenant and admin. The callback exchanges `code` + client secret via `oauth.v2.access` and stores
  the `xoxb-` token and `team.id` in the existing `SlackWorkspace`. The global signing secret is then correct and the
  button comes back as it was. This is standard for multi-workspace Slack apps (PagerDuty, Opsgenie). **It is Slack
  OAuth with the platform as the client, not an external identity provider for user login**, so the plan to stay on
  the platform's own JWTs (README "Design Decisions") is unaffected. Needs: `SLACK_CLIENT_ID`/`SLACK_CLIENT_SECRET`,
  public distribution enabled on the App (no Marketplace listing needed), an HTTPS redirect URL (a tunnel for local
  development), and a callback that is public in the filter chain (a browser redirect carries no JWT), so its whole
  security rests on `state`: test CSRF, replay and tenant mismatch.
- **(A) Keep one App per tenant, store its signing secret too.** The webhook reads the unverified `payload.team.id`,
  finds the workspace by `slack_team_id` (new internal auth-service lookup), verifies with that tenant's secret, then
  checks that the button's tenant matches the workspace's. It works without OAuth, but it needs a second encrypted
  secret (V18), manual Interactivity URL setup per tenant, and it becomes throwaway once (B) exists.
- Socket Mode was considered: with one App per tenant it means one WebSocket and app-level token per tenant held
  by notification-service. Rejected.

**Approach.** (B) when Slack is actually used by tenants. Until then incidents are acknowledged in the app.

---

### 0-36. Nothing checks entity `@Index` annotations against the real schema

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Problem.** Eight entities in six services (`Incident`, `IncidentHistory`, `AuditEvent`, `EscalationTask`,
`NotificationLog`, `NotificationQueueEntry`, `OncallSchedule`, `Postmortem`) list their indexes in
`@Table(indexes = ...)`. The Flyway migrations are the real schema. `ddl-auto: validate` does not check indexes,
so the lists are documentation that nothing verifies. `NotificationLog` drifted twice (V2 dropped its three
single-column indexes, V5 replaced one of V2's composites) before backlog #0-9 caught it by reading the code. Some
lists are also simplified on purpose: JPA cannot express a partial index, so `NotificationQueueEntry` shows
`(status, created_at)` for a `WHERE status = 'PENDING'` index.

**Options.** (1) A Testcontainers test per service that reads `pg_indexes` after the migrations and asserts every
`@Index` name on the service's entities exists (names only, since the definitions can't match exactly for
partial/expression indexes). Each service with Postgres integration tests already boots the migrated schema.
(2) Drop `@Index` from every entity and treat the migrations as the only documentation. This removes the drift
risk, but readers lose the at-a-glance list.

**Approach.** (1), added to each service's existing Postgres integration test, or a small shared test helper
in `shared`'s test utilities if one fits.


---

### 0-37. Ingest rate limiter answers 429, which Alertmanager drops without retry

**Type:** design · **Priority:** Medium · **Status:** Open (found while researching #0-16, not run)

**Problem.** `AlertIngestionController` answers 429 when the tenant or IP bucket is empty (around line 199).
Alertmanager's webhook notifier retries only network errors and 5xx and drops every 4xx, 429 included
(`notify/util.go`, `notify/webhook/webhook.go` in prometheus/alertmanager; the PagerDuty/Opsgenie notifiers add 429
to their retry codes, the webhook one does not). A tenant over its limit therefore loses its pages silently until
the next group flush, which is the failure the platform exists to prevent.

**Decide.** (1) 503 + `Retry-After` for webhook sources, so the sender retries within its flush window. (2) A limit
that does not reject per request (queue, or shed low severities first). (3) Keep 429 and document the loss.
Related: the response-code contract from #0-16 (a definite "no" is 4xx, "don't know / try later" is 503).

---

### 0-38. Integration key format has no checksum and a separator inside its alphabet

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Problem.** `ApiKeyHasher` produces `ipl_` + a base64url body. `_` and `-` are part of that alphabet, so the
separator can also appear inside the body (GitHub chose `_` for its token prefixes because it is not in their
body alphabet). There is no checksum, so a malformed key cannot be rejected without a lookup, and secret
scanning (GitHub's partner program asks for a unique prefix, high entropy and a 32-bit checksum) is weaker.
Entropy (24 random bytes, 192 bits) is fine.

**Work.** A new format, e.g. `ipl_` + base62 body + 6-character CRC32. Accept both formats during a transition.
Stored hashes need no migration, because the hash covers the full raw key.

**Acceptance.** New keys carry a checksum; a key with a bad checksum gets 401 without a lookup.

---

### 0-40. Dead-letter topics have no consumer or replay tooling

**Type:** design · **Priority:** Medium · **Status:** Open

**Problem.** `alerts.dead-letter`, `incidents.dead-letter`, `escalation.dead-letter`, `notification.dead-letter`
and `postmortem.dead-letter` are written by `DeadLetterPublisher` and read by nothing. A dead-lettered alert or
lifecycle event is lost unless someone reads the topic by hand, and nothing says that it happened.

**Decide.** (1) Alert on dead-letter growth (a metric plus a rule routed to the operator route from #0-16).
(2) A replay tool or endpoint, and who may use it: replaying a record re-enters tenant data into the pipeline, so
it needs the same per-record tenant resolution as the original consumers.

---

### 0-41. `teamId` from event payloads is not validated against the tenant's teams

**Type:** design · **Priority:** Low · **Status:** Open (found by code reading, not run)

**Problem.** `teamId` travels in `UnifiedAlertDto` and every `IncidentEvent` and is used for routing
(`IncidentCreationService`, escalation-service's `IncidentEventConsumer`, notification-service's
`NotificationRouter`) without checking that the team belongs to the tenant. The oncall lookups it feeds are
tenant-scoped, so a wrong id finds nobody rather than another tenant's people; the effect is a missed PRIMARY
lookup, not a leak.

**Decide.** Validate once where the id enters the platform (at incident creation, pulling from auth-service per the
#0-30 pattern), or accept and document it next to #0-12.


---

### 0-42. Kubernetes `MAIL_HOST` points at a `mailhog` that does not exist

**Type:** bug · **Priority:** Low · **Status:** Open
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** — (#0-16 is Done)
**Touches:** k8s (overlays/dev, base/infrastructure/app-config.yml), ci (.github/scripts, .github/workflows/ci.yml), notification-service (application.yml comments only), root (README.md "Infrastructure Hardening", .ai/context/infrastructure.md)

**Problem.** `k8s/base/infrastructure/app-config.yml` sets `MAIL_HOST: "mailhog"` and the dev overlay's comment says
the operator email is "caught by the same mailhog", but no manifest deploys a mail server. auth-service also requires
SMTP auth and STARTTLS (`mail.smtp.auth: true`, `starttls.required: true`) and notification-service requires auth,
so neither can send to a plain dev catcher. docker-compose had both gaps until backlog #0-16 added Mailpit and
overrode those properties with `SPRING_MAIL_PROPERTIES_*` environment variables for the compose stack.

**Work.** Deploy Mailpit in the dev overlay with the same overrides (or point `MAIL_HOST` at a real relay per
overlay), so dev on Kubernetes delivers invites and operator emails.

**Autopilot run (2026-10-10): BLOCKED** at the implementer, draft PR #477. AC9 needs a new `.github/` check
script and workflow step, which the autopilot may not write; the item was marked ready with `Risk: high` as if
that were enough. Now `human-only` (the owner builds it, from the architect's plan and Proposed ADR 0026 on
the branch of #477); the gap that let it through is closed by backlog #0-122 (the protected-path gate in
`next-item.sh`, `.ai/rules/ready.md` point 3).

**Decided (owner, `/ready #0-42`, 2026-10-10).**
- Mailpit runs in the dev overlay only, as a file in `k8s/overlays/dev/` listed in `resources`. Never in base,
  because its unauthenticated UI shows invite and password-reset links.
- The mail overrides are the compose ones (#0-16): `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH`, `..._STARTTLS_ENABLE`
  and `..._STARTTLS_REQUIRED` set to `"false"`, added to the dev `app-config` patch (`op: add`). Mailpit is not
  given a certificate or SMTP auth.
- Staging and prod get no relay. Base `MAIL_HOST` becomes a clearly commented placeholder, "replace before a
  real deployment", as #0-26 did for the operator address.
- The dev overlay's blanket Deployment patches are narrowed to `labelSelector: app.kubernetes.io/component=backend`
  (the 7 services carry it), so Mailpit and Redis stop receiving 768Mi and the 90/120 s probe delays.
- The Mailpit pod gets `allowPrivilegeEscalation: false`, drops `ALL` capabilities, uses `seccompProfile:
  RuntimeDefault`, and has resource requests and limits. `runAsNonRoot` and `readOnlyRootFilesystem` are set
  only if the image is verified to run with them. Probes are `httpGet` or `tcpSocket`.

**Acceptance criteria.**
AC1. The rendered dev overlay has a `Deployment` and a `ClusterIP` `Service`, both named `mailpit`, in
`incident-platform-dev`. The Service exposes 1025 (SMTP) and 8025 (HTTP), and its selector matches the pod labels.
AC2. In the rendered dev `app-config`, `MAIL_HOST` is the Mailpit Service name and `MAIL_PORT` is its SMTP port.
AC3. The rendered dev `app-config` sets `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH`,
`SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` and `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED` to `"false"`.
AC4. The rendered base, staging and prod contain no `mailpit` resource and no `SPRING_MAIL_PROPERTIES_*` key, and
base `MAIL_HOST` is a commented placeholder to replace before a real deployment.
AC5. The Mailpit image has an explicit version tag (never `latest`), equal to the tag in `docker/docker-compose.yml`.
AC6. The rendered dev contains no `mailhog`. The dev overlay comments and notification-service's `application.yml`
comments name Mailpit.
AC7. No Ingress and no Service other than `ClusterIP` exposes port 8025 in any overlay.
AC8. The dev overlay's blanket Deployment patches target only `app.kubernetes.io/component=backend`, and the
rendered Mailpit and Redis Deployments get neither the 768Mi memory nor the 90/120 s probe delays.
AC9. `.github/scripts/check-k8s-mail.sh` checks AC1–AC8 in the `validate-k8s-manifests` job, and its test
shows that a fixture breaking a rule fails it. `kubectl kustomize` and `kubeconform -strict` pass for base and
all three overlays.
AC10. README "Infrastructure Hardening" and `.ai/context/infrastructure.md` (the CI rules list) describe
Mailpit in dev on Kubernetes, the missing relay in staging and prod, and the new check.

---

### 0-43. API key introspection can be amplified from many IPs

**Type:** design · **Priority:** Low · **Status:** Open (accepted residual risk of #0-16, found in review, not run)

**Problem.** Every unknown `ipl_…` key sent to ingestion-service is one introspection call to auth-service.
`AuthFailureRateLimiter` bounds that per client IP (10 failures, refilled per 60 s), and the negative cache
(`CachingApiKeyIntrospectionClient`, 5 s, 1,000 entries) only helps when the same wrong key repeats. A sender
with many IPs and a fresh random key per request therefore turns its request rate into auth-service calls,
up to the IP bucket capacity times the number of IPs. It cannot authenticate (192-bit keys), and under
sustained load the `api-key-introspection` breaker opens, so ingest answers 503: the failure mode is
unavailability of key authentication, not a bypass. It also hurts valid keys that are not in the positive
cache while the breaker is open.

**Decide.** (1) Accept and document (today's state). (2) A global cap on introspection calls per second in
ingestion-service (bulkhead or rate limiter on the client), so an attack degrades only uncached keys.
(3) Reject malformed keys offline first, which needs the checksum from #0-38. (4) Edge rate limiting (the
Cloudflare/Ingress layers in `application.yml`'s rate-limiting comment). (2) and (3) are cheap together.

---

### 0-44. Concurrent cache misses for one API key each call auth-service

**Type:** tech-debt · **Priority:** Low · **Status:** Open (found in review, not run)

**Problem.** `CachingApiKeyIntrospectionClient.introspect` checks its maps and on a miss calls the delegate
directly, with no per-key coalescing. When an active key's entry expires (every 60 s), N concurrent requests
with that key send N introspection calls instead of one. Negligible at today's traffic; it grows with the
concurrency of a single integration and with the number of ingestion-service replicas.

**Work (when a high-throughput integration appears).** Single-flight per key hash (a map of in-flight
futures), or a cache library with an async loader — which is also the trigger for #0-33. Keep the rule that
failures are never cached.

---

### 0-45. API key usage write holds a second auth-service DB connection

**Type:** tech-debt · **Priority:** Low · **Status:** Open (found in review, not run)

**Problem.** `ApiKeyIntrospectionService.resolve` runs in a read-only transaction and calls
`ApiKeyUsageRecorder.recordUsage`, whose `last_used_at` update runs in `REQUIRES_NEW`: the outer transaction
is suspended and a second Hikari connection is taken for the update. auth-service's pool is 5
(`maximum-pool-size`). The write is throttled (once per `api-key.usage.write-interval`, default 5 min, per
key), so this matters only when many distinct keys miss the introspection cache at once — for example after
an ingestion-service restart (cold cache), when every integration's first request lands together.

**Work.** Load-test that cold-cache burst first. If the pool saturates: raise `maximum-pool-size`, or do the
write after the read transaction has ended (not nested), or hand it to a bounded executor with the metric
kept. The synchronous `REQUIRES_NEW` design is documented in `ApiKeyUsageRecorder`; a change reverses it and
should say so there.

---

### 0-46. Personal API keys can be granted scopes their owner's role does not allow

**Type:** bug · **Priority:** Low · **Status:** Open (found in #0-16 review, by reading the code, not run)

**Problem.** `ApiKeyScope.allowedForRole` decides which scopes a PERSONAL key may carry, and its own Javadoc
says a personal key "cannot be granted scopes beyond what the owner's tenant-level role permits". But it
only knows `ROLE_ADMIN` and `ROLE_RESPONDER`: every scope except `teams:write` is allowed to a RESPONDER,
including `alerts:ingest`, while the ingest endpoint lets a JWT in only with `INGESTOR` or `ADMIN`. So a
RESPONDER can create a personal key that does what their own token cannot. The same mapping decides every
other scope, so the check is not a real "no more than the owner" rule, only a hand-kept list. `ROLE_INGESTOR`
owners are not handled at all (they get no scope).

**Still latent.** Two places check a scope. Ingest's `hasScope(alerts:ingest)` is out of reach for personal
keys: #0-16 made auth-service's introspection answer `active:false` for every PERSONAL key. Since #0-89 the
team routes of auth-service need `teams:read` / `teams:write` (`SecurityConfig`, `ApiKeyAccess`; a key
reaches no other route of auth-service), but each also keeps its role check, which a key passes only with
its owner's current roles, so a scope this mapping wrongly allows gains nothing there. It becomes a real
bug as soon as a scope guards a route without a role check.

**Work.** Derive "allowed for role" from the same rule the endpoint enforces (e.g. `alerts:ingest` requires
`INGESTOR` or `ADMIN`), handle every role in `SecurityRoles`, and decide what happens to existing personal
keys whose scopes their owner no longer qualifies for (reject at use, or revoke). Test per role and scope.

---

### 0-48. User PII lives on the `users` row; erasure is in-place anonymization

**Type:** design · **Priority:** Low · **Status:** Open (was an untracked `TODO (Data Vault)`, see #0-6)

**Problem.** `User` stores `email` and `password_hash` on the `users` row. GDPR erasure
(`UserManagementService.anonymizeUser` → `User.anonymize`) overwrites them in place with a placeholder
and keeps the row, so historical references (audit log, incident assignments) stay valid. The residual
risk: any copy of the email outside that row (a cache, a log line, another service, a backup) can still
be linked to the user's UUID, and every new PII column has to be remembered in `anonymize()`.

**Option considered ("Data Vault" split).** Keep `users` PII-free (`id`, `tenant_id`, `active`, ...) and
move PII to a `personal_data` table (`user_id` FK, `email`, `password_hash`, ...); erasure becomes
`DELETE FROM personal_data WHERE user_id = ?`. Costs: a migration of existing rows, a join on the login path
and on every email lookup (`findByEmailAndTenantId`, the unique email-per-tenant constraint moves with it),
and it does not by itself remove copies held elsewhere.

Copies known so far include the audit log: events such as `USER_CREATED` and, since #0-80,
`TENANT_PROVISIONED` / `TENANT_ADMIN_REINVITED` carry the user's email address in their payload, and
anonymizing the user does not touch them.

**Work.** Decide whether the in-place approach is enough (document what "erased" covers, including logs
and backups) or plan the split, when a customer or audit requires stronger erasure guarantees. The
`TODO`s in `User` and `UserManagementService` point here.

---

### 0-55. Auth email failures do not tell an SMTP outage from a rejected address

**Type:** design · **Priority:** Low · **Status:** Open (found with #0-52)

**Problem.** `AuthEmailScheduler` treats every failed send alike: counted as
`auth.email.send{outcome="failed"}` and retried until the request's deadline. An SMTP connection or
authentication failure (the platform's problem) and a recipient the server rejects with a permanent
5xx, e.g. a typo in an invite address (the tenant's problem), look the same. So at low volume one
rejected address fires `AuthEmailDeliveryFailing`, a critical email to the operator about a tenant's
data, and a permanently rejected address is retried for 7 days for nothing.

**Approach.** Classify the exception from `JavaMailSender`: connection / authentication / timeout
(`MailAuthenticationException`, `MailSendException` caused by `ConnectException`,
`SocketTimeoutException`) versus a rejected recipient (`SendFailedException` with invalid addresses,
SMTP 5xx on RCPT). Tag the counter (`cause=transport|recipient|other`) and alert only on `transport`.
notification-service already does this classification (#0-93, `EmailNotificationChannel.classify`, with a
recipient rejection only for an SMTP reply naming the address: an enhanced `5.1.x`, or 550/551/553 without one,
so "550 5.7.1 Relaying denied" stays the platform's); moving it to `shared` would give both services one rule.
Decide whether a permanent recipient rejection gives up at once (`PERMANENTLY_FAILED`, reason
`RECIPIENT_REJECTED`) and how the tenant's admin learns about it (audit event, admin UI), since it
is not the operator's to fix. Check how 4xx (greylisting, mailbox full) is reported, since that must
keep being retried.

**Also: bounces after acceptance.** An entry is SENT once the SMTP server accepts the message; a later bounce
(mailbox does not exist, complaint) never reaches auth-service. With a real provider (SES, Postmark, SendGrid —
none is configured yet, and Kubernetes' `MAIL_HOST` points at a missing host, #0-42) consume its bounce/complaint
webhook or notification, mark the entry, and tell the tenant's admin, the same "tenant's problem, not the
operator's" route as a rejected recipient.

---

### 0-56. forgot-password leaks whether an account exists through response time

**Type:** design · **Priority:** Medium · **Status:** Open (found with #0-52)

**Problem.** `POST /api/v1/auth/forgot-password` always answers 202, but does different work on the request
thread depending on the account: an unknown email sleeps in `WorkSimulator` (8–11 ms, calibrated once to the
"user exists" path), a reset already PENDING returns after one read, and a new reset looks up the user
and inserts the outbox request (since #0-52 the token is created later, by the scheduler). Each branch
has its own latency, so response times measured at scale tell which emails have accounts. The calibration
drifts with every change to the "user exists" path. The endpoint has no rate limit, which makes the
measurement cheap and also lets anyone flood a mailbox with reset emails.

**Approach.** (A) Take the work off the request path, as the OWASP Forgot Password Cheat Sheet suggests:
the endpoint only records "reset requested (email, tenant)" and returns 202, the same single insert whether or
not the account exists; a scheduled job (ShedLock) resolves the user, invalidates old tokens, creates the new
one and writes the email outbox entry. `WorkSimulator` then goes away. Needs a table (and a retention rule for
requests about unknown emails, which hold an email address). (D) Rate limit per IP and per email with bucket4j on
Redis, the pattern ingestion-service already uses (#67), answering 202 either way.

---

### 0-58. `shared`'s Kafka tenant classes have no tests of their own

**Type:** tech-debt · **Priority:** Medium · **Status:** Open (found with #0-57)

**Problem.** Once the JaCoCo agent attached (#0-57), `shared`'s own tests turned out to cover 0% of
`TenantKafkaRecordResolver`, `TenantKafkaProducerInterceptor`, `TenantKafkaConsumerInterceptor`,
`DeadLetterPublisher` and `GlobalExceptionHandler`, and little of `AuditEventKafkaSender` and
`UnauthorizedEntryPoint`. Service tests use most of them (consumer tests in four services, security
tests everywhere), often as mocks, and JaCoCo counts only a module's own tests. So the per-record
tenant resolution that CLAUDE.md calls the most easily broken property has no test of its contract
where it is defined: header first, payload `tenantId` fallback, dead-letter otherwise, and a blank or
reserved tenant. `TenantKafkaConsumerInterceptor` has no test anywhere.

**Work.** Unit tests in `shared` for each class's contract, starting with `TenantKafkaRecordResolver`
and the two interceptors (a header/payload mismatch was #0-39, closed by #0-91/#0-92). Until then, a PR
that changes one of them fails CI's changed-lines coverage gate unless it adds those tests, which is
the intended pressure.

**Progress (backlog #0-91/#0-92).** `TenantKafkaRecordResolver`, `TenantKafkaProducerInterceptor`,
`TenantKafkaConsumerInterceptor` and `DeadLetterPublisher` now have their own tests in `shared`
(`TenantKafkaRecordResolverTest`, `TenantKafkaProducerInterceptorTest`, `TenantKafkaConsumerInterceptorTest`,
`DeadLetterPublisherTest`), and the header/payload mismatch (#0-39) is decided and tested. Left:
`GlobalExceptionHandler`, `UnauthorizedEntryPoint`.

**Also below 60% (instruction coverage, measured with #0-57), not tenant-critical:** auth-service
`IntegrationService`, `TenantSettingsService`, `TenantSettings`, `TeamMember`, `Integration`;
incident-service `IncidentWebSocketPublisher`, `IncidentWebSocketController`,
`IncidentEventOutboxPersistenceService`; notification-service `SlackActionService`, `SlackMessageTs`;
postmortem-service `GeminiClientImpl`. Pick them up when they are next changed; the gate asks for it.

---

### 0-62. Any GitHub Action from the Marketplace is allowed to run

**Type:** ci · **Priority:** Low · **Status:** Open (found with #0-59)

**Problem.** The repository's Actions policy is `allowed_actions: all` (Settings → Actions → General;
`GET /repos/{owner}/{repo}/actions/permissions`), so any workflow change can pull in any third-party
action, and only review stands between a typo-squatted or abandoned action and the job's token and
secrets. #0-60 fixes *which version* of an action runs; this is about *which actions* may run at all.

**Approach.** Switch to "Allow {owner}, and select non-{owner}, actions and reusable workflows": allow
actions created by GitHub, and list the others by owner or repository (today `dorny/paths-filter`,
`madrapps/jacoco-report`, `azure/setup-kubectl`, `docker/*`; `github/codeql-action` is GitHub's own).
Adding a new third-party action then needs a deliberate settings change as well as a PR. Prefer an
explicit list over "verified creators", which admits every verified publisher. Record the list in
README "Infrastructure Hardening", since the setting is invisible in diffs. It takes two calls:
`PUT /repos/{owner}/{repo}/actions/permissions` sets `allowed_actions=selected`, and
`PUT .../actions/permissions/selected-actions` sets the list. The first call also carries
`sha_pinning_required` (on since #0-60), and GitHub's docs do not say what omitting it does: send
`sha_pinning_required=true` with it and re-read the settings afterwards, so narrowing the allowed
actions does not switch SHA pinning off.

---

### 0-64. Kubernetes pods run without a `securityContext`

**Type:** design · **Priority:** Medium · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.** No Deployment or StatefulSet in `k8s/base` sets a pod or container `securityContext`, and the namespace
(`k8s/base/namespace/namespace.yml`) carries no Pod Security Admission label. The service images already run as a
non-root user (`USER appuser` in every Dockerfile), but nothing in the manifests enforces it, so an image that drops
that line would run as root unnoticed. Containers also keep the default capabilities, may escalate privileges, have a
writable root filesystem and no seccomp profile, and every pod gets the namespace's service account token mounted
although no service calls the Kubernetes API.

**Approach.** Label the namespace `pod-security.kubernetes.io/enforce: restricted` (and `warn`/`audit`), then give every
workload `runAsNonRoot: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`,
`seccompProfile.type: RuntimeDefault`, `readOnlyRootFilesystem: true` with an `emptyDir` for `/tmp` (the JVM and
Tomcat write there), and `automountServiceAccountToken: false`. Postgres, Redis and Kafka need their own check: their
images expect to write to their data directories and some start as root.

---

### 0-65. No NetworkPolicy: every pod can reach every other pod

**Type:** design · **Priority:** Medium · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.** `k8s/` has no NetworkPolicy, so the namespace is flat: any pod reaches Postgres, Redis, Kafka, every
service's management port (8091–8097) and incident-service's `/dev/**` wherever the dev profile is on. One compromised
or misbehaving pod can use all of them. This is what made #0-63 exploitable from inside the cluster even though the
Ingress does not route `/dev`.

**Approach.** A default-deny policy for ingress (and later egress) in the namespace, then explicit allows: the Ingress
controller to the API ports, each service to the data stores it uses (auth-service and ingestion-service to Redis,
everything but oncall-service to Kafka, all services to Postgres), service-to-service calls that exist
(notification → incident, oncall, auth; escalation → oncall; ingestion → auth; postmortem-service has an
`incident-service.base-url` setting but no code calls it), and the
monitoring namespace to the management ports. Egress default-deny also needs DNS (kube-dns) for every pod and the
external calls: postmortem-service to the Gemini API, notification-service to Slack, the SMTP server and the SMS
provider, auth-service to the SMTP server. Needs a CNI that enforces NetworkPolicy; Minikube's default does not.

---

### 0-66. Redis and Kafka in Kubernetes have no authentication, and no data store uses TLS

**Type:** design · **Priority:** Medium · **Status:** Open (found in the 2026-09-30 infrastructure security audit;
the Postgres part is done: the Secret with #0-78, the default password in PR #444)

**Problem.**
- **Redis** (`k8s/base/infrastructure/redis.yml`) runs `redis-server --appendonly yes` with no password. It holds
  the token revocation list and the rate-limit buckets, so any pod can un-revoke a token or reset a limit.
- **Kafka** (`kafka.yml`) listens `PLAINTEXT` with no SASL and no ACLs. Any pod can produce to `alerts.raw` or
  `incidents.lifecycle` for any tenant: since #0-92 consumers refuse a record whose header and payload disagree,
  but a forged record naming one tenant in both is taken as that tenant's.
- **Postgres** (done): since #0-78 the password comes from each overlay's `app-secrets` (`DB_PASSWORD`, passed to the
  six services that use the database and to the init script that creates the role), and the base no longer ships a
  Secret. `application.yml` used to fall back to `${DB_PASSWORD:incident_secret}`, so a run without the variable
  connected with the dev password; it now reads `${DB_PASSWORD}` with no default, and a CI step ("No service has a
  default database password") keeps it that way. No in-app check was added: Kubernetes (`secretKeyRef`) and compose
  (`${VAR:?}`) already stop a missing password with a clear message (an empty Kubernetes value gets
  through and fails like a wrong password), and for `spring-boot:run` the symptom ("password
  authentication failed", since Spring Boot leaves an unresolved placeholder in `spring.datasource.*`) is documented
  in `docs/database-roles.md`.
- No connection to any of the three uses TLS.

**Approach.** Redis `requirepass`
(or ACL users) from a Secret, wired to `REDIS_PASSWORD`, which the services already read. Kafka SASL/SCRAM with a
user per service and ACLs per topic, which also closes the forged-header path. TLS on all three once a certificate
source exists (#0-75). Pairs with #0-67.

---

### 0-67. All seven services share one database role

**Type:** design · **Priority:** Medium · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.** Every service connects as `incident_app`, which owns the database, the `public` schema and every table
in it (since #0-78 it is no longer a superuser, but as the one owner it still has every right on every table, and an
owner bypasses Row-Level Security unless a table sets `FORCE ROW LEVEL SECURITY`; and as an owner it can create
triggers and functions that run with the rights of whoever fires them, including the admin). That
each service owns its own tables (CLAUDE.md "Persistence") is a convention; the database does not enforce it. A SQL injection or code execution bug in any service, including the least trusted ones
(postmortem-service handles LLM output), can read and write auth-service's tables: Argon2 password hashes, encrypted
MFA and Slack secrets, API key hashes, auth tokens.

**Approach.** Builds on #0-78 (done: `incident_app` is no longer a superuser, `postgres` is the admin; see
`docs/database-roles.md`). One role per service that owns its tables and its
Flyway history table, with no rights on other services' tables; a separate migration role if Flyway should not run as the runtime role. Existing tables need an
ownership transfer migration per service. Decide whether each service gets its own schema (cleaner grants,
`search_path` per role) or stays in `public` with per-table grants.

---

### 0-68. SAST has never run: Snyk Code is not enabled, and CodeQL is not set up

**Type:** ci · **Priority:** Medium · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.** The `snyk-code` job in `snyk.yml` fails on every run with `403 Snyk Code is not enabled (SNYK-CODE-0005)`
(verified on the #0-61 dispatch run), and `continue-on-error: true` shows it as a success. No SARIF is produced, so
the Security tab has never had a code-scanning result, while README describes the job as "static analysis of Java
source code". GitHub's CodeQL, free for public repositories, is not configured (`code-scanning/default-setup`:
`not-configured`, languages `actions`, `java-kotlin`).

**Approach.** Enable CodeQL default setup (Java and Actions; the Actions queries also check workflows for injection
and permission problems), then remove the `snyk-code` job, or enable Snyk Code on the account if a second SAST engine
is wanted. Correct README "Security Scanning" either way.

---

### 0-69. The Snyk dependency scan fails on every run, so a new finding changes nothing

**Type:** ci · **Priority:** Medium · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.** `snyk test --severity-threshold=high` reports 42 issues (run of 2026-09-30; 37–45 on earlier runs), so the
dependency job is red on every push to `main` and every weekly run. A red job that is always red carries no signal: a
new critical CVE would not change its status. OWASP Dependency-Check, by contrast, is green.

The existing `.snyk` ignores are not a sound baseline either. The Tomcat entries (comment at `.snyk:22`, reasons at
lines 30, 38 and 125) justify the risk with "All endpoints require JWT authentication. No unauthenticated access",
which is false: login, health, Swagger (#0-73) and the Slack webhook are public, and ingestion-service takes API keys,
not JWTs. It would not be a mitigation even if it were true, since a Tomcat CVE is reached at the HTTP layer, before
any Spring Security filter runs. The same false claim was in `owasp-suppressions.xml`'s DOMPurify note, corrected
on 2026-10-01.

**Approach.** Triage each finding: upgrade where a fixed version exists (overrides go in the root `pom.xml` properties
with the CVE named, per CLAUDE.md), suppress in `.snyk` with a reason and an expiry where the vulnerable code is not
reachable, until the job is green; then keep it green. Re-check every existing `.snyk` ignore the same way and rewrite
the Tomcat reasons on what actually limits exposure (which Tomcat feature the CVE needs and whether the services use
it), or drop the ignore where nothing does.

---

### 0-70. Built images are not scanned, and no SBOM is produced

**Type:** ci · **Priority:** Low · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.** OWASP Dependency-Check and Snyk scan the Maven dependency tree only. The operating system packages of the
`eclipse-temurin:*-jre-alpine` runtime images, and the JRE itself, are scanned by nothing. CI builds all seven images
(`push: false`) but does not scan them, and no SBOM records what an image contains.

**Approach.** Scan each built image in the Docker job (Trivy or Grype, pinned by version and checksum like the Snyk CLI,
#0-61), failing on fixable high and critical findings, and emit an SBOM (Syft or `docker buildx` attestations) as a
build artifact.

---

### 0-71. Kubernetes and compose images use mutable tags, and some are not tracked by Renovate

**Type:** tech-debt · **Priority:** Low · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.**
- The service Deployments in `k8s/base` use `image: <service>:latest` with `imagePullPolicy: IfNotPresent`; the
  overlays replace the tag with `dev`, `staging` or `1.0.0`, all mutable, so a node can keep running an old image
  under the same tag.
- Third-party images in `k8s/` are not tracked by Renovate, whose `kubernetes` manager needs file patterns that
  `renovate.json` does not set: `apache/kafka:3.7.0` (compose runs 3.9.2), `redis:7-alpine`, `postgres:16-alpine`,
  `busybox:1.36`.
- `docker/docker-compose.yml` uses `:latest` for `provectuslabs/kafka-ui`, `danielqsj/kafka-exporter` and
  `dpage/pgadmin4` (`grafana/grafana` was pinned to `13.2.3` in #0-94 step 2, as it reads every tenant's logs).

**Approach.** Pin every third-party image to a version tag (and a digest where Renovate can keep it current), enable
Renovate's `kubernetes` manager for `k8s/**/*.yml`, and deploy service images by immutable tag (the commit SHA) or
digest once a registry exists.

---

### 0-72. docker-compose publishes every port on all interfaces, with default credentials

**Type:** tech-debt · **Priority:** Low · **Status:** Open — Grafana done in #0-94 step 2 (found in the 2026-09-30 infrastructure security audit)

**Problem.** Every `ports:` entry in `docker/docker-compose.yml` has the form `"5432:5432"`, which binds `0.0.0.0`,
so on any shared network (office, café) other machines reach Postgres (the services' role with `incident_secret`, and
since #0-78 the `postgres` superuser with `postgres_admin_dev`, both the `docker/.env.example` values), Redis (no password), Kafka
(plaintext), pgAdmin (`admin`), Prometheus (with `--web.enable-lifecycle`, which lets anyone
reload or shut it down), Alertmanager and every service's management port. Docker's port publishing also bypasses
host firewalls such as `ufw`. Grafana is no longer one of them: since it reads every tenant's logs (#0-94 step 2)
it is on `127.0.0.1:3000` with `GRAFANA_ADMIN_PASSWORD` (no default) instead of `admin`/`admin` on every interface.

**Approach.** Prefix every published port with `127.0.0.1:`. Services talk to each other on the compose network and
need no published ports for that; only the ports a developer opens in a browser or a client need publishing.

---

### 0-73. Swagger UI and the OpenAPI document are public in every profile

**Type:** tech-debt · **Priority:** Low · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.** `SharedSecurityAutoConfiguration.PUBLIC_PATHS` permits `/v3/api-docs/**`, `/swagger-ui/**` and
`/swagger-ui.html` in every service and profile, and springdoc runs with its defaults (enabled) in every service that
has it: auth, ingestion, incident, notification, oncall and postmortem. escalation-service has no HTTP API and no springdoc. An unauthenticated caller gets the full API description of each of those services, including internal
endpoints. The Ingress routes only `/api/v1/*` and
`/ws` today, which hides it from outside the cluster, but that is a routing detail, not a control.

**Approach.** Disable springdoc outside `local`/`dev` (`springdoc.api-docs.enabled=false`,
`springdoc.swagger-ui.enabled=false` by default, enabled in those profiles). Those properties go in each of the six
services' `application.yml` (and a dev-profile file where the k8s dev overlay should show the docs). The public paths
are in `shared`'s `PUBLIC_PATHS`, so dropping them, or permitting them only when springdoc is enabled, changes all
seven services at once.

---

### 0-74. No way to report a vulnerability privately

**Type:** docs · **Priority:** Low · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.** The repository is public but has no `SECURITY.md`, private vulnerability reporting is off
(`private-vulnerability-reporting`: `enabled: false`), and Dependabot alerts are off (`vulnerability-alerts`: 404).
Someone who finds a vulnerability has only public issues to report it in, and GitHub's own advisory matching does not
alert on the dependency graph. Renovate updates dependencies but is not an alerting channel.

**Approach.** Add `SECURITY.md` (supported versions, how to report, expected response), turn on private vulnerability
reporting and Dependabot alerts (alerts only; Renovate keeps doing the updates).

---

### 0-75. The Ingress has no TLS

**Type:** design · **Priority:** Low · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.** `k8s/base/infrastructure/ingress.yml` has no `tls:` section, so login requests, JWTs and Integration API
keys cross the network in plain text, and the HSTS header every service sends is ignored by browsers over HTTP.

**Approach.** A `tls:` block per overlay with a certificate from cert-manager (or the cloud provider's), and
`nginx.ingress.kubernetes.io/ssl-redirect: "true"`. The dev overlay can keep plain HTTP on Minikube.

---

### 0-76. kubeconform is installed from `releases/latest`, unpinned and unchecked

**Type:** ci · **Priority:** Low · **Status:** Open (found while fixing #0-63)

**Problem.** The `validate-k8s-manifests` job in `ci.yml` downloads
`https://github.com/yannh/kubeconform/releases/latest/download/kubeconform-linux-amd64.tar.gz` and pipes it into
`tar`: whatever release is latest at that moment, with no checksum. The same class of problem as #0-61, with less at
stake (that job has no secrets and a read-only token), but a new release can still change results with no change in
this repository.

**Approach.** Pin the version and verify the archive's SHA-256 (published in the release's `CHECKSUMS` file), as
`snyk.yml` does for the Snyk CLI.

---

### 0-77. Should `/dev/token` require an explicit switch as well as the dev profile?

**Type:** design · **Priority:** Low · **Status:** Open (from the review of #0-63)

Not a committed fix: decide first whether it is worth doing.

**Problem.** `DevTokenController` exists whenever the `local` or `dev` profile is active, and its startup guard only
rejects a missing profile. #0-63 showed that one wrong line in a shared ConfigMap is enough to give a production
deployment the dev profile. Since #0-63 the CI check on the rendered overlays catches that, but it is a tripwire for
accidents in `k8s/`, not a property of the application.

**Approach, if taken.** Require an explicit property on top of the profile (for example
`dev.token-endpoint.enabled=true`, `@ConditionalOnProperty`), set only by `application-local.yml` and the dev overlay.
Against: a second setting to keep in sync for a dev-only convenience, and the profile check plus the CI check may be
enough. Decide before implementing.

---

### 0-79. At HPA maxima during a rolling update, the connection pools exceed what Postgres allows

**Type:** design · **Priority:** Medium · **Status:** Open (found in the review of #0-78)

**Problem.** In Kubernetes every service that uses the database keeps a Hikari pool of 5 connections
(`DB_POOL_SIZE` in `app-config`; auth-service hard-codes 5). The base HPAs allow up to 3 replicas of auth-service and
incident-service and 2 of each of the other four, which is 70 connections; the staging overlay raises incident-service
to 5 (80), dev caps every HPA at 1 (30). If every Deployment rolls at the same time, each adds one surge pod
(`maxSurge: 1`): 100 in prod, 110 in staging. Postgres runs with the default `max_connections` of 100. Since #0-78 the services' role is not a
superuser, so it cannot use the 3 connections reserved for superusers (`superuser_reserved_connections`), and gets 97.
A deploy at peak load can then fail new pods with "too many connections". Before #0-78 the margin was zero. The
Postgres pod's 512Mi memory limit also caps how far `max_connections` can simply be raised. The admin can still log in
when the services have taken every other connection, which is the reserve's purpose.

**Approach.** Decide with a measurement, not by guessing a number. The options are:
- size `max_connections` together with the pod's memory (`-c max_connections=...` on the StatefulSet);
- lower `DB_POOL_SIZE` per pod, which needs a throughput check;
- put a pooler (PgBouncer) in front once the replica counts grow.

`docs/database-roles.md` explains the arithmetic.

---

### 0-82. Suspend and offboard a tenant

**Type:** design · **Priority:** Medium · **Status:** Done, PRs #456, #457, #458, #459 (split out of #0-80; offboarding moved to #0-101). Kept in full as
the record of the decisions, which the code and the other docs refer to.

**Problem.** (As written before step 1.) Since #0-80 a platform operator creates tenants
(`POST /api/v1/platform/tenants`), but nothing ends one. A customer who stops paying, breaches terms or leaves keeps logging in, its
Integration API keys keep filing alerts, and its data stays in all seven services. Today the only
lever is archiving its users one by one in the database.

**Two different things.**
- **Suspend** (reversible): the tenant's users cannot log in or refresh, its API keys introspect as
  inactive, and its incidents stop notifying, while its data stays. Enforcement points:
  - auth-service login, refresh, accept-invite and password reset: refuse when the tenant is suspended;
  - API key introspection (ingestion-service, 60 s cache) and auth-service's own API key lookup:
    `active:false`;
  - access tokens already issued live up to 15 minutes, and `JwtAuthFilter` in `shared` knows nothing
    about tenant status. Either accept that window, or carry status to every service (a revocation
    entry per tenant in the Redis check `TokenRevocationChecker` already does; ties into #0-3, which
    decides which services check revocation at all);
  - Kafka: alerts already accepted keep flowing; decide whether incident-service drops them or
    notification-service suppresses them.
- **Offboard** (irreversible): export the tenant's data on request, then delete or anonymize it in
  every service's tables (incidents, audit, notifications, on-call, postmortems, users, teams, keys,
  Slack workspace) and in Redis. Needs an orchestrated, resumable job across services, a retention
  decision for the audit log, and the same PII considerations as #0-48.

**Approach.** (The original sketch; what was decided, and what step 1 did, follows below under
**Decided** and **Progress**.) Design first. Add a `status` column to `tenants` (V21 deliberately has none) with
`ACTIVE` / `SUSPENDED` / `OFFBOARDING` / `OFFBOARDED`, operator endpoints under
`/api/v1/platform/tenants/{id}` with the same `PlatformAccess` rule, audit events in the operator
tenant, and the enforcement points above. Suspension first; offboarding is larger and can follow.

**Decided (2026-10-04, from the enterprise pattern: Azure tenant life cycle, AWS SaaS Lens).** Two
suspension modes, `FULL` (a taken-over tenant, a terms breach) and `READ_ONLY` (a billing hold: reads,
account security), with a reason and a note; background work of a suspended tenant is paused and
resumed, not dropped; three steps: (1) the status, the operator API and enforcement in auth-service
plus a shared filter every chain carries; (2) enforcement everywhere: the other services read the
status (a cached pull from auth-service, like notification-service's Slack workspace; last known value
when auth-service is down), `TenantStatusFilter` closes the access-token window, Kafka consumers park a
suspended tenant's records in a table and replay them on resume (a record cannot be held without
blocking its partition), schedulers skip the tenant, STOMP connections close; (3) offboarding, its own
item: #0-101.

**Progress.** Step 1: PR #456 (V30, `TenantLifecycleService`, `TenantAccessService`,
`TenantStatusFilter`; see the README "Tenant suspension" control). Decided in the review of step 1: a
`SECURITY` suspension must be `FULL` (400 in `TenantLifecycleService`, CHECK
`chk_tenants_security_suspension_full` in V30), since read-only keeps an intruder's sessions and the
account-security writes it allows (others' MFA, deactivation). In step 1 already: a read-only
tenant's alerts are paused, not refused: introspection answers `paused:true` and ingestion-service
503 + `Retry-After` (Alertmanager retries 5xx, drops 4xx), as a successful answer so its circuit
breaker is not tripped for every tenant; a full suspension's alerts got 401 (403 `TENANT_SUSPENDED` since
2a). Left for step 2 (or a
foreign key): a tenant with users but no `tenants` row has full access and cannot be suspended — none
should exist; it is counted, alerted (`PlatformTenantStatusRowMissing`) and logged once (closed by the foreign keys
in the last part, below). Also left for
step 2 (found in the review of step 1; all done in 2a, below): a key ingestion-service cached in the minute before a full
suspension still files alerts for the rest of that minute; a fully suspended tenant's key is answered
like a revoked one, so its sender's retries count against the IP's failed-authentication limit (a
shared NAT could throttle others); a sign-in refused for suspension is neither audited nor counted;
`findByIdForUpdate` (suspend, resume) waits without a `lock_timeout` (sign-ins are bounded at 3 s); the other services will need a short-lived cache
of the status (5-10 s), not a lookup per request; a short cache (5-10 s) of ingestion's paused
introspection answers, so a sender that ignores Retry-After cannot turn every alert into an
auth-service call; a sign-in that loses a deadlock to a suspension's session cleanup gets a 500,
not 503 + Retry-After.

**Step 2, decided (2026-10-05).** Split in two PRs. A decision of step 1 changed: Kafka consumers do **not**
park and replay a suspended tenant's records. notification-, escalation- and postmortem-service's consumers
only write a row (a queue entry, an escalation task, a GENERATING postmortem); their schedulers do the
outbound work. So 2b pauses the schedulers instead: a per-service `paused_tenants` table, excluded in the
schedulers' SQL (not in Java, where a suspended tenant's rows would fill every batch and starve the others),
in both modes, with delivery and escalation deadlines not running while paused; intake goes on, so the state
is consistent on resume. `AuditEventConsumer` is never paused. When auth-service cannot answer: the last known
status however old (static stability; the first version gave FULL an hour after the last answer, changed in the
review of 2a because that abandoned a known suspension, for good when the service token is rejected), FULL only
for a tenant never answered for (fail-open), counted and alerted; a rejected token is its own critical alert.

**Step 2a: PR #457.** Every other service reads the status (`AuthServiceTenantStatusProvider` in `shared`,
`GET /api/v1/internal/tenant-status`, cache 10 s, one call per tenant at a time, last known status during an
outage, FULL only for a tenant never answered for, alerts `TenantStatusLookupFailing` /
`TenantStatusLookupRejected`, CI check that every service sets `auth-service.base-url`), so `TenantStatusFilter` closes the access-token window in all of them; STOMP
`CONNECT` refused and open sessions closed (incident-service, `TenantWebSocketSessions`). The step-1 review
items: a full suspension's keys answer `suspended:true` → 403 in ingestion-service, not counted by the IP
limiter, and the 60 s positive cache window is closed by the status filter (ingestion's read-only refusal is
503 + Retry-After there, `tenant-status.read-only.retry-after`); paused and suspended introspection answers
are cached 5 s; a sign-in refused for suspension is audited (`USER_SIGN_IN_REFUSED_TENANT_SUSPENDED`, after the
rollback) and counted, at most a few times per user per window (then 429, no event: a refusal rolls back,
so its invite or reset token could be replayed without end);
suspend/resume wait at most 5 s for a lock; a lost lock (deadlock, timeout) in
auth-service is 503 + Retry-After, not 500. Still open from step 1 then: the missing `tenants` row (closed below).

**Step 2b: PR #458.** Decided (2026-10-06) after a `/research`: a table per service
(`<service>_paused_tenants`: notification V10, escalation V9, postmortem V7) rather than a set held in memory (lost on
restart, different per replica, and escalation-service needs when the pause began) or a status on every work row
(new states in three state machines, and the consumers' cancel paths would have to know them). `PausedTenantsSync`
(`shared`, ShedLock, every 10 s) asks auth-service only about the tenants with work waiting plus the paused ones,
with the existing tenant-scoped endpoint (no tenant-less list, so no second purpose token), and changes a tenant only
on auth-service's own answer (`TenantStatusProvider.confirmedStateOf`): an outage right after a restart resumes no
one, and a tenant never answered for is not paused (fail-open, as in 2a). The schedulers' queries leave the paused
tenants out (`NOT EXISTS`, native, the table a constant checked at startup against `tenant-pause.table`);
`TenantWorkGuard` holds a row back (`accessOf`, not the cache-only `knownAccessOf`: found by E2E, that answers FULL
for an uncached tenant, so after a restart a suspended tenant's oldest rows went out in the first cycle) until the
sync pauses the tenant. The pause is measured from the suspension's own time: auth-service's answer now carries
`since` (`suspended_at`, kept across a change of mode), and `paused_at` takes it (decided in the second review: first
a fixed 20 s allowance before the sync's own time, which a late sync, a used-up budget or a failing run outran, and a
task held by the guard meanwhile still escalated at once on resumption; the time of the event, recorded by its
owner, as Jira's SLA clocks and Kubernetes' `lastTransitionTime` do, not the time it was noticed; one database, one
clock). Resumption is one transaction with the service's hook: escalation-service moves every PENDING timer on by the
pause (`now - GREATEST(suspended_at, timer start)`; a task already due at the suspension is left as it is; `version`
bumped), notification-service restarts the routing lookups' retry windows (#0-19); the end of the pause is when the
sync sees the resumption, which only ever lengthens a timer. Notifications are all sent on resumption, none dropped
for age (the tenant's alerts were refused at intake meanwhile). Candidates (paused, or with waiting work) are asked
in tenant id order, each run continuing after where the last one's budget ran out (third review: paused tenants used
to go first, and enough of them in an auth-service outage starved new suspensions). escalation- and postmortem-service got
processing budgets like notification-service's (review: a batch of slow status lookups during an auth-service outage
could outlive a ShedLock), and each scheduler looks up its batch's tenants together before the loop, 8 at a time
(`TenantWorkGuard.prefetch`; fourth review: one after another, 60 tenants at ~3 s in an outage delayed the last
ones' escalations by a cycle or more), its budget started before the prefetch so the prefetch cannot eat the
margin below the lock (fifth review). A prefetched answer lasts one status TTL; a longer loop asks row by row
again, accepted (the sync has paused a suspended tenant by then). A timer is never moved earlier (`GREATEST(interval '0', ...)`). The Slack ACK path (no message carries the button since #0-21; #0-35 brings it back)
reaches incident-service with notification-service's token, past the status filter, so `SlackActionService` now
refuses a suspended tenant's acknowledgement itself, counted (`slack.ack.refused`), fail-open like the filter. Alerts
`TenantPauseSyncFailing` and `TenantPauseSyncStalled` (no instance completed a run in 5 minutes). Scale limits (a lookup per tenant per sync in each service, candidate and backlog scans
without `tenant_id` in the indexes): #0-102.

**Last part: the missing `tenants` row, PR #459.** A tenant id with data but no `tenants` row had full access and
could not be suspended (counted, alert `PlatformTenantStatusRowMissing`). Decided (2026-10-06): a foreign key to
`tenants`, `ON DELETE RESTRICT`, on every auth-service table holding a tenant's data (users, user_roles, teams,
tenant_settings, auth_tokens, api_keys, integrations, slack_workspaces, mfa_recovery_requests). V31 first records
any id without a row as ACTIVE, named after itself (as V21 did for users: refusing would stop a deployment over a
gap the row closes), and adds the keys `NOT VALID`; V32 validates them, under a lock that lets reads and writes go
on. Not on `auth_email_outbox` / `auth_audit_outbox`: queues of work about covered rows, whose events must never be
refused for their tenant. Not in the other six services: a foreign key across services would tie their schemas to
auth-service's (#0-85). A tenants row is never deleted (offboarding ends in the `OFFBOARDED` tombstone, #0-101).
The missing-row answer stays FULL with its counter and alert, now a tripwire: auth-service has no data for such a
tenant, so only a token minted outside its sign-in (`/dev/token`; elsewhere, something holding the JWT secret)
can name one. Provisioning's check of users without a row (`UserRepository.existsAnyByTenantId`, found in the
review of #0-80) went, unreachable; the operator tenant's bootstrap now stops its run (FAILED, gauge unchanged)
when it cannot record the row, since inviting the admin without it could only fail on the foreign key. A
structural test (`AuthRepositoryIntegrationTest.everyTenantIdColumnReferencesTenants`) fails on a table with a
`tenant_id` and no validated foreign key; `TenantForeignKeysMigrationTest` runs V31/V32 on orphaned data.
Added in review: a write refused by one of these keys is 403, not the shared catch-all's 500
(`UnrecordedTenantHandler`, only the nine constraint names, which a test compares with the schema); V31 names each
id it adopts in a WARNING (a reserved one flagged: adopted rather than refused, as refusing would stop the
deployment and being reserved grants nothing), and waits at most 5 s for a lock (`lock_timeout`; its nine ALTERs
hold their locks until it commits); V32's header says what to do if validation ever fails; the migration test seeds
an orphan in each of the nine tables and checks the window between V31 and V32. Second review: V31 locks the ten
tables before its backfill, so no orphan can be written between the backfill and the constraints; the 403 body is
the shared handler's own wording, not a hint that the tenant is unknown; a write failing only at commit is shown to
reach the handler as well.

---

### 0-85. A tenant id with data in other services but no user can be provisioned

**Type:** design · **Priority:** Low · **Status:** Open (found in the review of #0-80; needs verification)

**Problem.** `POST /api/v1/platform/tenants` (#0-80) refuses an id only if auth-service's `tenants` table
has it, and V21 filled that table from auth-service's `users`. The other six services keep `tenant_id` as a
plain string on their own rows. If any of them holds rows under an id that never had data in auth-service,
an operator can provision that id, and its new admin sees those rows. Possible sources, to be verified:
- incident-service's `/dev/token` (dev profile only) mints a token for any tenant id, so a dev or test
  database may have incidents, on-call schedules or postmortems under such ids. Its default is
  `test-tenant`, which the README's end-to-end test uses;
- a hard-deleted tenant's users (only archive/anonymize exist today, so possibly none);
- alerts ingested under an Integration API key always belong to a tenant with a user, so probably not.

Only an operator can do this, so it is an accident or a rogue-operator risk, not an outside attack. Today
`docs/tenant-provisioning.md` tells the operator to check the id first. An id with any data in auth-service,
archived users included, is already refused: since #0-82 every auth-service table holding a tenant's data has a
foreign key to `tenants` (V31), so such an id has its row, and provisioning's insert refuses it (that replaced a
check of the users, `UserRepository.existsAnyByTenantId`).

**Approach.** First verify: list the tenant ids each service's tables hold and which of them auth-service
has no record of, on every deployed database. Then either:
- **Document and close.** If no production database can hold such ids, keep the guide's check.
- **Refuse at provisioning.** auth-service asks every service whether the id is in use, through an internal
  service-token endpoint per service; many calls, and a new cross-service dependency for a rare action.
- **Record unknown ids.** A one-off backfill of `tenants` from every service's distinct `tenant_id`,
  which blocks those ids for good.

---

### 0-86. Integration tests load the web-slice test configuration

**Type:** tech-debt · **Priority:** Low · **Status:** Open (found while implementing #0-83)

**Problem.** `AuthServiceApplication` declares its own `@ComponentScan("com.incidentplatform.auth",
"com.incidentplatform.shared")`. That replaces the scan `@SpringBootApplication` would do, including
its `TypeExcludeFilter`, so a `@SpringBootTest` (e.g. `AuthRepositoryIntegrationTest`) scans the test
sources too: `AuthApiTestApplication` (itself a `@SpringBootApplication`) and, through it,
`AuthWebMvcTestConfig`. The integration context therefore gets the web slice's no-op
`TokenRevocationChecker` (`@Primary`), and a test bean named like a production bean silently replaces
it. #0-83 hit this: a mock `MfaSessionStatusService` replaced the real one in the integration tests.
Worked around by giving the slice's beans their own names and `@Fallback`.

**Approach.** Drop the explicit `@ComponentScan` (`shared` is already scanned through
`scanBasePackages` or its auto-configuration, to be checked), or add Boot's exclude filters
(`TypeExcludeFilter`, `AutoConfigurationExcludeFilter`) and move `AuthApiTestApplication` out of the
scanned package. Then check that no integration test relied on the leaked beans. Other services
with an explicit `@ComponentScan` may have the same issue.

---

### 0-87. Operator MFA enrolment is not bound to the invite

**Type:** design · **Priority:** Low · **Status:** Open (option deferred in #0-83)

**Problem.** Enabling MFA needs only a password-authenticated session. #0-83 answered the risk that a
password thief enrols their own factor with an email on every MFA change and a 24 h grace period
before the platform API accepts a new factor. That leaves a window: an owner who does not read the
email within the grace period, or whose mailbox is also compromised, does not stop it.

**Option.** For the `platform-operator` tenant, make enrolling the TOTP part of accepting the invite
(the invite link is itself proof from outside the password channel), and refuse `/mfa/setup` from a
password-only session of an operator. Existing operator accounts without MFA would then be
re-invited. Stronger, with no window, but it changes the invite flow and the operator bootstrap.

**Also possible, smaller.** A content-free alert to the platform channel whenever a
platform-operator account enables MFA (the pattern of the operator's undeliverable-notification
alert), so a second person sees it even if the owner's mailbox does not (suggested in the review of
#0-83).

**Or, with several operators.** A new operator factor counts only once a second operator admin
approves it (four eyes), so a thief who holds both the password and the mailbox still cannot pass
the platform API alone. Needs at least two operator admins, and a recovery path for a deployment
with one (suggested in the review of #0-83).

**When.** If the platform API gains more powerful actions (suspension, #0-82, has landed and is
alerted on every change; offboarding, #0-101, would be the next), or a deployment has several
operators.

---

### 0-94. Logs are plain text, with no structure, escaping or collection

**Type:** design · **Priority:** Medium · **Status:** Open — step 1 done (PR #463), step 2 done (PR #464), step 3 open (raised in the analysis of #0-91/#0-92)

**Problem (before step 1).** Every service logged plain text: six shared the pattern
`%d [%X{tenantId}] [%X{requestId}] [%X{userId}] %-5level %logger - %msg%n`, auth-service uses Spring Boot's
default. No `logback*.xml`, no encoder escapes anything. So:
- **Log injection.** Any value from outside that reaches a log line (a Kafka header such as `X-Event-Type`, a
  payload field, an HTTP header, an exception message quoting a library's input) can carry CR/LF and forge or
  split lines. #0-92 closes it for the tenant id only (a strict format checked at every entry); other fields
  rely on each call site remembering not to log them raw (`notification` `IncidentEventConsumer` does,
  others do not).
- **No machine-readable fields.** The MDC keys (tenant, request, user, Kafka message id) are only positions in a
  string, so filtering one tenant's or one request's lines, or correlating them across services, needs regexes.
- **No collection.** Neither docker-compose nor `k8s/` runs a log collector or store (no Loki, Promtail, Fluent
  Bit, Alloy, ELK): logs live in each container's stdout until it is replaced. An incident on the platform
  itself is investigated container by container, and nothing alerts on log content.

**How production systems handle it.** Structured (JSON) logs to stdout, one object per line, the encoder
escaping every value; a common field schema (ECS or similar: timestamp, level, logger, message, trace and span
ids, service, plus the platform's own tenant and request ids); a collector shipping them to a store queried by
field, with retention and access control (logs carry tenant data); trace ids from OpenTelemetry linking logs,
metrics and traces. Spring Boot 3.4+ (this project: 3.5) has built-in structured logging
(`logging.structured.format.console=ecs|logstash|gelf`), so no extra encoder dependency is needed.

**To analyse as a whole, before any change:**
- format and field schema (ECS vs Logstash vs GELF), MDC keys mapped to fields, the same in all seven services;
- local readability: JSON for containers, plain text for `spring-boot:run` (profile-dependent) or one format;
- collection and storage: which stack (Loki + Alloy is the usual fit next to the existing Prometheus/Grafana/
  Alertmanager), in compose and k8s, retention, who may read logs (they hold tenant content and PII: #0-48);
- tracing: OpenTelemetry/Micrometer Tracing for trace ids in logs (none today);
- what must never be logged (secrets, tokens, raw payloads) and how that is checked;
- CI: the smoke test and anything that greps log output.

**Decided (2026-10-08, analysis of the whole item).** Three steps, one PR each:
1. **JSON in ECS for every service — done.**
   - *Format.* Spring Boot's own structured logging, ECS on the console and in a log file if one is configured,
     set for all seven services by `shared`'s `StructuredLoggingDefaults` (an `EnvironmentPostProcessor`, added
     after the config data). ECS rather than Logstash or GELF: a standard schema with room for
     `trace.id`/`span.id` (step 3), read as is by Loki, Elastic and Grafana. ECS nests its own fields (`log.level`,
     `service.name`, `error.stack_trace`) and puts every MDC key at the top level; the set reaching the log is
     pinned (`tenantId`, `requestId`, `userId`, `kafkaMessageId`), the interceptor's `_kafkaStartNanos` excluded.
     The encoder escapes every value, so the log injection above is closed for every field, not only the
     tenant's. The six services' copies of the text pattern are gone; auth-service, on Boot's default before, is
     like the others (its break-glass command too).
   - *Enforcement.* A default can be overridden from anywhere, so `StructuredLoggingGuard` refuses to start a
     service whose effective console or file format is not `ecs`, that names a Logback file (`logging.config`,
     `logback.configurationFile`) or has one on the classpath (`logback*.xml`/`.groovy`, test ones included, in
     any jar), or whose JSON object is reshaped (`logging.structured.json.*` beyond the platform's own `exclude`:
     a renamed or excluded `tenantId` would vanish from every line), whatever set it. The one exception is `platform.logging.plain-text: true`, which a developer puts
     in the gitignored `application-local.yml` with an empty console format to get plain text in the old MDC
     pattern (the default's `logging.pattern.console`); set anywhere else it still works, and is logged as a
     warning at every start. CI's sixth structural rule (`check-structured-logging.sh`, with its own cases) fails
     on that switch in the usual spellings, and on a Logback file, in tracked files; any other way of switching
     JSON off already stops the service in the smoke test. This replaced, in review, a first CI check of format
     keys in committed configuration, which a `config/` copy, YAML flow style, quoted keys,
     `SPRING_APPLICATION_JSON` or a ConfigMap outside the repository got around.
   - *What a line must never carry* (tokens, secrets, keys, raw payloads) is a rule in CLAUDE.md, not a check: the
     encoder makes any value safe to print, not safe to disclose. The manual guards (`AuditText`, `printable()`
     in `SlackApiClient`) stay where a value goes to a tenant-readable record (audit, `notification_log`).
   - *Tests.* `StructuredLoggingDefaultsTest` runs a real `SpringApplication`: ECS fields and the MDC key set, a
     CR/LF in the message, an argument, an MDC value and an exception kept inside one line, a log file in ECS,
     a start refused for a format set in `application.yml`, by an argument, through `SPRING_APPLICATION_JSON` and
     by `logging.config`, plain text with the switch. `StructuredLoggingGuardTest` takes each refusal on its own
     (either output, case and padding, `logback.configurationFile`, every Logback file, the classpath lookup, a
     reshaped JSON object, the switch honoured only when true and logged, both environment-variable spellings). A
     packaged service jar logs JSON from its first line (checked on oncall-service).
   - *Not covered:* a deployment outside this repository setting the switch on purpose (whoever can, can also
     change the image; it is logged), and the content of an exception's message (escaped, not filtered).
2. **Collection — done (decided 2026-10-09).** docker-compose only: `docker-socket-proxy` → Alloy → Loki, read in
   Grafana (Explore → Loki), every new image (and Grafana) pinned by tag. k8s has no monitoring at all (no Prometheus, Grafana or
   Alertmanager), so logs there come with the rest of it, in #0-106, not alone.
   - *Collection.* Alloy (`docker/alloy/config.alloy`) discovers the containers named `incident-*` (the filter does
     not depend on the compose project name) and reads their stdout from the Docker API. Labels stay few and
     bounded: `service` (the compose service), `container`, `level` (from ECS's `log.level`). `tenantId`,
     `requestId`, `userId` and `kafkaMessageId` are **structured metadata** of each line, queryable
     (`| tenantId="acme"`) without a stream per value: the cardinality reason the `tenant` tag left
     `kafka.records.received` in #0-92. Only the services (compose names ending in `-service`) are parsed
     (`stage.match`): another container's JSON (kafka-ui, a library image) sets no level and no identifiers, as its
     values are not the platform's and as labels could add streams without bound. Lines that are not JSON
     (Postgres, Kafka, Redis) are kept unparsed. A service's level outside TRACE..ERROR (JSON of its own on its
     raw stdout) becomes `OTHER`, so `level` has at most six values. The other containers' raw lines are kept too,
     on purpose, under the same 15 days: an incident on the platform is often in Postgres, Kafka or the proxy, not
     only in the services. They may carry tenant content (a Postgres error quoting a row, a Kafka UI request), so
     they get the same access rules as the services' lines, nothing looser. A
     service run with `spring-boot:run` is no container and is not collected, nor is auth-service's break-glass
     command (`docker compose run --rm`, docs/tenant-provisioning.md: its container is not named `incident-*` and is
     removed when it ends); its audit events are recorded as usual, its log lines go only to the operator's
     terminal. Positions are kept on a volume, so a
     restarted Alloy does not resend everything.
   - *Docker API.* Through `tecnativa/docker-socket-proxy`, never the socket: the socket is root on the host,
     and `:ro` protects the file, not the API. The proxy answers GET on `/containers` and `/networks` (Alloy's
     discovery lists both) and refuses the rest, every POST (exec, stop, create) included, and is on an
     `internal` network with Alloy alone. Rejected: the socket mounted in Alloy (the usual tutorial; a compromised
     collector would own the host) and Docker's Loki logging driver (a plugin on every developer's host, and a
     `logging:` block in every service). *Not covered:* the proxy cannot narrow `/containers` further, so whoever
     controls Alloy can still read every container's environment (secrets included, Grafana's password too, through
     inspect) and files (archive, export); and `attach/ws`, a GET that upgrades to a websocket able to feed a
     container's stdin (checked: 101), harmless here as no container keeps stdin open. Much less than root on the
     host, but not nothing. The proxy is pinned by
     digest (the one container with the socket) and hardened (read-only root with a tmpfs for its rendered config,
     every capability dropped, `no-new-privileges`, 128 MiB), and the smoke test fails if a write gets through it or its
     network is not internal. The way
     past it, should it matter, is a collector with no Docker API at all: `loki.source.file` over Docker's
     `/var/lib/docker/containers` mounted read-only (works on a Linux host, not inside Docker Desktop's VM, so not
     for the developers' machines) or a logging driver; kept for k8s, where Alloy reads the kubelet's files (#0-106).
   - *Retention and access.* 15 days (`retention_period: 360h`, compactor `retention_enabled`; without it Loki
     deletes nothing), the same as Prometheus: one window for investigating an incident on the platform from both,
     and no tenant content kept longer. Lines older than 7 days are refused, and so is a line about an hour behind its stream's newest (Loki's
     out-of-order window): an Alloy that lost its positions re-reads old Docker logs, those lines are dropped and
     `LogsDropped` fires once (seen in testing). Loki has `auth_enabled: false` (one
     org): whoever reaches it can read, push and query every line. So it publishes no port, its delete API is off
     (`deletion_mode: disabled`; retention does not need it), and it is on the internal `logs` network alone with
     Alloy, Grafana and Prometheus; Alloy, whose HTTP API shows its config, is on `logs` and `docker-api` only. The
     services, kafka-ui, pgAdmin and the rest are on `default` and cannot reach either (a first version had both on
     `default`, where any compromised container could read every tenant's logs and erase lines). Grafana is its
     only reader, so who may read the logs is who may log in to Grafana; it is on `logs` (Loki and Prometheus are
     there) and on `grafana-ui`, a network of its own only to publish its port, not on `default`: from there a
     compromised container could log in (an old volume still has `admin`) and query Loki through Grafana's
     datasource proxy, around the `logs` network (found in the third review). The smoke test checks that none of
     the proxy, Loki, Alloy and Grafana is on `default` in the compose file, that Loki and Alloy (and Grafana when
     it runs) do not answer from the services' network (only a curl DNS, connect or timeout error counts, never a
     docker failure) and the proxy is on internal networks only, and that Loki and Alloy answer on `logs`; it also pins exactly who is on
     `logs`, `docker-api` and `grafana-ui`, and that the first two are internal, so adding a service or a UI
     image to one of them fails CI.
     Grafana was `admin`/`admin` on every interface (#0-72); since it reads tenant content it is published
     on `127.0.0.1` only, its admin password is `GRAFANA_ADMIN_PASSWORD` with no default (empty in
     `.env.example`, so compose refuses to run until a developer sets one; CI sets a dummy) and its image is
     pinned (`13.2.3`, was `latest`). The password applies to a new `grafana_data` volume only; an older one keeps
     `admin` until reset (README Step 5), and `make dev-up` warns while Grafana still accepts `admin`/`admin`, or when the password is under 16 characters (`docker/grafana-password-check.sh`, reading it as compose does: BOM, `export`, quotes, inline comments, and a `$` outside single quotes said to be interpolated rather than measured; its 28 cases, with a stub curl, run in CI's build job, `.github/scripts/test-grafana-password-check.sh`).
     Grafana sends nothing it does not need (usage reporting, update and plugin checks, news feed off) and installs
     no plugin from its UI, as `grafana-ui` gives it a route out. Memory is capped (Loki 1 GiB, Alloy 512 MiB, about
     150 and 80 MiB in use when measured), so a heavy query cannot starve the services on a laptop or CI runner;
     Loki also runs with `GOMEMLIMIT` and bounds each query (no longer than the retention, 8 parallel parts and 16 for the TSDB index (128 by
     default), 500 series, 2000 lines, 2 GB read, 1 minute (the HTTP server's timeouts, 30 s by default, raised to 70 s so they do not cut it first), 4 at once; the index-stats and volume caches off), and its ingestion rate is written out (4 MB/s, bursts of 8), which caps the rate, not the
     disk. Its WAL replay is capped at 400 MB (the default, 4 GB, is above the container's limit: after one OOM
     kill the replay would pass it again and Loki would restart for ever). Alloy has `GOMEMLIMIT` too and sends no
     usage report. Loki and Alloy are hardened like the proxy (read-only root, no capabilities, no privilege
     gain), Alloy running as its image's `alloy` user (473) rather than root; a line over 64 KB is cut (4 queries x 2000 lines x 256 KB, Loki's own size, would not fit 1 GiB), not
     refused (a refusal would count as a drop). `LogPipelineRestarting` (two restarts in 30 minutes) covers the
     restart loop `LogPipelineDown` cannot see, as every `up=1` between restarts resets its 5 minutes. Alloy's
     and Loki's own lines are collected too, on purpose (they are where a pipeline problem shows); while Loki is
     down, Alloy's errors about it wait in its queue like any other line. Grafana 13 downloads app plugins from grafana.com at start (seen: advisor, the Drilldown apps,
     Pyroscope); `GF_PLUGINS_PREINSTALL_DISABLED` stops it, as none is needed where every tenant's logs can be
     read. No access per tenant: the logs are the platform's, read by its
     operators. One tenant's lines cannot be deleted before the retention is up: #0-101. Usage reporting to
     Grafana Labs is off. Docker keeps at most 3 x 10 MB of each container's json-file log (`x-logging` in
     compose; it grew without bound before), Alloy having shipped it.
   - *Alerts on the pipeline* (`logs` group in `prometheus.rules.yml`, all `high`, to the platform webhook): a
     pipeline that stops says nothing by itself. `LogPipelineDown` (Loki or Alloy unreachable for 5 minutes),
     `LogPipelineRestarting` (two restarts in 30 minutes, at once: a loop keeps `up` mostly 1, so
     `LogPipelineDown` stays quiet; a first version, three in 15 minutes, missed a slow loop of one OOM kill
     every 6-10 minutes), `LogsNotFlowing` (Alloy sent no line for 15 minutes while up and discovering: a
     renamed container, lost targets; a live stack is never silent that long, as the proxy alone logs each
     discovery call; 112 lines a minute was the fewest in 3 idle hours, measured),
     `LogPipelineNotScraped` (no `up` series for either for 5 minutes: a scrape job removed or renamed, which
     `LogPipelineDown` cannot see; added in review),
     `LogDiscoveryFailing` (Alloy's Docker discovery kept failing for 5 minutes: a 2-minute window, as a 10-minute
     one let a single failed refresh fire it, e.g. the proxy is
     down: Alloy's own component health stays "healthy" then, found by stopping the proxy) and `LogsDropped` (any
     line Alloy gave up on, per reason; lost for good; late by design, as Alloy retries a batch for about 8.5
     minutes first, its backoff written out in `config.alloy`; the counter exists at 0 for every reason from
     Alloy's start, so the first drop counts). Rotation bounds what an Alloy outage can catch up on: a chatty
     service fills its 3 x 10 MB in minutes, and lines rotated away meanwhile are lost uncounted. promtool tests for each (sustained, blip, idle, other
     job/mechanism), and `routes test` lines in CI. Alerts on what the lines say: #0-107.
   - *CI.* `validate-monitoring-config` runs `loki -verify-config`, `alloy validate` and `alloy fmt` (canonical
     form) on the images it reads from the compose file, so the tags cannot drift apart. The smoke test starts the pipeline with the services and runs
     `.github/scripts/test-log-collection.sh`: every service's lines are in Loki with a `level`; a probe
     container's JSON line has its level as a label and `tenantId`/`requestId` as structured metadata, never
     labels; a line that is not JSON arrives unparsed; the proxy, asked from Alloy's network, answers Alloy's
     reads and refuses writes (aimed at a container that does not exist, so a misconfigured proxy cannot make the
     check stop a real one) and other reads; every network of the proxy, Loki and Alloy is internal, and neither
     Loki nor Alloy answers from the services' network; a non-service container's JSON is not parsed. One
     deadline for the whole run (4 minutes; one per check could add up past the job's timeout), and every request
     to Loki has its own 10 s limit. Breaking the `service` relabel, the level path, the tenant mapping, the
     services-only match, `POST=0`, `internal` or Loki's network makes it fail (checked). `test-postgres-roles.sh`
     checks compose refuses to run without `GRAFANA_ADMIN_PASSWORD`, as it does for the database passwords.
3. **Tracing — open.** Micrometer Tracing with OpenTelemetry: `trace.id`/`span.id` in every line, `traceparent`
   across HTTP and Kafka (the header half of #0-95), so one alert can be followed from ingestion to its notifications.

### 0-95. Kafka messages have no envelope (correlation id, schema version, producer)

**Type:** design · **Priority:** Low · **Status:** Open (a `TODO` in `AlertKafkaProducer` that cited no backlog
item, found in the review of #0-91/#0-92)

**Problem.** A record is its domain payload plus headers (`X-Tenant-Id`, a copy of the payload's tenant since
#0-91; `X-Event-Type` on `incidents.lifecycle`). Nothing carries a correlation id from the alert through the
incident to its notifications, a schema version, or the producing service and time, so tracing one alert
across services, replaying with context or evolving a payload shape has nothing to go on.

**Options.** A typed `KafkaEnvelope<T>` in `shared` (routing metadata next to the payload; every producer and
consumer changes, a deserialization strategy for both shapes during a migration), or standard headers
(W3C `traceparent` via Micrometer Tracing / OpenTelemetry, a schema-version header) with payloads unchanged.
The tenant stays the payload's either way (#0-92). Justified together with tracing (#0-94) or when producers
and consumers multiply; not before.

### 0-97. Kafka consumers on `DefaultErrorHandler`, and a dead-letter replay

**Type:** design · **Priority:** Low · **Status:** Open (option deferred in #0-96)

**Problem.** Since #0-96 every consumer decides each record's fate itself, in `MANUAL_IMMEDIATE` mode, through
`DeadLetterPublisher` (`deadLetterThenAcknowledge`, `redeliverLater`, `redeliverIfTransientElseDeadLetter`) and
`KafkaFailures`. That is correct, but six consumers repeat the same catch blocks, and nothing stops a new one from
acknowledging too early or returning without acknowledging, which skips the record (`DeadLetterPublisherKafkaIntegrationTest`).
A transient failure is retried every 5 s, with no backoff, until its redelivery deadline (30 min, in memory per
instance, `RecordRedeliveries`). And nothing reads the dead-letter topics: a record put there is kept, not processed.

**Options.**
- Spring Kafka's `DefaultErrorHandler` with a recoverer built on `DeadLetterPublisher` (the tenant-less record,
  the `AuditText` reason and the `X-Tenant-Unresolved` marker of #0-92 stay there): listeners throw, the
  container retries with an exponential backoff, a non-retryable exception (or `KafkaFailures` says not
  transient) goes to the recoverer, which waits for the copy before the offset is committed. One place for all
  consumers; `AckMode.RECORD`, no `Acknowledgment` in the listeners; an exponential backoff instead of a fixed
  5 s, and the deadline expressed as the handler's (`ExponentialBackOffWithMaxRetries` or a max elapsed time).
- A replay tool for the dead-letter topics: it must resolve the tenant again from the original payload (README
  "Infrastructure Hardening": the topics' content is untrusted until Kafka ACLs, #0-66), and deduplicate copies
  stored twice by `sourceTopic`, `sourcePartition` and `sourceOffset` (at-least-once since #0-96).

**When.** When consumers multiply, or when the first dead-lettered record has to be processed.

---

### 0-98. MFA recovery verifies the person by procedure only

**Type:** design · **Priority:** Low · **Status:** Open (left out of #0-90 on purpose)

**Problem.** The operator-assisted MFA recovery of a customer tenant's only admin (#0-90) records how the
operator verified the person (`MfaVerificationMethod` and a note), but the platform checks none of it. It holds
no contact of the tenant independent of the admin's own account (`tenants.first_admin_email` is usually the very
mailbox that may be compromised), and a `DNS_TXT_RECORD` verification is the operator's own lookup. The waiting
period and the notice to the account are the platform's safeguards; the verification itself rests on the
operator following docs/tenant-provisioning.md.

**Approach.** Record verified contacts per tenant at onboarding (a phone number, a billing or security contact,
the customer's domain), editable only through the platform API with the same rules; let the recovery request name
which contact was used, and for a domain have auth-service look the TXT record up itself (a value it generated,
on `_incident-platform.<domain>`), refusing the request until it matches.

**When.** When customers are onboarded under contract (a contact exists anyway), or when a recovery request is
disputed.

---

### 0-99. Public token endpoints have no request limit

**Type:** security · **Priority:** Low · **Status:** Open (found in the security review of #0-90)

**Problem.** auth-service's public endpoints that take an emailed token (`POST /api/v1/auth/reset-password`,
`/accept-invite`, and since #0-90 `/mfa-recovery/cancel`) have no request limit. A token is 32 random bytes,
stored as a hash and single-use, so guessing one is out of reach; an unauthenticated flood of invalid tokens still
costs a database lookup and a WARN line each. Login has its own brute-force protection; these do not.

**Approach.** One limiter for the public token endpoints together, not one per endpoint: per client address (the
Ingress's forwarded address, trusted only from the Ingress) and in total, in the Redis bucket4j setup ingestion
uses, fail-open like it (a refused recovery or password reset is worse than a flood that Redis's absence lets
through); 429 with Retry-After; the WARN for an invalid token sampled or counted instead of logged per request.

**When.** Before the platform is exposed to the internet without a WAF or rate limit at the edge.

---

### 0-100. A locally built jar contains the developer's `application-local.yml`

**Type:** tech-debt · **Priority:** Low · **Status:** Open (found while closing #0-81)

**Problem.** Each service reads a developer's `application-local.yml` from `src/main/resources`
(`make run-*`, `spring-boot:run -Dspring-boot.run.profiles=local`). It is gitignored and
`.dockerignore`d, so it is never committed and never reaches an image or a CI-built jar. But
`./mvnw package` on a developer's machine puts it into `target/*.jar` as
`BOOT-INF/classes/application-local.yml`, with that developer's real local secrets (JWT secret,
MFA and Slack encryption keys, database password, Gemini API key). A jar copied off the machine,
or run elsewhere with the `local` profile, carries them.

**Approach.** Exclude `application-local.*` from the jar in `service-parent` (`maven-jar-plugin`
`excludes`), so `spring-boot:run`, which reads `target/classes`, keeps working; a test (or a CI
step on a freshly packaged jar) that the jar has no `application-local`. Alternatively move the
local file out of the source tree (`./config/application-local.yml` next to the module, which
Spring Boot also loads from the working directory), which needs the README "Step 2" templates and
the Makefile changed.

**When.** Whenever `service-parent` is next touched, or before anyone hands a locally built jar on.

---

### 0-101. Offboard a tenant

**Type:** design · **Priority:** Medium · **Status:** Open (split out of #0-82)

**Problem.** A tenant can be suspended (#0-82) but not ended: a customer who leaves keeps its data in all
seven services (incidents, audit, notifications, on-call, postmortems, users, teams, keys, Slack
workspace) and in Redis, with no export and no deletion short of editing the databases.

**Approach.** The pattern of Azure's tenant life cycle and AWS SaaS Lens: on request, suspend the tenant
in full and move it to `OFFBOARDING`; export its data (a signed link to the account owner for a limited
time); a grace period in which it can still be resumed; then an orchestrated, resumable, idempotent
deletion in every service (an internal per-service "delete tenant" step, the orchestrator in auth-service
recording each one), Redis included; then `OFFBOARDED`, a tombstone that keeps the id taken (ties into
#0-85). Decide first: the export format, the grace period, the audit trail's retention (often longer than
the customer), backups, and whether the destructive step needs a second operator (#0-87). Run it from an
approval, keep a tested runbook. The platform's own logs too (since #0-94 step 2): Loki keeps every tenant's
lines for 15 days in one org with no tenant label, so one tenant's cannot be deleted sooner; either the
offboarding waits out the retention (state it in the export/erasure answer), or it uses Loki's delete API with
a `tenantId` structured-metadata filter (needs `deletion_mode` set and the compactor's delete requests; to be
tried).

**When.** Before the first customer leaves, or the first erasure request for a whole organisation.

### 0-102. The pause of suspended tenants' background work at scale

**Type:** performance · **Priority:** Low · **Status:** Open (found in the review of #0-82 step 2b)

**Problem.** `PausedTenantsSync` (notification-, escalation-, postmortem-service) asks auth-service about every
tenant with waiting work, one call each, serially, every 10 s, and the sync interval equals the status cache's TTL,
so nearly every call misses the cache: about 3 services x N tenants / 10 s on `/internal/tenant-status`. Its
candidate queries (`SELECT DISTINCT tenant_id ... WHERE status ...`) and the schedulers' `NOT EXISTS` read every
pending row of a paused tenant on each run, as the partial indexes they use do not hold `tenant_id`; a tenant held
with nothing waiting is still asked about every run (it must be, to see its resumption); a tenant held
for weeks with alerts still flowing (notifications are written meanwhile) makes every poll walk its backlog.
`notification_queue` has no `tenant_id` index for the resumption's UPDATE. Fine at today's scale; not at
thousands of tenants with work waiting. A run that runs out of its budget leaves the rest to the next (which goes on
after it), so at that size a suspension takes several runs to reach every service (the timers no longer depend on it:
the pause is measured from `suspended_at`).

**Approach.** Measure first (the provider's cache hit/miss counters, `EXPLAIN ANALYZE` on a seeded database).
Then, as needed: a sync interval at least twice the status cache's TTL (most lookups then hit the cache) or
lookups fanned out a few at a time on virtual threads instead of one after another; a partial index
`(tenant_id) WHERE status = 'PENDING'` per work table (the candidate query becomes an index-only scan); a batch
status endpoint in auth-service (a service token per tenant today: a tenant-less list
would need a second purpose token, CLAUDE.md's deny-by-default rule), or a sync interval above the TTL; `tenant_id`
in the partial pending indexes (`CREATE INDEX CONCURRENTLY`, alone in its migration, with
`spring.flyway.postgresql.transactional-lock: false`).

**When.** Before tenants with work waiting number in the hundreds, or when auth-service's status endpoint shows in
its own latency.

---

### 0-105. The email channel's worst-case send is not checked against the scheduler lock

**Type:** performance · **Priority:** Low · **Status:** Open (found in the second review of #0-103)

**Problem.** `NotificationScheduler` checks its budget between entries, and since #0-103 refuses to start unless
budget + 30 s + each channel's `NotificationChannel.worstCaseSendTime()` fits its 4-minute ShedLock. Only Slack
declares one (about 51 s). Email and SMS return zero, so they share the fixed 30 s margin with the entry's database
writes and the Slack workspace lookup in auth-service. An SMTP server that accepts the connection and then stalls
costs up to `mail.smtp.connectiontimeout` + `timeout` (+ `writetimeout`) per command, 5 s each by default, over
several commands of one session; with Slack, SMTP and auth-service all hanging at once, the entry in flight could
outlive the lock and a second replica send the same notifications. Not new with #0-103 (the margin always assumed
it), but now there is a place to say it.

**Options.** `EmailNotificationChannel.worstCaseSendTime()` derived from the `spring.mail` timeouts and a bound on the
commands of one send, if JavaMail gives one; or a hard deadline on the send (a `TimeLimiter`, or the session
closed after a total timeout), which bounds it whatever the protocol does and is then what it declares. The same
for SMS once it has a real provider.

---

### 0-106. Kubernetes has no observability stack

**Type:** design · **Priority:** Medium · **Status:** Open (found in the analysis of #0-94 step 2)

**Problem.** `k8s/` deploys the seven services, Postgres, Redis and Kafka, and nothing that watches them: no
Prometheus, Alertmanager or Grafana, so none of the alerts in `docker/prometheus.rules.yml` (the operator's email,
the dead man's switch, #0-16) exist in a cluster, and since #0-94 step 2 no log collection either: logs live in
each pod's stdout until it is replaced. Everything in `docker/` (compose) is all the platform has.

**Approach (to analyse).** The usual way is the kube-prometheus-stack / Grafana's Kubernetes monitoring Helm charts,
or the same components as Kustomize manifests: Prometheus (or Alloy as the scraper) with the same rule file, Alertmanager with
the same routes, Loki, and Alloy as a DaemonSet reading `/var/log/pods` (no Docker socket: the kubelet's files, with
RBAC for pod metadata only). Retention and access as decided for compose in #0-94 step 2; a managed backend is the
other option. Touches #0-64 (securityContext) and #0-65 (NetworkPolicy: who may reach Loki).

### 0-107. No alert reads log content

**Type:** design · **Priority:** Low · **Status:** Open (left out of #0-94 step 2 by decision)

**Problem.** Since #0-94 step 2 the platform's logs are in Loki, but nothing reads them for alerts: Loki's ruler is
off (`docker/loki.yml`). Most signals already are metrics with alerts (errors per service, failed deliveries, the
outbox, Kafka redeliveries), so this is for what only a line shows, e.g. `StructuredLoggingGuard`'s WARN that
plain-text logs were switched on in a deployment (#0-94 step 1), or a burst of ERROR lines from a service whose
metrics look healthy. Also a service that stops logging: `LogsNotFlowing` (#0-94 step 2) fires only when Alloy
sends no line at all, and `docker-socket-proxy` logs each of Alloy's discovery calls, so one service, or all seven,
going silent never fires it; a per-service query on the lines (`absent_over_time` per `service`) would.

**Approach (to analyse).** Loki's ruler with LogQL alerting rules sent to the same Alertmanager (rules in the repo,
tested like the Prometheus ones if a tool allows), or recording rules turning a log query into a metric that a
Prometheus rule alerts on. Only for signals no metric gives.

---

### 0-108. Architecture tests (ArchUnit) for the invariants in CLAUDE.md

**Type:** ci · **Priority:** Medium · **Status:** Open
**Autopilot:** not-ready · **Risk:** low · **Complexity:** medium · **Depends on:** —

**Problem.** The invariants in CLAUDE.md ("Invariants: the short version", `.ai/context/architecture.md`)
are enforced only by review. Several have been broken before (#0-47 `= 0L` on `@Version`, #0-83 a bulk
`@Modifying` without `flushAutomatically`, the forked security chains of `d80541f`/`ec4eae4`). With
unattended agents writing code, a rule that only a reviewer checks is a rule a reviewer can miss.

**Approach.** A test module `architecture-tests` (test scope only, depends on every service) with ArchUnit
rules, each pointing to the invariant it guards. Existing violations are frozen
(`FreezingArchRule`, store committed) so the build is green on day one and only new violations fail.
Candidate rules, to be confirmed by the owner before the item is `ready`:
1. Every method annotated `@Scheduled` is also annotated `@SchedulerLock` (ShedLock).
2. No class outside `TenantRecords` / `DeadLetterPublisher` calls `KafkaTemplate.send` directly.
3. A `@Version` field is of type `Long` (and has no initialiser, checked by a source-level test).
4. Every `@Modifying(clearAutomatically = true)` also sets `flushAutomatically = true`.
5. No `@KafkaListener` method is `@Transactional`.
6. No class of one service depends on a package of another service; `shared` depends on no service.
The module and the frozen store are human-owned paths in `.github/CODEOWNERS`, and the rules are listed
in `.ai/rules/review/architecture.md` as "enforced by tests, do not review by hand".

**Acceptance criteria.**
AC1. `./mvnw verify` runs the architecture tests in CI ("Build, Test & Coverage") and they pass on `main`.
AC2. Each confirmed rule fails a test fixture that violates it (one negative test per rule).
AC3. `.ai/rules/review/architecture.md` lists the enforced rules; CODEOWNERS covers the module.

---

### 0-109. No secret scanning in CI or before a commit

**Type:** ci · **Priority:** Medium · **Status:** Open
**Autopilot:** not-ready · **Risk:** high · **Complexity:** low · **Depends on:** —

**Problem.** Nothing scans commits for secrets. `check-packaged-profiles.sh` (#0-81) catches secret-looking
keys under `src/main/resources` only. An agent that pastes a token into a test, a log excerpt in
`.ai/work/<id>/proofs.md` or a workflow file is not caught before the PR, and not at all outside those paths.

**Approach.** gitleaks in CI on every PR and push to `main`, installed as a standalone binary pinned by
version and SHA-256 next to it, the way the Snyk CLI is (#0-61, ADR-0021) — not a Marketplace action
(#0-62). A committed `.gitleaks.toml` with allow-list entries only for documented test fixtures. Optional:
the same binary in a local pre-commit hook and in the devcontainer.

**Acceptance criteria.**
AC1. A PR that adds a string matching a default gitleaks rule fails a required check.
AC2. The binary's version and checksum are pinned in the workflow; a checksum mismatch fails the job.
AC3. README "Infrastructure Hardening" lists the control.

---

### 0-110. Maven Enforcer: allowed repositories and pinned plugin versions

**Type:** ci · **Priority:** Low · **Status:** Open
**Autopilot:** not-ready · **Risk:** high · **Complexity:** low · **Depends on:** —

**Problem.** `mvn verify` runs every plugin a POM declares. An unattended change to a POM can add a plugin or
a repository, and the build runs that code with the network and files of wherever it runs. Review and
`factory-guards.yml` (a POM supply-chain change needs the owner's approval) are the only checks.

**Approach.** `maven-enforcer-plugin` in the root POM: `requirePluginVersions`, `banRepositories` /
`requireNoRepositories` (Maven Central only), `banDuplicatePomDependencyVersions`, and `bannedDependencies`
for known-bad coordinates. Root POM is a CODEOWNERS path.

**Acceptance criteria.**
AC1. A module declaring a plugin without a version, or a `<repository>`, fails `./mvnw verify`.
AC2. The current build passes unchanged.

---

### 0-111. OpenAPI contract diff on pull requests

**Type:** ci · **Priority:** Low · **Status:** Open
**Autopilot:** not-ready · **Risk:** low · **Complexity:** medium · **Depends on:** —

**Problem.** A change to a controller can change the public API (a removed field, a new required
parameter) without any check noticing; the Angular frontend (and any API client) finds out at runtime.

**Approach.** Generate each service's OpenAPI document in CI (springdoc at build time) and diff it against
`main` (openapi-diff); a breaking change fails unless the PR carries the owner's approval, the same rule
`factory-guards.yml` applies to test tampering. Interacts with #0-73 (OpenAPI public in every profile).

**Acceptance criteria.**
AC1. A PR removing a response field fails the check; adding an optional field passes.
AC2. The check's result names the endpoint and the breaking change.

---

### 0-112. Mutation testing on the classes a PR changes

**Type:** ci · **Priority:** Low · **Status:** Open
**Autopilot:** not-ready · **Risk:** low · **Complexity:** medium · **Depends on:** —

**Problem.** Coverage (#0-57) proves lines ran, not that tests would notice them change. The reviewers'
behaviour → test map is a judgement by a model. Nothing measures it.

**Approach.** PIT (`pitest-maven`) on the classes changed by a PR only (`scmMutationCoverage` or an explicit
target list), threshold start at 60% and raised later; informational for two weeks, then required.

**Acceptance criteria.**
AC1. A PR reports the mutation score of its changed classes in the job summary.
AC2. Once required, a score under the threshold fails the check.

---

### 0-113. Repository settings the AI factory depends on

**Type:** ci · **Priority:** High · **Status:** Open — machine account `lukaszplawiakbot` (Write) and the labels done 2026-10-09; branch protection, auto-delete and the token in the devcontainer open
**Autopilot:** human-only · **Risk:** high · **Complexity:** low · **Depends on:** —

**Problem.** The autopilot's merge safety is in GitHub settings, not in this repository: without them a
bot token can merge around every check. None of these settings is visible in a diff.

**Work** (owner, in the GitHub UI; `docs/ai-factory.md` "Setup" has the details).
1. A machine account for the autopilot, collaborator with **Write** (not Admin), fine-grained token for
   this repository only (contents and pull requests read/write, metadata read).
2. Branch protection (or a ruleset) on `main`: PR required, required checks ("Build, Test & Coverage",
   "Docker Compose Smoke Test" — #0-2, "Factory guards"), CODEOWNERS review required, and **no bypass for
   administrators**.
3. "Automatically delete head branches" on (the CLAUDE.md "delete the branch after every merge" rule for
   PRs merged by `--auto`).
4. Labels: `autopilot`, `shadow`, `blocked`, `risk-high`, `autopilot-stop`, `human:agree`,
   `human:fp-<dimension>` and `human:missed-<dimension>` for each review dimension.
5. Make "Factory guards" a required check once its workflow has run green on `main`.

**Acceptance criteria.**
AC1. A test PR from the machine account cannot be merged by it while a required check is red.
AC2. A PR touching `.ai/rules/` stays unmergeable until the owner approves it.
AC3. README "Infrastructure Hardening" records the settings.

---

### 0-116. The devcontainer firewall does not bind code an agent runs

**Type:** design · **Priority:** High · **Status:** Open — accepted for shadow mode (decision 2026-10-09); must be done before phase 3 (auto-merge)
**Autopilot:** human-only · **Risk:** high · **Complexity:** high · **Depends on:** —

**Problem.** The devcontainer's firewall (`.devcontainer/init-firewall.sh`) is `iptables` in the same network
namespace the agents run in, and it holds only while nothing there is root. Testcontainers needs Docker, so
the container reaches the host's Docker (docker-outside-of-docker), and Docker access is root over the
devcontainer itself: `docker exec -u 0 <this container> …` works from `dev` (checked 2026-10-09), and so
would a container on the host network or in this container's network namespace. Code an agent writes and
runs (a test) can therefore drop the firewall or send data out around it, read files the agents' tools may
not (`docker/.env`, `application-local.yml`), and start containers outside it. The agents' own commands
are still bound: `Bash(docker *)`, `curl` and `sudo` are denied in `.claude/settings.autopilot.json`, and
the hooks refuse what they can recognise.

**Decided for now (2026-10-09).** Accepted while the autopilot runs in shadow mode: the machine account's
token reaches this repository only, CI runs on every PR, the owner reads each PR before merging, and the
autopilot cannot merge. Recorded in `docs/ai-factory.md` ("What isolates what") and README "Infrastructure
Hardening". Not accepted for unattended merging: this item is a precondition of phase 3.

**Options (to analyse before implementation).**
- **A. No Docker in the devcontainer.** The firewall becomes a real boundary (no root for `dev`); the
  test gate runs unit tests only and Testcontainers tests run in CI alone, so items touching repositories,
  migrations or Kafka show their failures only on the PR.
- **B. A filtering Docker API proxy (recommended).** The container gets a proxy instead of the socket,
  deny by default on the request body: no `exec` into the devcontainer, no `Privileged`, no `CapAdd`, no
  host or `container:` network mode, no host PID, no bind mounts outside an allow-list, and only listed
  images (`postgres:16-alpine`, `apache/kafka`, Ryuk): an image name is itself a channel, as pulling
  `<host>/<data>:tag` contacts that host. The path-only proxy used for Alloy (#0-94 step 2, tecnativa)
  cannot inspect bodies, so this is a component of our own, with its own tests, kept in step with the
  calls Testcontainers makes.
- **C. The boundary outside the container**: a separate VM (Lima, Colima) or cloud runner whose egress is
  filtered at the host or hypervisor. A second Docker daemon for the tests (dind) on Docker Desktop needs
  `--privileged`, from which escaping into Docker Desktop's VM, which has the internet, is easy.

**Acceptance criteria** (for the option chosen).
AC1. From inside the devcontainer, code run as `dev` cannot change the firewall's rules or reach a host
outside the allow-list, directly or through any container it can start.
AC2. The Testcontainers tests still run in the autopilot's test gate (B, C), or the gate's reduced scope
is documented and CI covers the rest (A).
AC3. `docs/ai-factory.md` and README "Infrastructure Hardening" describe the boundary as it is.

---

### 0-117. The escalation level bound is hard-coded in three services

**Type:** tech-debt · **Priority:** Low · **Status:** Open (found by `/ready #0-7`, 2026-10-09)

**Problem.** The highest escalation level, `2`, is written separately in escalation-service
(`EscalationTask.isMaxLevel()`, `>= 2`), notification-service (`IncidentEventConsumer.MAX_ESCALATION_LEVEL`)
and, after #0-7, incident-service. A third level added in one place would be dead-lettered by the others.

**Approach (to analyse).** One constant in `shared` next to `IncidentEscalatedEvent`, used by all three; a
change to `shared` changes all seven services (CLAUDE.md), so it is its own item, not part of #0-7.

---

### 0-118. An out-of-order escalation event lowers a recorded level

**Type:** bug · **Priority:** Low · **Status:** Open (found by `/ready #0-7`, 2026-10-09; not verified)

**Problem.** `Incident.recordEscalation(int)` assigns the level unconditionally, so an `IncidentEscalatedEvent`
of level 1 consumed after one of level 2 (a redelivery, a replay from the dead-letter topic) sets the
incident back to 1. Whether `incidents.lifecycle` can deliver them out of order for one incident (both keyed
by the incident id, one partition) needs checking first.

**Approach (to analyse).** Keep the higher level (never lower it from this consumer), with a test; or decide
that ordering by key makes it impossible and close this item with that reasoning.

---

### 0-119. postmortem-service coerces `durationMinutes` with `asInt(0)`

**Type:** bug · **Priority:** Low · **Status:** Open (noticed by `/ready #0-7`, 2026-10-09; effect not verified)

**Problem.** postmortem-service's `IncidentEventConsumer` reads `event.path("durationMinutes").asInt(0)`, the
pattern #0-7 removes from incident-service: a missing or malformed duration becomes `0` silently. Whether a 0
duration is harmful there (a postmortem stating the incident lasted no time) is not checked.

**Approach.** Verify the effect, then validate as #0-7 does (dead-letter through the existing path) if it
matters.

---

### 0-120. Kubernetes staging and prod overlays are swapped

**Type:** bug · **Priority:** Medium · **Status:** Open (found by `/ready #0-42`, 2026-10-10)

**Problem.** `k8s/overlays/staging/kustomization.yml` sets `namespace: incident-platform-prod`, 2–3 replicas
and image tag `1.0.0`, and `k8s/overlays/prod/kustomization.yml` sets `namespace: incident-platform-staging`
and tag `staging`. Each directory's `secrets.yml` names its own namespace (`metadata.namespace` and comments), but
the overlay's `namespace:` overrides it, so the staging Secret renders into `incident-platform-prod` and the prod
Secret into `incident-platform-staging`. Deploying "staging" therefore writes into the prod namespace with release
images and staging secrets, and "prod" runs the `staging` tag.

**Approach.** Check the whole of each overlay (namespace, replicas, tags, patches), then swap the
`kustomization.yml` contents so each directory describes its own environment; the `secrets.yml` files are
already right. A CI assertion that each overlay's rendered namespace ends in its directory name would keep it
from coming back.

---

### 0-121. A pipeline audit traces problems to the stage that introduced them

**Type:** design · **Priority:** Medium · **Status:** Open (owner's decision 2026-10-10, after #0-42)

**Problem.** The only audit target is `reviewers` (`.claude/workflows/audit.js`): one analyst per review
dimension, which fits seven parallel reviewers with one verdict format. The stages before and after the panel
— `/ready` with `ready-checker`, the planner, the picker, the architect, the implementer, acceptance and the
shipper — run in sequence, and a problem in one usually shows up in a later one. #0-42 was introduced in
`/ready` (an AC needing `.github/`), passed the architect (`Risk: high` let it through) and was detected only
by the implementer (draft PR #477). No analyst looks at that chain: the reviewers' audit sees nothing (no
review ran), and an audit per agent would see each stage acting correctly on its own.

**Decided (owner, 2026-10-10).** Not one auditor per agent: one analyst for the whole pipeline beside the
`reviewers` audit. It measures phase containment — for each item that did not go through cleanly (BLOCKED,
extra rounds, `backlog-estimate-off`, an escape) the stage where the problem was introduced and the stage where
it was detected — pools cases across stages (so the ≥3-case rule of `.ai/rules/audit.md` is reachable) and
addresses each recommendation to the stage that should have caught it. A stage gets its own analyst only when
the data shows it produces most of the cases.

**Data already recorded** (backlog #0-122): the stop stage and draft PR of a BLOCKED item in
`.ai/runs/state.json`, the "Ready check" section of a `/ready` PR, and the hard-fact rule for deterministic
contradictions in `audit.md`.

**Work.** A target `pipeline` in `audit.js`, an `audit-pipeline` agent definition, its data in
`scripts/factory/audit-data.sh` (the `/ready` PRs, `handoff.md` of BLOCKED items), and its section in
`.ai/rules/audit.md`. Human-only: every file is a protected path. Worth starting once a few BLOCKED or
reworked items exist to measure.

---

### 0-122. Protected paths: one list, a Touches gate and the stop stage

**Type:** bug · **Priority:** High · **Status:** Open (found by #0-42's autopilot run, 2026-10-10)
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** —
**Touches:** ci (scripts/factory, .github/scripts, .github/workflows/factory-guards.yml), root (README.md, CLAUDE.md, AGENTS.md, .ai/rules, .claude)

**Problem.** #0-42 was marked `ready` with `Risk: high` and an acceptance criterion needing a new
`.github/` check. The architect let it through (`Risk: high` items proceed), and only the implementer, which
may not write `.github/`, stopped it (draft PR #477) — one wasted run. Behind it: `ready.md` read "Risk: high
or not ready" without saying when, `ready-checker` pushed criteria toward new CI scripts, no code checked an
item against the paths the autopilot may not write, and that list existed in about ten places that had
drifted apart (the shell hook did not cover `.ai/audit/decisions.md`; one copy treated all of `CLAUDE.md` as
protected). The run state recorded the block with no PR and no stage, so an audit could not see where it
was stopped.

**Decided (owner, 2026-10-10).** One list, `.ai/rules/protected-paths.md`, read by the scripts (from the base
commit, failing closed) and by every agent that needs it; the deny rules, the shell hook and CODEOWNERS keep
literal copies, checked in CI. The gate reads **Touches only** (a criterion may name an existing check) and
treats a bare `ci` as protected. `Risk: high` only says who merges. A deterministic contradiction of rules is
a hard fact in `audit.md` (one case is enough for a missing-gate recommendation). One pipeline auditor
later, #0-121.

**Delivered.** `_protected.sh` reading the list; `next-item.sh` / `check-queue.sh` refuse such an item;
`changed-paths.sh` and `check-factory-guards.sh` rule 6 read the same list (from the base);
`check-protected-paths.sh` (deny rules, hook, CODEOWNERS by GitHub's last-match rule) with its test; the hook covers `.ai/audit/decisions.md`; `state.sh` records the stop stage and draft PR (`prArg`
keeps the record when a URL is odd); `ready.md`, `ready-checker`, the architect, `/ready` (a "Ready check"
section in its PR), the implementer, the reviewers' `_common.md`, `AGENTS.md`, `CLAUDE.md`, README and
`docs/ai-factory.md` point at the list; #0-42 is `human-only`.

---

### 0-115. Rewrite the review rules as invariants

**Type:** docs · **Priority:** Medium · **Status:** Open
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** —

**Problem.** The ~90 rules in `.ai/rules/review/*.md` came from the former review agents and are written
as questions to a reviewer (`SEC-01` "Could this code path return, log, or leak data across tenant
boundaries?", `ARC-01` "Does each new responsibility live in…?"). Since 2026-10-09 the implementer reads
them too — `general`, `architecture` and `security` in full, the others as the architect selects them —
and a question to a reviewer is a weak instruction to the one who writes the code. Some rules are
general knowledge the model has without the file; some will be enforced by tests (#0-108, #0-112) and
need no one to remember them.

**Approach** (owner's work: `.ai/rules/` is human-owned; an agent may draft, the owner decides).
1. One file per PR, so the audit can compare review rounds and findings before and after it.
2. Each rule as an invariant — what is true of the code — with one line "Check:" under it, e.g.
   `**SEC-03** A tenant comes from authentication; it is never read from a request body, a path parameter
   or an unsigned header. Check: every new tenant read traces to the security context or a signed token.`
3. Ids stay stable (verdicts and audits cite them). A removed rule keeps its id as `retired — <why>`.
4. Drop what is generic (the model knows it without this file); mark what a test enforces
   ("Enforced by: ArchUnit rule …"), so reviewers skip it.
5. Aim at 10–15 rules per file; the core files (`general`, `architecture`, `security`) are read for every
   item, so every line there costs on every run.

**Acceptance criteria.**
AC1. Every rule of the rewritten file is an invariant with a "Check:" line, or retired with a reason.
AC2. No id was reused or renumbered.
AC3. The next audit after each file reports review rounds and blocking findings of that dimension, before
and after.
