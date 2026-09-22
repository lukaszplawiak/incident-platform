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
| [0-12](#0-12-notification-primary-lookup-sends-no-teamid) | Notification PRIMARY lookup sends no `teamId` | bug | High | Open |
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
| [0-26](#0-26-set-notification_operator_alert_email-per-kubernetes-environment) | Set `NOTIFICATION_OPERATOR_ALERT_EMAIL` per Kubernetes environment | design | Medium | Open |
| [0-27](#0-27-claudemd-says-team-isolation-is-the-default-but-it-is-not-enforced) | CLAUDE.md reads as if team isolation were enforced | docs | Low | Open |
| [0-28](#0-28-notification_queue-rows-are-never-purged) | `notification_queue` rows are never purged | tech-debt | Low | Open |
| [0-29](#0-29-staging-and-prod-k8s-overlays-have-swapped-namespaces) | staging and prod k8s overlays have swapped namespaces | bug | Low | Open |

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

### 0-12. Notification PRIMARY lookup sends no `teamId`

**Type:** bug · **Priority:** High · **Status:** Open

**Problem.** `OncallClientImpl.getCurrentOncall(tenantId, role)` calls `/api/v1/oncall/current`
with `role` only, so "PRIMARY" is resolved tenant-wide, which is ambiguous when a tenant has more
than one team. Split out of backlog #0-1 (done): the escalation path needs no `teamId` because it
looks the target up by tenant and user id.

This is the team-level counterpart of the tenant isolation done in #0-18. A tenant is one customer; below
it are teams, then members. Between customers the boundary is absolute; between teams of one customer it is
"no by default, unless someone configures it" (as in PagerDuty, where an escalation policy belongs to a
service owned by a team, and a private team's services and incidents are invisible to other teams). The
PRIMARY step of the recipient chain is tenant-wide, so in a tenant with several teams it can notify another
team's PRIMARY about an incident that is not theirs. With `teamId` in the event and on the queue entry the
chain becomes target, that team's PRIMARY, UNDELIVERABLE.

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

**Problem.** `notification.channels.slack` has one `bot-token` and one channel for the whole platform.
That is a single-organisation design. In a multi-tenant SaaS each customer has its own Slack workspace, and
one bot token cannot DM their users, so with `broadcast-enabled=false` (the safe default since backlog
#0-18) the Slack DM only works for users who are in the platform's own workspace.

**Approach.** A per-tenant Slack integration: each tenant installs the app in its own workspace (OAuth) and
notification-service uses that tenant's token and channel, stored per tenant. The ACK button flow
(`SlackActionService`, `SlackMessageStore`) has to resolve the tenant's token too. Until then Slack is
usable only in a single-organisation deployment.
With `broadcast-enabled=true` the router still skips Slack for a contact without a valid Slack user id, so that entry
also gets no shared-channel post; a per-tenant integration should settle what broadcast means.
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

**Approach.** Add a `version` column (its own Flyway migration, V7) and `@Version`, and decide how the scheduler treats
an `OptimisticLockingFailureException` (skip the entry; the next cycle reloads it).

---

### 0-26. Set `NOTIFICATION_OPERATOR_ALERT_EMAIL` per Kubernetes environment

**Type:** design · **Priority:** Medium · **Status:** Open

**Problem.** Backlog #0-18 replaced the shared fallback address with a content-free alert email to the platform operator
(`notification.operator-alert.email`, no default). The k8s base ConfigMap `app-config` ships
`NOTIFICATION_OPERATOR_ALERT_EMAIL: ""` and no overlay (`k8s/overlays/dev|staging|prod`) patches it, so on Kubernetes the
operator is never emailed about an undeliverable notification; only the ERROR log and the `notification.undeliverable`
metric remain. Nothing fails in CI: it is a silent runtime gap. docker-compose ships `operator@incident-platform.local`
(mailhog) for local runs, so the compose smoke test exercises the configured path and k8s the unconfigured one.

**Decide.** The real address for prod and staging (dev may point at mailhog, which the base already uses for `MAIL_HOST`),
and whether it belongs in the ConfigMap or in a secret (then the Deployment needs a `secretKeyRef`, like `JWT_SECRET`).
Then patch `app-config` in each overlay, for example:

    - target: {kind: ConfigMap, name: app-config}
      patch: |-
        - op: replace
          path: /data/NOTIFICATION_OPERATOR_ALERT_EMAIL
          value: "ops@<real-domain>"

Also state on purpose in the dev overlay if it stays empty. Related: backlog #0-17 (alert on the metric) and #0-22.

---

### 0-27. CLAUDE.md says team isolation is the default, but it is not enforced

**Type:** docs · **Priority:** Low · **Status:** Open

**Problem.** The "Notifications" bullet under "Multi-tenancy invariants" in CLAUDE.md ends with "Between teams of one tenant the
default is also 'no' (backlog #0-12)". It reads as an enforced invariant, but the PRIMARY lookup is still tenant-wide until
#0-12, so in a multi-team tenant it can notify another team's PRIMARY. CLAUDE.md is the file a future session reads first, so
a session could assume team isolation exists and skip the work. `.ai/context/project.md` already states this openly.

**Decide / do.** Reword to "the intended default is also 'no', but it is not enforced yet: the PRIMARY lookup is
tenant-wide (backlog #0-12)". Held back on purpose: CLAUDE.md is edited only on the owner's decision. Remove this item when
#0-12 lands and the sentence becomes true.

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

### 0-29. staging and prod k8s overlays have swapped namespaces

**Type:** bug · **Priority:** Low · **Status:** Open

**Problem.** `k8s/overlays/staging/kustomization.yml` sets `namespace: incident-platform-prod`, and
`k8s/overlays/prod/kustomization.yml` sets `namespace: incident-platform-staging` — the two are swapped relative to
their directory names. Kustomize's top-level `namespace:` field overrides the namespace of every resource it
kustomizes, including each overlay's own `secrets.yml` (which does correctly declare `namespace:
incident-platform-prod` / `incident-platform-staging` matching its directory — that value is simply discarded by the
overlay's own `namespace:` transformer). Found while investigating backlog #0-26; unrelated to it.

**Effect.** Deploying "prod" today would land resources in the `incident-platform-staging` namespace, and vice versa
— the overlay names and the actual namespaces disagree.

**Decide / do.** Confirm this is unintended (not, for example, a deliberate historical rename that the directory
names never caught up to) and swap the two `namespace:` values back to match their directories.

---

## Done

| # | Title | Delivered in |
|---|---|---|
| 0-1 | Escalations notify the `escalateTo` user, falling back to the tenant's PRIMARY, then to the configured addresses (the addresses were removed by #0-18) | PRs #411, #413, #414, #415 |
| 0-18 | Tenant content only reaches members of the tenant: no fallback address, `UNDELIVERABLE` status (V6), content-free rate-limited operator alert by email, a `NOTIFICATION_UNDELIVERABLE` audit event type of its own (`shared`), skipped channels reported, Slack shared-channel broadcast off by default, a Slack id the channel would ignore is no address | PR #416 |
| 0-19 | An oncall-service outage is no longer read as "nobody on call": the two decisive lookups throw, the entry stays PENDING until the lookup has been failing for a retry window (from its first failed lookup), then UNDELIVERABLE; a scheduler run stops after a processing budget | PR #416 |
| 0-10 | `NotificationScheduler` loads a capped page of PENDING entries, oldest first (`notification.scheduler.batch-size`, default 200), and a run stops after a processing budget validated against the ShedLock | PR #416 |
| 0-11 | Service tokens were rejected by `JwtAuthFilter`: per-tenant, per-audience service tokens, `ServicePrincipal`, real-token filter tests, fallback metric, tenant-id validation, escalation client timeouts, correct oncall URL default | PR #413 |
| — | Register a default no-op `TokenRevocationChecker` so incident-service starts (unblocked CI on `main`) | PR #410 |
| — | Key notification idempotency on tenant + escalation level; stop dropping level-2 escalations | PR #411 |
| — | Align README/CLAUDE.md with the code; add LICENSE; scrape auth-service in Prometheus | PR #409 |

Move an item here, with its PR, when it is finished. Items completed before this file existed are
not listed.
