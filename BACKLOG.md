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
- **Status:** `Open` · `In progress` · `Done` (keep Done items, with the PR, so references in code
  stay resolvable).
- **Type:** `bug` · `design` (a decision is needed before implementation) · `tech-debt` ·
  `ci` · `docs`.
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
| [0-7](#0-7-incident-service-coerces-escalationlevel-with-asint0) | incident-service coerces `escalationLevel` with `asInt(0)` | bug | Low | Open |
| [0-8](#0-8-dead-publishescalated-and-a-stale-javadoc-in-incident-service) | Dead `publishEscalated` and a stale Javadoc in incident-service | tech-debt | Low | Open |
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
| [0-39](#0-39-kafka-tenant-resolution-no-headerpayload-match-check-producers-without-the-tenant-header) | Kafka tenant resolution: no header/payload match check, producers without the tenant header | tech-debt | Medium | Open |
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
| [0-66](#0-66-data-stores-in-kubernetes-have-no-authentication-or-tls-and-the-db-password-is-baked-into-every-service) | Data stores in Kubernetes have no authentication or TLS, and the DB password is baked into every service | design | Medium | Open |
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
| [0-78](#0-78-the-application-database-role-is-a-postgres-superuser) | The application database role is a Postgres superuser | bug | High | Open |

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

### 0-7. incident-service coerces `escalationLevel` with `asInt(0)`

**Type:** bug · **Priority:** Low · **Status:** Open

**Problem.** `IncidentEscalationEventConsumer` reads the level with
`event.path("escalationLevel").asInt(0)` and passes it to `Incident.recordEscalation(int)`. A
missing or non-numeric level becomes `0`. notification-service fixed the same pattern in PR #411
(required, validated 1..2, otherwise dead-lettered). Verify the effect here (the level is an
attribute, not part of an idempotency key, so the impact is likely a wrong `escalationLevel` on the
incident rather than a lost notification) and apply the same validation if warranted.

**Acceptance.** A malformed level is dead-lettered or rejected instead of silently recorded as `0`;
consumer test added.

---

### 0-8. Dead `publishEscalated` and a stale Javadoc in incident-service

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Problem.** `IncidentEventPublisher.publishEscalated(...)` has no callers. The Javadoc of
`IncidentEscalationEventConsumer` still says incident-service publishes `IncidentEscalatedEvent`
for manual REST-driven escalation, so the only producer today is escalation-service's
`EscalationScheduler`.

**Work.** Remove the dead method (or wire the manual-escalation path if it is meant to exist) and
correct the Javadoc. If a manual path is ever added it must publish a level that cannot collide
with the automatic one, because a repeat escalation at the same level is deduplicated.

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
channel once (plus the in-call `@Retry` of the Slack/SMTP clients), records a failure in `notification_log`,
and marks the queue entry SENT anyway. A Slack API outage, an SMTP timeout or a Twilio error therefore loses
that channel's message for good. Backlog #0-21 adds one more cause: if auth-service cannot answer the
tenant's Slack-workspace lookup, Slack is skipped for that notification while email/SMS go out. That was a
deliberate choice: holding the whole entry (the #0-19 pattern) would make auth-service a hard dependency of
email and SMS, and retrying only the auth-service case would make one config lookup more reliable than the
send itself.

**What already exists.** Idempotency in `notification_log` is keyed per channel (incident + tenant + event type
+ escalation level + channel). If an entry stayed PENDING after a partial send, the next cycle would skip the
channels that already went out, so a per-channel retry needs no migration for that part.

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

### 0-39. Kafka tenant resolution: no header/payload match check, producers without the tenant header

**Type:** tech-debt · **Priority:** Medium · **Status:** Open (found by code reading, not run)

**Problem.** `TenantKafkaRecordResolver` takes `X-Tenant-Id` first and falls back to the payload `tenantId`,
without checking that the two agree when both are present. `AuditEventConsumer` ignores the header and trusts the
payload. auth-service, notification-service and postmortem-service do not configure
`TenantKafkaProducerInterceptor`, and `AuditEventKafkaSender` / `DeadLetterPublisher` do not stamp the header
themselves. No authorization decision depends on this today; it is a consistency gap in the Kafka leg of the
tenant-isolation invariant.

**Work.** Every producer stamps the header; the resolver dead-letters a record whose header and payload tenant
differ; `AuditEventConsumer` resolves through the resolver like the other consumers.

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

**Problem.** `k8s/base/infrastructure/app-config.yml` sets `MAIL_HOST: "mailhog"` and the dev overlay's comment says
the operator email is "caught by the same mailhog", but no manifest deploys a mail server. auth-service also requires
SMTP auth and STARTTLS (`mail.smtp.auth: true`, `starttls.required: true`) and notification-service requires auth,
so neither can send to a plain dev catcher. docker-compose had both gaps until backlog #0-16 added Mailpit and
overrode those properties with `SPRING_MAIL_PROPERTIES_*` environment variables for the compose stack.

**Work.** Deploy Mailpit in the dev overlay with the same overrides (or point `MAIL_HOST` at a real relay per
overlay), so dev on Kubernetes delivers invites and operator emails.

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

**Latent today.** The only scope check in the codebase is ingest's `hasScope(alerts:ingest)`, and #0-16 closed
that path: auth-service's introspection answers `active:false` for every PERSONAL key, so none reaches
ingestion-service. Inside auth-service, `ApiKeyLookupServiceImpl` gives a personal key its owner's current
roles and no endpoint checks a scope. It becomes a real bug as soon as any endpoint is guarded by a scope.

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
and the two interceptors (a header/payload mismatch is #0-39, a separate decision). Until then, a PR
that changes one of them fails CI's changed-lines coverage gate unless it adds those tests, which is
the intended pressure.

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

### 0-66. Data stores in Kubernetes have no authentication or TLS, and the DB password is baked into every service

**Type:** design · **Priority:** Medium · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.**
- **Redis** (`k8s/base/infrastructure/redis.yml`) runs `redis-server --appendonly yes` with no password. It holds
  the token revocation list and the rate-limit buckets, so any pod can un-revoke a token or reset a limit.
- **Kafka** (`kafka.yml`) listens `PLAINTEXT` with no SASL and no ACLs. Any pod can produce to `alerts.raw` or
  `incidents.lifecycle` with any `X-Tenant-Id`, and consumers trust that header (#0-39).
- **Postgres**: `postgresql-secret.yml` in the base ships the password `incident_secret` for every overlay, and no
  Deployment passes `DB_PASSWORD` to the services. Each one connects with the default in its own `application.yml`
  (`${DB_PASSWORD:incident_secret}`, six services), so changing the database password breaks every service until
  the code changes. Compare `JWT_SECRET`, which has no default and stops startup when missing.
- No connection to any of the three uses TLS.

**Approach.** Remove the `DB_PASSWORD` default (fail fast, as for `JWT_SECRET`) and pass it from a Secret in every
Deployment. Move `postgresql-secret` out of the base into the overlays (dev keeps a dev value). Redis `requirepass`
(or ACL users) from a Secret, wired to `REDIS_PASSWORD`, which the services already read. Kafka SASL/SCRAM with a
user per service and ACLs per topic, which also closes the forged-header path. TLS on all three once a certificate
source exists (#0-75). Pairs with #0-67.

---

### 0-67. All seven services share one database role

**Type:** design · **Priority:** Medium · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.** Every service connects as `incident_app`, which `docker/init.sql` grants `ALL` on the `public` schema and
on all future tables (the role is also a superuser, which makes every grant moot: #0-78). That each service owns its
own tables (CLAUDE.md "Persistence") is a convention; the database does not enforce it. A SQL injection or code execution bug in any service, including the least trusted ones
(postmortem-service handles LLM output), can read and write auth-service's tables: Argon2 password hashes, encrypted
MFA and Slack secrets, API key hashes, auth tokens.

**Approach.** Builds on #0-78 (a non-superuser `incident_app`). One role per service that owns its tables and its
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
- `docker/docker-compose.yml` uses `:latest` for `provectuslabs/kafka-ui`, `danielqsj/kafka-exporter`,
  `dpage/pgadmin4` and `grafana/grafana`.

**Approach.** Pin every third-party image to a version tag (and a digest where Renovate can keep it current), enable
Renovate's `kubernetes` manager for `k8s/**/*.yml`, and deploy service images by immutable tag (the commit SHA) or
digest once a registry exists.

---

### 0-72. docker-compose publishes every port on all interfaces, with default credentials

**Type:** tech-debt · **Priority:** Low · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.** Every `ports:` entry in `docker/docker-compose.yml` has the form `"5432:5432"`, which binds `0.0.0.0`,
so on any shared network (office, café) other machines reach Postgres (`incident_secret`), Redis (no password), Kafka
(plaintext), pgAdmin and Grafana (both `admin`/`admin`), Prometheus (with `--web.enable-lifecycle`, which lets anyone
reload or shut it down), Alertmanager and every service's management port. Docker's port publishing also bypasses
host firewalls such as `ufw`.

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

### 0-78. The application database role is a Postgres superuser

**Type:** bug · **Priority:** High · **Status:** Open (found in the 2026-09-30 infrastructure security audit)

**Problem.** Both docker-compose and `k8s/base/infrastructure/postgresql.yml` create `incident_app`, the role every
service connects as, through the official image's `POSTGRES_USER`, which the image documents as creating a user
"with superuser power". `docker/init.sql` said "incident_app is NOT a superuser", so the setup does not do what it
was meant to (not checked on a running database during the audit, since Docker was down;
`SELECT rolsuper FROM pg_roles WHERE rolname = 'incident_app'` confirms it). A superuser ignores every grant (so
#0-67's per-service roles would change nothing while this stands) and Row-Level Security, and can run
`COPY ... PROGRAM`: a SQL injection in any of the seven services, including the least trusted ones
(postmortem-service handles LLM output), is command execution in the database container, with its network access
and its volume. No SQL injection is known; the queries are JPA or parameterised. The priority is about the blast
radius of the first one.

**Approach.** Give the image's superuser its own name and password (from a Secret, not committed), used only for
administration. `docker/init.sql` creates `incident_app` with `NOSUPERUSER NOCREATEDB NOCREATEROLE` and its own
password (k8s runs no init script at all today: mount it from a ConfigMap into `/docker-entrypoint-initdb.d`), and grants it what Flyway and the services need today. The services keep
connecting as `incident_app`, so no service config changes. Existing local volumes need `make dev-reset` (no
production data yet). Smaller than and independent of #0-67; do this first. Pairs with #0-66 (the password itself).

---

## Done

| # | Title | Delivered in |
|---|---|---|
| 0-1 | Escalations notify the `escalateTo` user, falling back to the tenant's PRIMARY, then to the configured addresses (the addresses were removed by #0-18) | PRs #411, #413, #414, #415 |
| 0-18 | Tenant content only reaches members of the tenant: no fallback address, `UNDELIVERABLE` status (V6), content-free rate-limited operator alert by email, a `NOTIFICATION_UNDELIVERABLE` audit event type of its own (`shared`), skipped channels reported, Slack shared-channel broadcast off by default, a Slack id the channel would ignore is no address | PR #416 |
| 0-19 | An oncall-service outage is no longer read as "nobody on call": the two decisive lookups throw, the entry stays PENDING until the lookup has been failing for a retry window (from its first failed lookup), then UNDELIVERABLE; a scheduler run stops after a processing budget | PR #416 |
| 0-10 | `NotificationScheduler` loads a capped page of PENDING entries, oldest first (`notification.scheduler.batch-size`, default 200), and a run stops after a processing budget validated against the ShedLock | PR #416 |
| 0-11 | Service tokens were rejected by `JwtAuthFilter`: per-tenant, per-audience service tokens, `ServicePrincipal`, real-token filter tests, fallback metric, tenant-id validation, escalation client timeouts, correct oncall URL default | PR #413 |
| 0-12 | `teamId` now flows through all 5 `IncidentEvent` records (`shared`) and `NotificationQueueEntry` (V7); the PRIMARY on-call lookup (every event type, and the escalation fallback) is team-scoped via oncall-service's already-team-aware `/current?teamId=...`. Also fixed the dead SECONDARY/MANAGER escalation chain found along the way: `IncidentOpenedEvent` never carried `teamId`, so `EscalationTask.teamId` was always null and `EscalationScheduler` always skipped team-scoped on-call routing | PR #417 |
| 0-27 | CLAUDE.md's "Between teams of one tenant the default is also 'no'" sentence is now true — closed by #0-12 | PR #417 |
| 0-26 | `NOTIFICATION_OPERATOR_ALERT_EMAIL` patched in the app-config ConfigMap for every overlay: dev mirrors docker-compose's `operator@incident-platform.local` (mailhog); staging/prod get a placeholder `ops@incident-platform.local` (this repo has no real domain anywhere), commented to replace before a real deployment | PR #417 |
| 0-21 | Slack is per tenant: each tenant connects its own workspace (`SlackWorkspace`, V17, one active per tenant via a partial unique index, bot token AES-256-GCM under a separate `slack.encryption-key`, admin API `/api/v1/slack-workspace`, manual token paste). notification-service reads it through `CachingSlackWorkspaceClient` (60 s TTL, bounded, Micrometer `cache.*` meters) over `SlackWorkspaceClientImpl` (Resilience4j, fallback throws). The global bot token/channel/broadcast config is gone. An auth-service outage skips only Slack, except when Slack was the only channel (PENDING, then `SLACK_WORKSPACE_UNAVAILABLE`). ACK via Slack is off until the OAuth install: #0-35. Follow-ups: #0-32, #0-33, #0-34 | PR #422 |
| 0-30 | Decided and implemented: a service that needs auth-service-owned tenant data pulls it over a narrow HTTP call with a service token `aud=auth-service`, accepted on exactly one `ROLE_SERVICE` endpoint (`GET /api/v1/internal/slack-workspace`), cached briefly by the caller. Kafka replication was rejected for a single consumer. Decision recorded in `.ai/context/project.md` and CLAUDE.md; the identity/config split it raised is #0-31 | PR #422 |
| 0-9 | `NotificationLog`'s `@Index` list named V1's three indexes, dropped by V2 (and V5 replaced one of V2's); it now mirrors V2/V5 and says the migrations are the source of truth. A check against the real schema is #0-36 | PR #424 |
| 0-16 | Alert sources authenticate with Integration API keys (A1): ingestion-service runs `ApiKeyAuthFilter` (`ApiKey`/`Bearer ipl_…`) and introspects the key's SHA-256 in auth-service with a tenant-less purpose token accepted on that one route only (deny by default elsewhere); 60 s cache = revocation window; 401 = definite "no", 503 + `Retry-After` = can't check; per-IP failed-auth limiter before the lookup; `hasRole('SERVICE')` removed from ingest, and ingestion accepts no service tokens. Platform alerts out of band (B3): Watchdog to a dead man's switch, critical to operator email, all to the reserved `platform-operator` tenant (invite-bootstrapped admin). Alertmanager/Prometheus pinned, rules/routes validated in CI. The 30-day `system` token, its refresher and script are gone. Only TENANT keys introspect as active (a personal key gets `active:false`). Decision in `.ai/context/project.md`. Follow-ups: #0-37..#0-46 | PR #425 |
| 0-47 | Creating a user by invite failed on a real database (bug since `e7290db1`, exposed by #0-16's `OperatorTenantBootstrap`, which crashed auth-service at startup): `User.version` and `SlackWorkspace.version` were initialised to `0L`, so Spring Data saw a new entity as existing, `save()` merged instead of persisting and `UserService.createUser` kept the transient instance (`TransientPropertyValueException AuthToken.user -> User`). Both fields are now left `null` until persist; a Testcontainers test saves a new `User` + `AuthToken` the way production does. Unit tests mock repositories and V1_1 seeds users by SQL, so nothing caught it | PR #426 |
| 0-50 | Accept-invite, reset-password and refresh-token rotation failed with `LazyInitializationException` on a real database (bug since `f3d05fd`): `AuthTokenRepository.markUsedIfUnused` had `clearAutomatically = true`, so `consumeToken` detached the token and its lazy `User` proxy before every caller used `token.getUser()`. The single-row claim no longer clears the persistence context; `consumeToken` sets the same `usedAt` in memory. Testcontainers tests now drive accept-invite, reset-password and refresh rotation through the real services; MFA goes through the same `consumeToken` path (service unit tests mock the repositories, and #0-47 had kept anyone from reaching accept-invite) | PR #428 |
| 0-51 | MFA login failed with a 500 on a real database (bug since `372ac32`/`efe96a9`): `chk_auth_token_type` (V2/V6) allowed only INVITE, PASSWORD_RESET and REFRESH, while `AuthToken.Type` also has `MFA_SESSION` and `MFA_SETUP_REQUIRED`, so neither token could be stored. V18 widens the constraint to all five types. `AuthRepositoryIntegrationTest` now stores a token of every `AuthToken.Type` (a new type without a migration fails CI) and drives MFA verification with a TOTP code through the real `MfaService` | PR #430 |
| 0-49 | The operator admin invite could be lost for good (email permanently failed while SMTP was down, or the 7-day token expired unaccepted): `OperatorTenantBootstrap` did nothing once the tenant had any user and `resend-invite` needs an admin. It is now a reconciler (`@Scheduled` + ShedLock, ~30 s after start, then hourly) that checks for an admin who can log in, re-invites the configured admin through `ResendInviteService` when the latest invite permanently failed or no valid token is left, leaves an invite in flight alone, and never creates a second admin or deletes a user (an unexpected state is an ERROR for a human). Gauge `platform.operator.admin.pending` + alert `OperatorAdminNotActivated` (critical, 1 h, promtool-tested). Rejected: re-invite only at startup, never giving up on INVITE emails, a manual reinvite flag. Outbox retry policy for all emails: #0-52 | PR #431 |
| 0-52 | Auth emails (invite, password reset) no longer give up after 3 attempts 5 minutes apart. The outbox (V19, recreated) records the intent to send: the request path only INSERTs through `AuthEmailRequestService`, and `AuthEmailScheduler` is the only writer after that. Per attempt it closes entries no longer worth sending (SUPERSEDED: newer request, accepted invite, missing user; PERMANENTLY_FAILED: deadline passed), otherwise invalidates the user's earlier tokens of the type and creates the token it sends, so no raw token is ever stored and the link is valid for its full lifetime from sending. Failed sends are retried on `AuthEmailRetryPolicy`'s backoff (1m, 5m, 30m, 2h, then every 6h) until the deadline (7 days / 15 minutes); two lanes with their own batches, a processing budget checked against the ShedLock at startup, state-guarded conditional UPDATEs instead of `@Version` (one writer), a daily purge of terminal rows. Counters `auth.email.send{type,outcome}` and `auth.email.permanently_failed{type,reason}`, alerts `AuthEmailDeliveryFailing` (critical) and `AuthEmailPermanentlyFailed` (high), promtool-tested. Fixed with it: resend-invite / forgot-password over a FAILED entry returned 500 (unique index) or, with a concurrent scheduler write, 409 instead of forgot-password's 202 — they no longer touch existing rows. The table was dropped rather than migrated (not in production); from the first production release, schema changes must stay compatible with the previous release | PR #432 |
| 0-54 | Closed by #0-52's redesign rather than by encryption: the outbox no longer stores the raw token at all — the scheduler creates the token when it sends the email and only its SHA-256 is kept, in `auth_tokens` | PR #432 |
| 0-53 | `AuthEmailOutboxRepository.findLatestByUserIdAndType` returned `Optional` from an unlimited `ORDER BY` query, so the second resend of an invite or a repeated password reset threw `IncorrectResultSizeDataAccessException` (500). Replaced by the derived `findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc`; Testcontainers test with two entries | PR #431 |
| 0-57 | No coverage rule had ever run: surefire's explicit `<argLine>` replaced the `argLine` property set by `jacoco:prepare-agent`, so the agent never attached, no `jacoco.exec` was written and `jacoco:report`/`jacoco:check` skipped themselves in every module, locally and in CI. `<argLine>` now starts with `@{argLine}`. The PR comment (`madrapps/jacoco-report`) never failed a job either (its thresholds only pick an emoji), and was never posted (read-only `GITHUB_TOKEN`, error hidden by `continue-on-error`); it now writes to the job summary, keeping the job's token read-only. A new CI step runs `diff-cover` (pinned) over the JaCoCo XML and fails a PR when under 60% of its changed Java lines are covered, and a missing report fails it too. `report` now has the same excludes as `check`. Measured at the fix: every module above 60% LINE (`shared` lowest, 67.8%); `shared`'s tenant classes: #0-58 | PR #433 |
| 0-59 | Every workflow declares its `GITHUB_TOKEN` scope instead of inheriting the repository setting: `contents: read` at workflow level, and only the jobs that need more widen their own token (`detect-changes` adds `pull-requests: read` for `dorny/paths-filter`; both Snyk jobs add `security-events: write` for the SARIF upload, which used to be granted to the whole workflow). Follow-ups: #0-60, #0-61, #0-62 | PR #434 |
| 0-60 | Every `uses:` in `.github/workflows/` names a full commit SHA with its tag in a comment, pinned to the commit each tag pointed at (no version change); Renovate keeps both current through `helpers:pinGitHubActionDigests` (the old "pin to SHA" rule pinned nothing); every `actions/checkout` sets `persist-credentials: false`, so no later step can read `GITHUB_TOKEN` from `.git/config`; the repository setting "Require actions to be pinned to a full-length commit SHA" is not part of the PR: it was turned on manually on 2026-09-29, after the PR merged and the run on `main` was green | PR #437 |
| 0-61 | The Snyk CLI in both `snyk.yml` jobs is the standalone binary at a version pinned in the workflow's `env`, checked against a SHA-256 kept next to it (`sha256sum --check --strict`) before it runs, instead of `npm install -g snyk` (unpinned, a Node wrapper with an unbundled `@sentry/node ^7` range); `SNYK_TOKEN` reaches only the scan step as an environment variable, with no `snyk auth` writing it to argv and a config file; version and checksum are bumped by hand, since Renovate cannot compute the checksum. README's hardening section became `## Infrastructure Hardening` | PR #440 |
| 0-63 | The k8s base ConfigMap set `SPRING_PROFILES_ACTIVE: "dev"`, inherited by every overlay: the rendered prod and staging manifests started incident-service with the dev profile, enabling `DevSecurityConfig` and the unauthenticated `GET /dev/token` (an ADMIN JWT for any tenant, accepted by all seven services through the shared HS512 secret) — not routed by the Ingress, but reachable from any pod since there is no NetworkPolicy; `DevTokenController`'s startup guard could not catch it, as it only rejects a missing dev profile. The base now sets no profile, `k8s/overlays/dev` adds it, and the `validate-k8s-manifests` CI job fails if the rendered staging or prod overlay sets any Spring profile (env var, property or flag form). Found in the 2026-09-30 infrastructure security audit | PR #441 |
| — | Register a default no-op `TokenRevocationChecker` so incident-service starts (unblocked CI on `main`) | PR #410 |
| — | Key notification idempotency on tenant + escalation level; stop dropping level-2 escalations | PR #411 |
| — | Align README/CLAUDE.md with the code; add LICENSE; scrape auth-service in Prometheus | PR #409 |

Move an item here, with its PR, when it is finished. Items completed before this file existed are
not listed.
