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
| [0-9](#0-9-stale-index-annotations-on-notificationlog) | Stale `@Index` annotations on `NotificationLog` | tech-debt | Low | Open |
| [0-13](#0-13-asymmetric-service-tokens-or-mtls-for-service-identity) | Asymmetric service tokens or mTLS for service identity | design | Medium | Open |
| [0-14](#0-14-by-slack-is-open-to-any-authenticated-role) | `GET /by-slack/{id}` is open to any authenticated role | tech-debt | Low | Open |
| [0-15](#0-15-incidentackclient-is-not-authorized-on-the-status-endpoint) | `IncidentAckClient` is not authorized on the status endpoint | bug | Medium | Open |
| [0-16](#0-16-decide-the-alertmanager-service-token-tenant-role-and-lifetime) | Decide the Alertmanager service token: tenant, role, lifetime | design | High | Open |
| [0-17](#0-17-alert-on-service_client_fallback_totalreasonauth) | Alert on `service_client_fallback_total{reason="auth"}` | tech-debt | Medium | Open |
| [0-20](#0-20-tenant-owned-fallback-contact) | Tenant-owned fallback contact | design | Medium | Open |
| [0-21](#0-21-slack-is-one-workspace-for-the-whole-platform) | Slack is one workspace for the whole platform | design | High | Open |
| [0-22](#0-22-the-operator-alert-is-email-only) | The operator alert is email only | tech-debt | Low | Open |
| [0-23](#0-23-the-oncall-retry-is-probably-inactive) | The `oncall` `@Retry` is probably inactive | tech-debt | Low | Open |
| [0-24](#0-24-on-call-contacts-are-not-verified-against-tenant-membership) | On-call contacts are not verified against tenant membership | design | Medium | Open |
| [0-25](#0-25-notificationqueueentry-has-no-version) | `NotificationQueueEntry` has no `@Version` | tech-debt | Medium | Open |
| [0-28](#0-28-notification_queue-rows-are-never-purged) | `notification_queue` rows are never purged | tech-debt | Low | Open |
| [0-29](#0-29-staging-and-prod-k8s-overlays-are-wholesale-swapped-not-just-their-namespace) | staging and prod k8s overlays are wholesale swapped, not just their `namespace:` | bug | Low | Open |

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

**Deliverable.** A recorded decision, then an implementation item. Backlog #0-1 is done, so this is ready to revisit. Related:
backlog #0-13, #0-17.

---

### 0-17. Alert on `service_client_fallback_total{reason="auth"}`

**Type:** tech-debt · **Priority:** Medium · **Status:** Open

**Problem.** Fail-open clients count every fallback in `service.client.fallback{client,target,reason}`
(backlog #0-11), and `reason="auth"` (401/403) is a misconfiguration, not an outage. Nothing alerts on
it. `docker/prometheus.rules.yml` is not the place yet: its alerts reach ingestion through Alertmanager and
become incidents of the tenant in Alertmanager's token (currently `system`), so an alert about the
platform itself depends on how that operator tenant is set up in backlog #0-16.
Also: `service.client.fallback` does not say which operation fell back (for example a target lookup or a PRIMARY lookup); an
`operation` tag needs a change in `shared`.

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

### 0-21. Slack is one workspace for the whole platform

**Type:** design · **Priority:** High · **Status:** Open

**Problem.** `notification.channels.slack` has one `bot-token`, one `channel` and one `signing-secret` for
the whole platform (one k8s `Secret` per environment). That is a single-organisation design. In a
multi-tenant SaaS each customer has its own Slack workspace, and one bot token cannot DM their users, so
with `broadcast-enabled=false` (the safe default since backlog #0-18) Slack works at all only for the one
tenant whose engineers happen to be in the platform's own workspace — every other tenant's DM branch in
`SlackNotificationChannel.send()` never fires (`isSlackUserId` is false for them) and the channel fails per
`NotificationException`. `SlackWebhookController`/`SlackActionService` (ACK-via-Slack) have the same
single-workspace assumption on the inbound side.

**Verified against Slack's own docs (not assumed):** the signing secret is per-app, not per-workspace — one
Slack App has one `Signing Secret` that verifies every installation's callbacks, so `SlackSignatureVerifier`
and `SLACK_SIGNING_SECRET` do **not** need to become per-tenant. Only the bot token is per-workspace (one
`xoxb-...` per OAuth install, standard for a multi-workspace Slack app), and the interactive payload carries
the workspace id (`payload.team.id`) as an optional cross-check. See
[Authentication overview](https://api.slack.com/authentication),
[Installing via OAuth](https://docs.slack.dev/authentication/installing-with-oauth/),
[block_actions payload](https://docs.slack.dev/reference/interaction-payloads/block_actions-payload/).

**Precedent in this codebase.** `Integration` (auth-service) is the shape match: a named, tenant-scoped
connection to an external system, its own lifecycle (`create`/`revoke`), its own credential
(`ApiKey`, `@OneToOne`) — a per-tenant Slack workspace is the same kind of object, for outbound notification
instead of inbound alerts. `tenant_settings` (`V12__add_mfa_support.sql`) is the storage-philosophy
counter-precedent: its own migration comment says typed columns over generic key-value are deliberate,
which argues against folding Slack into it as a settings row rather than a connection with a lifecycle.
`AesEncryptionService` (already encrypting `users.mfa_secret` as AES-256-GCM) is the precedent for the bot
token itself — the first genuinely new thing here is that no existing credential in this codebase is
issued *by* a third party and stored by us (`ApiKey`/JWTs are platform-issued, MFA secrets are
user-entered); an OAuth-installed Slack bot token would be the first.

**Options.** (A) Add typed Slack columns to `tenant_settings` — smallest diff, but fights that table's own
stated purpose (simple flags, not objects with an install/revoke lifecycle) and doesn't generalize to
team-scoped channels later. (B) A dedicated `Integration`-shaped entity in auth-service (`slack_team_id`,
`bot_token` encrypted via `AesEncryptionService`, `default_channel`, `broadcast_enabled`, `installed_at`,
`revoked_at`), fetched by notification-service cross-service — matches the established "identity/tenant
data lives in auth-service" architecture and reuses the existing encryption primitive, at the cost of a
new cross-service fetch-and-cache path notification-service doesn't have today for this kind of data.
(C) Store it in notification-service's own database — keeps Slack code and data together, but
notification-service has no encryption-at-rest primitive today, so this means either a live bot token in
plaintext or duplicating `AesEncryptionService` (a second key to manage) — and fights the "identity data has
one home" architecture directly.

**Recommendation.** (B). The sensitive-token risk alone rules out (C); between (A) and (B), `tenant_settings`'s
own documented purpose argues for treating a Slack workspace as an `Integration`-like connection, not a
settings flag, and it leaves room for team-scoped channels (a predictable next ask, since `Integration`
already scopes to `Team`) without a second migration. With `broadcast-enabled=true` the router still skips
Slack for a contact without a valid Slack user id, so that entry also gets no shared-channel post; the
per-tenant integration should settle what broadcast means.

**Correction to the ACK-flow claim above:** `SlackActionService.processAcknowledgeAction` already carries
`tenantId` safely through the ACK round-trip today (embedded server-side in the button's own `value` field,
which Slack echoes back unmodified — not user-editable, so already trustworthy). It doesn't need a *new* way
to learn the tenant; it needs to use the tenant it already has to call
`SlackNotificationChannel.updateMessageAfterAck` with *that tenant's* bot token instead of the one global
one. `payload.team.id` is optional defense-in-depth here (cross-checking the click's workspace against the
tenant recorded in the button), not a requirement.
The README paragraph "Why Slack Bot Token instead of Incoming Webhook?" still describes channel posts and should be
updated with the same change.

---

### 0-22. The operator alert is email only

**Type:** tech-debt · **Priority:** Low · **Status:** Open

**Problem.** The content-free alert for an UNDELIVERABLE notification (backlog #0-18) is sent by email only.
`SlackNotificationChannel.send` attaches an ACK button bound to the incident id and stores the message, which
an operator alert must not do, so Slack and SMS operator alerts need a plain-message path of their own.
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
| — | Register a default no-op `TokenRevocationChecker` so incident-service starts (unblocked CI on `main`) | PR #410 |
| — | Key notification idempotency on tenant + escalation level; stop dropping level-2 escalations | PR #411 |
| — | Align README/CLAUDE.md with the code; add LICENSE; scrape auth-service in Prometheus | PR #409 |

Move an item here, with its PR, when it is finished. Items completed before this file existed are
not listed.
