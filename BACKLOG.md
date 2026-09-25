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
| [0-29](#0-29-staging-and-prod-k8s-overlays-are-wholesale-swapped-not-just-their-namespace) | staging and prod k8s overlays are wholesale swapped, not just their `namespace:` | bug | Low | Open |
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

**Approach.** Add a `version` column (its own Flyway migration — V7 went to backlog #0-12's `team_id`
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

**Type:** bug · **Priority:** Low · **Status:** Open

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
the same for every channel and cause. Not a Slack-only special case.

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
| — | Register a default no-op `TokenRevocationChecker` so incident-service starts (unblocked CI on `main`) | PR #410 |
| — | Key notification idempotency on tenant + escalation level; stop dropping level-2 escalations | PR #411 |
| — | Align README/CLAUDE.md with the code; add LICENSE; scrape auth-service in Prometheus | PR #409 |

Move an item here, with its PR, when it is finished. Items completed before this file existed are
not listed.
| 0-50 | Accept-invite, reset-password and refresh-token rotation failed with `LazyInitializationException` on a real database (bug since `f3d05fd`): `AuthTokenRepository.markUsedIfUnused` had `clearAutomatically = true`, so `consumeToken` detached the token and its lazy `User` proxy before every caller used `token.getUser()`. The single-row claim no longer clears the persistence context; `consumeToken` sets the same `usedAt` in memory. Testcontainers tests now drive accept-invite, reset-password and refresh rotation through the real services; MFA goes through the same `consumeToken` path (service unit tests mock the repositories, and #0-47 had kept anyone from reaching accept-invite) | PR (number filled after opening) |
