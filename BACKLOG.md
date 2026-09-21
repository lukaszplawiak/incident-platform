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
| [0-1](#0-1-escalation-notifications-go-to-the-primary-on-call-not-the-escalation-target) | Escalation notifications go to the PRIMARY on-call, not the escalation target | bug | High | Open |
| [0-2](#0-2-make-the-docker-compose-smoke-test-a-required-check-and-add-context-boot-tests) | Make the compose smoke test a required check; add context-boot tests | ci | High | Open |
| [0-3](#0-3-decide-token-revocation-coverage-across-services) | Decide token-revocation coverage across services | design | Medium | Open |
| [0-4](#0-4-escalation-event-is-published-at-most-once) | Escalation event is published at-most-once | tech-debt | Medium | Open |
| [0-5](#0-5-testcontainers-and-kafka-test-jars-ship-in-every-service-jar) | Testcontainers and Kafka test jars ship in every service jar | tech-debt | Medium | Open |
| [0-6](#0-6-untracked-todos-need-a-backlog-reference) | Untracked `TODO`s need a backlog reference | tech-debt | Low | Open |
| [0-7](#0-7-incident-service-coerces-escalationlevel-with-asint0) | incident-service coerces `escalationLevel` with `asInt(0)` | bug | Low | Open |
| [0-8](#0-8-dead-publishescalated-and-a-stale-javadoc-in-incident-service) | Dead `publishEscalated` and a stale Javadoc in incident-service | tech-debt | Low | Open |
| [0-9](#0-9-stale-index-annotations-on-notificationlog) | Stale `@Index` annotations on `NotificationLog` | tech-debt | Low | Open |
| [0-10](#0-10-notificationscheduler-loads-all-pending-entries-without-a-limit) | `NotificationScheduler` loads all pending entries without a limit | tech-debt | Low | Open |
| [0-12](#0-12-notification-primary-lookup-sends-no-teamid) | Notification PRIMARY lookup sends no `teamId` | bug | Medium | Open |
| [0-13](#0-13-asymmetric-service-tokens-or-mtls-for-service-identity) | Asymmetric service tokens or mTLS for service identity | design | Medium | Open |
| [0-14](#0-14-by-slack-is-open-to-any-authenticated-role) | `GET /by-slack/{id}` is open to any authenticated role | tech-debt | Low | Open |
| [0-15](#0-15-incidentackclient-is-not-authorized-on-the-status-endpoint) | `IncidentAckClient` is not authorized on the status endpoint | bug | Medium | Open |
| [0-16](#0-16-decide-the-alertmanager-service-token-tenant-role-and-lifetime) | Decide the Alertmanager service token: tenant, role, lifetime | design | High | Open |
| [0-17](#0-17-alert-on-service_client_fallback_totalreasonauth) | Alert on `service_client_fallback_total{reason="auth"}` | tech-debt | Medium | Open |

---

### 0-1. Escalation notifications go to the PRIMARY on-call, not the escalation target

**Type:** bug · **Priority:** High · **Status:** Open (PR #411 and the oncall-service endpoint done, notification-service left; see below)

**Problem.** escalation-service (`EscalationScheduler.escalate()`) resolves the SECONDARY
(level 1) or MANAGER (level 2) user through oncall-service and publishes
`IncidentEscalatedEvent(escalateTo, escalationLevel, ...)`. notification-service stores both on
its outbox entry, but `NotificationRouter.route(...)` still resolves the recipient with
`oncallClient.getCurrentOncall(tenantId, "PRIMARY")` for every event type. Escalation
notifications therefore reach the PRIMARY's addresses (or the configured fallback addresses).

**Done.** PR 1 (PR #411): level-2 notifications are no longer dropped as duplicates; idempotency is
keyed on tenant + event type + escalation level, and `escalate_to` is stored on
`notification_queue`.

**Prerequisite (backlog #0-11).** Service tokens were rejected by `JwtAuthFilter`, so every
service-to-service HTTP call was a 401 and `escalateTo` was always null. Fixed first; without it
the steps below would be unreachable in a real deployment.

**Done (oncall-service).** `GET /api/v1/oncall/current/by-user/{userId}` returns the user's current
on-call entry with contact details, scoped to the request tenant **and** the user id together
(`escalateTo` is an unverified id from a Kafka payload; a lookup by user id alone could send one
tenant's incident to another tenant's user). Restricted to SERVICE and ADMIN at URL level, since the
response carries email and phone number. A user with several concurrent entries gets the most recently
started one. Only the contact details in the response are meaningful: the role of that entry is not
authoritative (no `teamId` is returned either), so notification-service must not derive anything from it.

**Remaining (notification-service).**
1. `OncallClient` method for the new endpoint; `NotificationRouter` uses the entry for
   `IncidentEscalatedEvent` from `escalateTo` (fall back to the configured fallback addresses when
   there is no target or no schedule).
2. `OncallClientImpl.getCurrentOncall(tenantId, role)` sends no `teamId`, so "PRIMARY" is
   resolved tenant-wide. Split out as backlog #0-12.
3. Remove the "Current limitation" text from the README (Escalation Chain) and from
   `.ai/context/project.md` when this lands.

**Acceptance.** Level-1 and level-2 escalations notify the SECONDARY and MANAGER respectively;
tests in `NotificationRouterTest`, the oncall controller/service tests and a tenant-mismatch
case; the docs above updated.

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

- `auth-service`: `AuthServiceApplication` (future service split); `User` and
  `UserManagementService` (Data Vault)
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

### 0-9. Stale `@Index` annotations on `NotificationLog`

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Problem.** `NotificationLog` declares `idx_notification_log_incident_id`, `_tenant_id` and
`_sent_at`, which migration V2 dropped. Harmless under `ddl-auto: validate` (indexes are not
validated) but misleading. Align the annotations with the real indexes or remove them.

---

### 0-10. `NotificationScheduler` loads all pending entries without a limit

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Problem.** `NotificationQueueRepository.findPendingOlderThan(...)` returns a `List` with no
limit, so after an outage or burst the scheduler loads every pending entry in one cycle and works
through them with synchronous HTTP calls. The same class of problem was fixed in
`EscalationScheduler` (backlog #39, `scheduler-batch-size`).

**Approach.** Cap per cycle with a `Pageable`/limit and a configurable
`notification.scheduler-batch-size`, mirroring backlog #39.

---

### 0-12. Notification PRIMARY lookup sends no `teamId`

**Type:** bug · **Priority:** Medium · **Status:** Open

**Problem.** `OncallClientImpl.getCurrentOncall(tenantId, role)` calls `/api/v1/oncall/current`
with `role` only, so "PRIMARY" is resolved tenant-wide, which is ambiguous when a tenant has more
than one team. Split out of backlog #0-1 (item 3): the escalation path needs no `teamId` because it
looks the target up by tenant and user id.

The by-user lookup (backlog #0-1) returns the most recently started entry when one user holds
concurrent entries on different teams, which is not necessarily the team of the incident. Only the
contact details are used today, so this is harmless, but it is the same missing-team gap: once
`teamId` travels with the event, the lookup can take it as an optional filter.

**Approach.** Carry `teamId` through `IncidentEscalatedEvent`/the incident events, the
notification consumer and `NotificationQueueEntry` (Flyway migration), then send it. Touches `shared`
event records, so it rebuilds all 7 services: do it as its own PR.

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

### 0-16. Decide the Alertmanager service token: tenant, role, lifetime

**Type:** design · **Priority:** High · **Status:** Open

**Context.** Alertmanager is part of the optional local monitoring stack (README "Step 5"; it is not
deployed in `k8s/`). It posts to `/api/v1/alerts/prometheus` with a 30-day JWT that
`AlertManagerTokenRefresher` and `scripts/generate-alertmanager-token.sh` mint with
`tenantId = "system"`, `ROLE_SERVICE` and (since backlog #0-11) `aud = ingestion-service`. Before #0-11
`JwtAuthFilter` rejected that token, so Alertmanager never authenticated; now it does.

`system` is intentional: four rules in `docker/prometheus.rules.yml` about the platform itself
(`IpRateLimitExceeded`, `IncidentServiceDown`, `KafkaConsumerLag*`) carry `tenantId: system`. So the
question is not "which tenant?" but how to make `system` a real operator tenant and whether a service
token is the right credential for an external alert source.

**Findings (by reading the code, not run).**
- A `tenantId` label does not choose the tenant: the normalizer copies labels into metadata and the
  tenant comes only from the caller's token. The misleading comment in `prometheus.rules.yml` is fixed.
- No `system` tenant is defined anywhere: no seed, migration, users or on-call. In development you can
  enter it with `/dev/token?tenantId=system`; otherwise nobody owns those incidents.
- The name is not reserved: tenant ids are free-form strings.
- The token is `ROLE_SERVICE`, and ingest accepts `hasRole('SERVICE')`, so any service token minted for
  the target tenant can post alerts, not only Alertmanager's. The script's comment says `ROLE_INGESTOR`.
- The token has no `jti` (it cannot be revoked) and lives 30 days.

**Options.** (1) Keep `system`: reserve the name and give that tenant users and on-call.
(2) Narrow the role to ingest only. (3) Replace the JWT with an Integration API key
(`Authorization: ApiKey ipl_<prefix>.<secret>`, scope `alerts:ingest`, per tenant, revocable);
`ApiKeyAuthFilter` already reads that header, and Prometheus documents `authorization.type` as
configurable, which still has to be verified against the Alertmanager version in `docker-compose.yml`.
(4) Take the tenant from the alert label: rejected in review, since whoever writes the rules would
choose the tenant.

**Deliverable.** A recorded decision, then an implementation item. Revisit after backlog #0-1. Related:
backlog #0-13, #0-17.

---

### 0-17. Alert on `service_client_fallback_total{reason="auth"}`

**Type:** tech-debt · **Priority:** Medium · **Status:** Open

**Problem.** Fail-open clients count every fallback in `service.client.fallback{client,target,reason}`
(backlog #0-11), and `reason="auth"` (401/403) is a misconfiguration, not an outage. Nothing alerts on
it. `docker/prometheus.rules.yml` is not the place yet: its alerts reach ingestion through Alertmanager and
become incidents of the tenant in Alertmanager's token (currently `system`), so an alert about the
platform itself depends on how that operator tenant is set up in backlog #0-16.

---

## Done

| # | Title | Delivered in |
|---|---|---|
| 0-11 | Service tokens were rejected by `JwtAuthFilter`: per-tenant, per-audience service tokens, `ServicePrincipal`, real-token filter tests, fallback metric, tenant-id validation, escalation client timeouts, correct oncall URL default | PR #413 |
| — | Register a default no-op `TokenRevocationChecker` so incident-service starts (unblocked CI on `main`) | PR #410 |
| — | Key notification idempotency on tenant + escalation level; stop dropping level-2 escalations | PR #411 |
| — | Align README/CLAUDE.md with the code; add LICENSE; scrape auth-service in Prometheus | PR #409 |

Move an item here, with its PR, when it is finished. Items completed before this file existed are
not listed.
