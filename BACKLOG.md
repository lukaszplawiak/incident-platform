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
    `**Fixes:** #0-N · **Escaped from:** <stage>` on the same line, where `<stage>` is `review-<dimension>` when
    the panel missed it, or the pipeline stage that let it in (`ready`, `queue`, `pick`, `plan`, `implement`,
    `acceptance`, `ship`); the audits count these (the pipeline audit, backlog #0-121, by the date the marker
    was added), but only a marker that reached `main` in a PR the owner opened (backlog #0-129). Factory guards
    fails a line with `**Escaped from:** <stage>` and no `**Fixes:** #0-<digits>` before it (backlog #0-128).
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
| [0-69](#0-69-the-snyk-dependency-scan-fails-on-every-run-so-a-new-finding-changes-nothing) | The Snyk dependency scan fails on every run, so a new finding changes nothing | ci | High | Open |
| [0-70](#0-70-built-images-are-not-scanned-and-no-sbom-is-produced) | Built images are not scanned, and no SBOM is produced | ci | Low | Open |
| [0-71](#0-71-kubernetes-and-compose-images-use-mutable-tags-and-some-are-not-tracked-by-renovate) | Kubernetes and compose images use mutable tags, and some are not tracked by Renovate | tech-debt | Low | Open |
| [0-72](#0-72-docker-compose-publishes-every-port-on-all-interfaces-with-default-credentials) | docker-compose publishes every port on all interfaces, with default credentials | tech-debt | Low | Open |
| [0-73](#0-73-swagger-ui-and-the-openapi-document-are-public-in-every-profile) | Swagger UI and the OpenAPI document are public in every profile | tech-debt | Low | Open |
| [0-74](#0-74-no-way-to-report-a-vulnerability-privately) | No way to report a vulnerability privately | docs | Low | Open |
| [0-75](#0-75-the-ingress-has-no-tls) | The Ingress has no TLS | design | Low | Open |
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
| [0-101](#0-101-offboard-a-tenant) | Offboard a tenant | design | Medium | Open |
| [0-102](#0-102-the-pause-of-suspended-tenants-background-work-at-scale) | The pause of suspended tenants' background work at scale | performance | Low | Open |
| [0-105](#0-105-the-email-channels-worst-case-send-is-not-checked-against-the-scheduler-lock) | The email channel's worst-case send is not checked against the scheduler lock | performance | Low | Open |
| [0-106](#0-106-kubernetes-has-no-observability-stack) | Kubernetes has no observability stack | design | Medium | Open |
| [0-107](#0-107-no-alert-reads-log-content) | No alert reads log content | design | Low | Open |
| [0-108](#0-108-architecture-tests-archunit-for-the-invariants-in-claudemd) | Architecture tests (ArchUnit) for the invariants in CLAUDE.md | ci | Medium | Open |
| [0-110](#0-110-maven-enforcer-allowed-repositories-and-pinned-plugin-versions) | Maven Enforcer: allowed repositories and pinned plugin versions | ci | Low | Open |
| [0-111](#0-111-openapi-contract-diff-on-pull-requests) | OpenAPI contract diff on pull requests | ci | Low | Open |
| [0-112](#0-112-mutation-testing-on-the-classes-a-pr-changes) | Mutation testing on the classes a PR changes | ci | Low | Open |
| [0-115](#0-115-rewrite-the-review-rules-as-invariants) | Rewrite the review rules as invariants | docs | Medium | In progress |
| [0-116](#0-116-the-devcontainer-firewall-does-not-bind-code-an-agent-runs) | The devcontainer firewall does not bind code an agent runs | design | High | Open |
| [0-117](#0-117-the-escalation-level-bound-is-hard-coded-in-three-services) | The escalation level bound is hard-coded in three services | tech-debt | Low | Open |
| [0-169](#0-169-a-factory-ops-call-is-not-bound-to-the-command-the-workflow-named) | A factory-ops call is not bound to the command the workflow named | security | Medium | Open |
| [0-171](#0-171-a-seed-bugs-run-that-measured-nothing-leaves-no-record) | A `/seed-bugs` run that measured nothing leaves no record | ci | Low | Open |
| [0-172](#0-172-the-defect-seeder-writes-and-commits-with-no-write-scope) | The defect-seeder writes and commits with no write scope | security | Low | Open |
| [0-174](#0-174-seed-bugs-cannot-start-from-its-own-command-and-reads-strings-from-factory-ops) | `/seed-bugs` cannot start from its own command, and reads strings from factory-ops | ci | High | In progress |
| [0-175](#0-175-the-other-workflows-cannot-start-from-their-commands-and-read-untyped-factory-ops-answers) | The other workflows cannot start from their commands, and read untyped factory-ops answers | ci | Medium | Open |
| [0-133](#0-133-incidentresolvedevent-carries-no-openedat) | `IncidentResolvedEvent` carries no `openedAt` | tech-debt | Low | Open |
| [0-134](#0-134-diff-cover-is-installed-by-version-without-hashes) | `diff-cover` is installed by version, without hashes | ci | Low | Open |
| [0-135](#0-135-kubectl-in-ci-is-downloaded-unverified) | kubectl in CI is downloaded unverified | ci | Low | Open |
| [0-136](#0-136-the-security-review-rules-have-no-rule-for-the-ci-supply-chain) | The security review rules have no rule for the CI supply chain | docs | Low | Open |
| [0-139](#0-139-the-hpas-scale-the-jvm-services-on-memory) | The HPAs scale the JVM services on memory | design | Low | Open |
| [0-140](#0-140-the-services-pods-are-not-spread-over-zones-nor-rebalanced-after-scale-down) | The services' pods are not spread over zones, nor rebalanced after scale-down | design | Low | Open |
| [0-143](#0-143-the-images-do-not-use-a-cds-or-aot-cache) | The images do not use a CDS or AOT cache | design | Low | Open |
| [0-151](#0-151-local-actions-are-called-by-path-not-by-githubs-self-repository-syntax) | Local actions are called by path, not by GitHub's self-repository syntax | ci | Low | Open |
| [0-152](#0-152-refresh-the-snyk-cli-signing-key-before-2027-01-06) | Refresh the Snyk CLI signing key before 2027-01-06 | ci | Medium | Open |
| [0-153](#0-153-decide-which-paths-need-the-owners-review-before-auto-merge) | Decide which paths need the owner's review, before auto-merge | design | Medium | Open |
| [0-155](#0-155-a-scan-that-cannot-run-on-main-alerts-no-one) | A scan that cannot run on `main` alerts no one | ci | Medium | Open |
| [0-156](#0-156-secrets-are-scanned-only-after-the-push) | Secrets are scanned only after the push | security | Medium | Open |
| [0-158](#0-158-spring-boot-35-is-out-of-open-source-support-decide-the-move-to-4x) | Spring Boot 3.5 is out of open-source support; decide the move to 4.x | design | High | Open |
| [0-159](#0-159-security-version-overrides-in-the-root-pom-are-bumped-and-checked-by-hand) | Security version overrides in the root POM are bumped and checked by hand | ci | Medium | Open |
| [0-167](#0-167-no-hook-of-the-owners-session-refuses-gits-writing-options) | No hook of the owner's session refuses git's writing options | security | Medium | Open |

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

**Type:** bug · **Priority:** Medium · **Status:** Open, deferred to be done together with #0-35 (found by code reading, not run)

**Problem.** notification-service's `IncidentAckClient` calls `PATCH /api/v1/incidents/{id}/status`
with a service token (ACK via Slack). That endpoint has `@PreAuthorize("hasRole('RESPONDER') or
hasRole('ADMIN')")` and dereferences `principal.userId()`. After backlog #0-11 the token
authenticates, but as a `ServicePrincipal` with `ROLE_SERVICE`, so the call is answered with 403; and
`@AuthenticationPrincipal UserPrincipal` would be `null` for a service caller.

**Decision needed.** How a service acts on behalf of a user: a dedicated service endpoint that takes
`acknowledgedBy` from the request, or propagating the user's identity. Then add a test that sends a
real service token to this endpoint.

**Found in the analysis of 2026-10-15** (`/research`, not run):
- The path is unreachable today: no message carries the Acknowledge button since #0-21, and #0-35 brings it back.
  So this is a precondition of #0-35, deferred to be done with it (the owner's decision): the OAuth install may
  change how a Slack user maps to a platform user, which decides the answer for an unmapped one.
- `UpdateStatusCommand` has only `status` and `comment`: the `acknowledgedBy` the client sends is dropped
  silently, and the actor would be the principal; `principal.userId()` would also be a 500 (NPE) for a service
  caller, past the 403.
- `SlackActionService` invents the actor for a Slack user with no on-call mapping
  (`UUID.nameUUIDFromBytes(slackUserId)`), so history and audit would name a user who does not exist.
- Recommended direction: a trusted subsystem, not identity propagation (a click carries no platform token, and a
  token minted for the user would be an impersonation capability). An internal `ROLE_SERVICE` endpoint in
  incident-service for the acknowledgement only, the pattern of auth-service's `/api/v1/internal/...` endpoints
  (tenant from the token's claim, `hidden = true`), with a required `acknowledgedBy`, the source recorded as Slack
  and the relay in the audit; an unmapped Slack user is refused and counted, not given an invented id; tests with a
  real service token (accepted), a user token and another audience (refused). Widening `PATCH /status` to services
  was rejected: it would give a service token every transition and put an on-behalf-of field on a user endpoint.

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

**Approach.** (B), together with #0-15.

**Requires #0-15.** The button's path ends in `IncidentAckClient`, which incident-service refuses today (403, and
its `acknowledgedBy` is dropped): with the button back and #0-15 open, every click would fail silently. The two
are done together; #0-15 records the analysis and the recommended endpoint.

**When.** Before the first production tenant uses Slack (the owner's decision, 2026-10-15): acknowledging from Slack
is part of the offer from the start, so this and #0-15 are done before anyone connects Slack in production. It used
to read "when Slack is actually used by tenants", a moment nothing in the platform would signal. Until then
incidents are acknowledged in the app.

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

**Type:** bug · **Priority:** Low · **Status:** Done, PR #480 (built by hand after the autopilot blocked it, draft PR #477). Kept in
full for the decision history; ADR-0026 records the result.
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

**Type:** ci · **Priority:** High · **Status:** Open (found in the 2026-09-30 infrastructure security audit; updated
2026-10-20)

**Problem.** `snyk test --severity-threshold=high` reports 42 issues (run of 2026-09-30; 37–45 on earlier runs), so the
dependency job is red on every push to `main` and every weekly run. A red job that is always red carries no signal: a
new critical CVE would not change its status. OWASP Dependency-Check, by contrast, is green.

The existing `.snyk` ignores are not a sound baseline either. The Tomcat entries (comment at `.snyk:22`, reasons at
lines 30, 38 and 125; all removed by #0-157) justify the risk with "All endpoints require JWT authentication. No unauthenticated access",
which is false: login, health, Swagger (#0-73) and the Slack webhook are public, and ingestion-service takes API keys,
not JWTs. It would not be a mitigation even if it were true, since a Tomcat CVE is reached at the HTTP layer, before
any Spring Security filter runs. The same false claim was in `owasp-suppressions.xml`'s DOMPurify note, corrected
on 2026-10-01.

**Approach.** Triage each finding: upgrade where a fixed version exists (overrides go in the root `pom.xml` properties
with the CVE named, per CLAUDE.md), suppress in `.snyk` with a reason and an expiry where the vulnerable code is not
reachable, until the job is green; then keep it green. Re-check every existing `.snyk` ignore the same way and rewrite
the Tomcat reasons on what actually limits exposure (which Tomcat feature the CVE needs and whether the services use
it), or drop the ignore where nothing does.

**State on 2026-10-20 (run 39431317669, the first authenticated scan after #0-154).** 50–58 issues per project
(eight projects). Tomcat is #0-157 (fixed by a `tomcat.version` override, its four `.snyk` ignores removed); what Snyk fixes only through Spring Boot 4 is #0-158. Fixed within the current
line, by a version override:

| Dependency | Now | Fixed in | Issues |
|---|---|---|---|
| `bcprov-jdk18on` | 1.84 | 1.86 | 15, among them Critical Improper Certificate Validation (`-18512779`, `-18519194`) and Improper Input Validation (`-18512810`); Renovate PR #402 offers 1.85 only |
| `postgresql` | 42.7.12 | 42.7.14 | Not Failing Securely (`SNYK-JAVA-ORGPOSTGRESQL-20571163`) |
| `flyway-core` | 11.7.2 | 11.8.1 | per Snyk's upgrade advice |
| `jackson-databind` / `jackson-core` | 2.21.5 | 2.21.7 | resource exhaustion, DoS |
| `micrometer-core` | 1.15.12 | 1.16.7 | CRLF Injection, memory not released |
| `jaxb-core` | 4.0.9 | Snyk: only through Boot 4 (`spring-boot-starter-data-jpa` 4.0.0, #0-158); check whether a later 4.0.x `jaxb-core` override works with Hibernate 6 | XXE (`SNYK-JAVA-ORGGLASSFISHJAXB-20883142`) |

The compression libraries kafka-clients brings (`zstd-jni` 1.5.6-4, `snappy-java` 1.1.10.5, `at.yawk.lz4:lz4-java`
1.10.1; 11 issues) are separate artifacts, not shaded like the old `org.lz4` one: check whether an override works
with kafka-clients 3.9.2 and whether any producer enables compression. OWASP Dependency-Check is green on the same
`main` although Snyk reports Critical issues in `bcprov`: find out why (another database, a suppression, CPE
matching) before a green OWASP run is read as clean.

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
  `busybox:1.36`, and since #0-42 `axllent/mailpit` in `k8s/overlays/dev/mailpit.yml`, whose tag
  `check-k8s-mail.rb` requires to equal compose's: a compose-only Renovate PR fails that check until the manifest is
  bumped by hand (ADR-0026).
- `docker/docker-compose.yml` uses `:latest` for `provectuslabs/kafka-ui`, `danielqsj/kafka-exporter` and
  `dpage/pgadmin4` (`grafana/grafana` was pinned to `13.2.3` in #0-94 step 2, as it reads every tenant's logs).

**Approach.** Pin every third-party image to a version tag (and a digest where Renovate can keep it current), enable
Renovate's `kubernetes` manager for `k8s/**/*.yml`, and deploy service images by immutable tag (the commit SHA) or
digest once a registry exists. Group an image that compose and k8s both run (`axllent/mailpit`, and Kafka once its
versions agree) across the two managers in one `packageRules` entry, so both files move in one PR.
Since #0-29, `.github/scripts/check-k8s-environments.rb` refuses an untagged or `latest` image in the prod render
and a service tagged with an environment's name, but accepts any other tag (`1.0.0` included), so it does not
make prod immutable; once services deploy by digest, tighten its rule 3 to require a digest for them (its
mutation test already has a digest case).

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
(`DB_POOL_SIZE` in `app-config`; auth-service hard-codes 5). The HPAs own the replica counts and each overlay sets
their ceilings (since #0-137, which kept the ceilings as they were): staging keeps base's 3 for auth-service and
incident-service and 2 for each of the other four, which is 70 connections; prod raises incident-service to 5 (80;
ingestion-service's 5 adds none, it has no database); dev caps every HPA at 1 (30). If every Deployment rolls at the same time, each adds one
surge pod (`maxSurge: 1`): 100 in staging, 110 in prod. (Written while #0-29 had swapped the two overlays, as
"staging 110"; corrected when #0-29 was fixed: prod is the one with the most.) Postgres runs with the default `max_connections` of 100. Since #0-78 the services' role is not a
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

### 0-133. `IncidentResolvedEvent` carries no `openedAt`

**Type:** tech-debt · **Priority:** Low · **Status:** Open (found while fixing #0-119, 2026-10-15)

**Problem.** postmortem-service needs when the incident opened (the `postmortems` row, the Gemini prompt),
but the event has only `durationMinutes` (whole minutes, `Duration.toMinutes()` of `createdAt` to
`resolvedAt` in incident-service) and `occurredAt`. Since #0-119 the consumer derives
`openedAt = occurredAt - durationMinutes`, so the opening is off by up to 59 s, and `occurredAt` is the
time `IncidentEventPublisher` built the event, not the incident's `resolvedAt`. The consumer's
`openedAt` branch exists, but no producer fills it.

**Approach.** Add `openedAt` (the incident's `createdAt`) to `IncidentResolvedEvent` in `shared` and set
it in `publishResolved`; postmortem-service keeps the derived value as the fallback for events already
in the topic or the outbox. A change to `shared` changes all seven services (CLAUDE.md); check every
consumer of the record (none of them should need more than recompiling).

---

### 0-134. `diff-cover` is installed by version, without hashes

**Type:** ci · **Priority:** Low · **Status:** Open (found while fixing #0-76, 2026-10-15)

**Problem.** "Build, Test & Coverage" in `ci.yml` runs `pip install 'diff-cover==10.6.0'`: the version is
pinned, but neither the wheel nor its dependencies are checked, and the dependencies (`Jinja2`, `pluggy`,
`chardet`, `Pygments`) are resolved from PyPI at install time, so a new release of any of them changes
what runs with no change in this repository. README cites it as pinned beside the Snyk CLI (#0-61), which
is checked against a SHA-256, and kubeconform now is too (#0-76); `diff-cover` is the one tool CI installs
at a weaker level. The job runs a PR's own code with a read-only token and no secrets, so the reach is a
coverage gate that could be made to pass, not a credential.

**Approach.** A lock file with every package's hashes (`pip-compile --generate-hashes`, or `uv pip
compile`) committed under `.github/`, installed with `pip install --require-hashes --no-deps -r`; Renovate
can update a pip-compile file and its hashes together (`pip-compile` manager), so the bump need not be by
hand (as for the release-asset tools since #0-147, ADR-0033). Decide whether that manager is turned on for `.github/`.

---

### 0-135. kubectl in CI is downloaded unverified

**Type:** ci · **Priority:** Low · **Status:** Open (found in the review of #0-76, 2026-10-15)

**Problem.** The k8s job (`validate-k8s-manifests` in `ci.yml`) installs kubectl with
`azure/setup-kubectl` (v4.0.1, pinned by SHA), which downloads `dl.k8s.io/release/v<version>/bin/linux/amd64/kubectl`
with `toolCache.downloadTool` and caches it without checking a checksum or a signature (`src/run.ts` at that
SHA). Since #0-76 its version is pinned, but nothing checks that the binary is the one Kubernetes released.
kubectl renders the manifests into `/tmp/*-rendered.yml`, and the checks after it read those files: #0-63's
"staging and prod set no Spring profile", #0-42's mail check and kubeconform itself, so a replaced kubectl could
make them all pass, the vector #0-76 closed for kubeconform. Same reach as #0-76: a read-only token and no
secrets in that job.

**Approach.** Kubernetes signs its release binaries with Sigstore: `kubectl.sig` and `kubectl.cert` sit next
to the binary on `dl.k8s.io` (checked for v1.36.5: the certificate is issued by `sigstore.dev` to
`krel-staging@k8s-releng-prod.iam.gserviceaccount.com`, OIDC issuer `https://accounts.google.com`). Download
kubectl directly instead of through the action, and verify it with `cosign verify-blob --certificate
--signature --certificate-identity --certificate-oidc-issuer` before it runs. cosign itself then has to be
installed pinned and checked (`sigstore/cosign-installer` by SHA, or the binary with its published checksum),
and the expected identity checked against the Kubernetes release documentation rather than taken from one
certificate. The `.sha256` next to the binary is from the same host, so on its own it is the weaker check
ADR-0021 refused for Snyk.

**Also (review round 2 of #0-166 PR 1, 2026-10-25).** The runtimes' setup actions have the same gap: `setup-java`,
`setup-python` and, since #0-166 (ADR-0040), `ruby/setup-ruby` (all pinned by SHA) download a prebuilt runtime and
verify nothing (`setup-ruby` v1.335.0's `downloadAndExtract`: download, then `tar -xz`), and every check of their
jobs runs on it. Ruby's builds (`ruby/ruby-builder`) publish no signature; decide per runtime what can be checked
(a published digest, an attestation) and what stays the action's word, in the same pass as kubectl.

---

### 0-136. The security review rules have no rule for the CI supply chain

**Type:** docs · **Priority:** Low · **Status:** Open (found in the review of #0-76, 2026-10-15)
**Autopilot:** human-only · **Risk:** low · **Complexity:** low · **Depends on:** —
**Touches:** `.ai/rules/review/security.md`

**Problem.** ADR-0021 (Snyk CLI) and ADR-0027 (the k8s job's inputs) decide that a tool CI downloads is
pinned and checked before it runs, and ADR-0027 says how a reviewer recognises a violation (a tool fetched
from `latest` or without a checked checksum or signature, an unpinned validation input). No rule in
`.ai/rules/review/security.md` says so: SEC-50 is about the project's dependencies and their CVE suppressions
(`owasp-suppressions.xml`, `.snyk`), not about tools a workflow downloads. The security reviewer of
#0-76 reported it as a rule gap, so a change that reintroduced `releases/latest` could be found but not
cited as blocking.

**Approach.** The owner adds one rule (`.ai/rules/` is human-owned), written as #0-115 asks, e.g. "A tool or
data file a workflow downloads is pinned (version, commit or digest) and checked (checksum from two
channels, or a signature) before it runs; ADR-0021, ADR-0027. Check: every new `curl`/`wget`/install in
`.github/` names a version and verifies it." Open gaps it would flag today: #0-134, #0-135.

**Also (found in the review of #0-147, 2026-10-17).** ADR-0033 replaced ADR-0021's two channels with a check
against the publisher (attestation, signature, checksums file or digest), so the rule cites ADR-0033 and reads
"checked against its publisher by `check-pinned-tools.sh`, installed only by `install-pinned-tool.sh`". The
security reviewer of #0-147 named two more checks the rule should carry: a privileged trigger (`workflow_run`,
`pull_request_target`) decides by event and repository, never by branch name (the fork's `main` of #0-148,
today only in `.ai/context/security.md` "CI workflows"); and no code from the checkout that a step holding a
secret relies on (an installer script, a tool it runs) runs after an untrusted build step in the same job (the
Snyk CLI's installer ran after `./mvnw compile` until that review).

**Also (found in the security review of #0-109, 2026-10-18).** No rule covers a scanner the scanned change can
reconfigure: a tool that reads its config or ignore file from the checked-out tree (betterleaks' `.gitleaks.toml`
and `.gitleaksignore` at the root, read even when a flag names another directory) lets the pull request it checks
switch its rules off; `secret-scan.sh` refuses both (ADR-0036). Nor one for a workflow command built from a
repository-controlled string (a file name with a newline in an `::error file=...` annotation). Candidate rule: "a
CI check reads its rules from the base branch or from flags, never from the tree it checks".

**Also (found in the review of #0-29, 2026-10-15).** No rule covers the separation of deployment environments
either: which namespace an overlay deploys into and what prod runs. `check-k8s-environments.rb` enforces both in
CI, but a reviewer cannot cite a rule for a change that weakens it. The check also skips resources without
`metadata.namespace`, the cluster-scoped ones: no overlay renders one other than its Namespace today, but a
ClusterRole or ClusterRoleBinding added to one environment would act across the whole cluster unchecked. When the
rule is written, decide whether the check should also refuse every cluster-scoped kind other than Namespace in an
overlay's render; that part touches `.github/scripts/check-k8s-environments.rb` and its test as well.

**Also (the research of #0-161, 2026-10-24).** `.ai/context/security.md` "CI workflows" (privileged triggers,
secrets only in an environment `main` may use, a step holding a secret relying on nothing that ran after the
build, `permissions:` per job) is knowledge without ids, so none of it can be cited as blocking either. #0-161
left CI out of `factory.md` on purpose (a different domain: GitHub's tokens and the supply chain, not agents),
but its form is the precedent: a file read only when the diff touches `.github/workflows/` or `.github/actions/`
(or rules in `security.md`, read by the implementer for every item — which no autopilot item needs, as
`.github/` is Owner-only); decide here which.

---

### 0-139. The HPAs scale the JVM services on memory

**Type:** design · **Priority:** Low · **Status:** Open (found in the analysis of #0-137, 2026-10-15)

**Problem.** Every HPA in `k8s/base/*/service-hpa.yml` scales on CPU (70%) and on memory (80%), and an HPA scales up when
either metric is above its target. The services are JVMs: the heap grows to what the JVM is allowed and is rarely given
back to the operating system, so memory utilisation stays high after the load is gone. The memory metric can then hold a
service at its ceiling, or scale it up on heap growth that is not load; and the ceilings are what #0-79's connection
arithmetic counts. Not measured: no cluster runs these manifests yet.

**Approach.** Measure first, on a cluster under load: whether memory utilisation follows the load at all. If it does
not, scale on CPU only, or on a metric that follows the work (requests per pod, or the Kafka consumer lag through KEDA
for the consumers), and keep memory as a limit (OOM protection), not a scaling signal.

---

### 0-140. The services' pods are not spread over zones, nor rebalanced after scale-down

**Type:** design · **Priority:** Low · **Status:** Open (left open by #0-138, ADR-0029, 2026-10-16)

**Problem.** Since #0-138 each service's pods spread over nodes (`topologySpreadConstraints` on
`kubernetes.io/hostname`, `ScheduleAnyway`), but not over zones: on a cluster with several zones, every pod of a
service may run in one zone, whose outage takes the service down. And the spread is applied only when a pod is
scheduled: an HPA scale-down removes pods without regard to it, and a drain's evicted pods land wherever there is
room, so the placement can drift until the pods are rescheduled. Not observable yet: no cluster runs these manifests.

**Approach.** Once the target cluster and its zones are known: a second constraint on `topology.kubernetes.io/zone`
(`ScheduleAnyway` unless every zone is sure to have capacity), extended in `check-k8s-environments.rb` rule 6; and
decide whether the cluster runs a descheduler (`RemovePodsViolatingTopologySpreadConstraint`) or accepts the drift.

---

### 0-141. One 502 from Maven Central fails the image builds

**Type:** bug · **Priority:** Medium · **Status:** Done, PR #498 (found 2026-10-16 in the smoke tests of 2026-10-15; with
#0-100 and #0-144). Kept in full as the record of the decision and the design it replaced, which ADR-0030 and the code
refer to.

**Problem.** On 2026-10-15 around 19:16 UTC the Docker Compose smoke test failed on three Renovate PRs (#402
oncall-service, #165 auth-service, #194 ingestion-service) and on `main` after #496, each in a Dockerfile's
`dependency:go-offline` on a different artifact, with a 5xx from `repo.maven.apache.org` (502 Bad Gateway); none of
the PRs changed what failed, and a rerun is the only remedy. Two causes. The Maven resolver does not retry a 502:
Maven 3.9.16 ships resolver 1.9.27, whose `aether.connector.http.retryHandler.serviceUnavailable` defaults to
`429,503`. And each of the seven Dockerfiles resolves on its own, with its own cache (`id=<service>-m2`): the smoke
test builds on a fresh runner, so one run fetches nearly the same tree, and the Maven distribution the wrapper
downloads, seven times, seven chances to meet a 5xx.

**Decided (owner, 2026-10-16, third round; ADR-0030).** Maven builds the services once, and the images only
package the jar (R1).
- The Dockerfiles are a JRE `layers` stage: `COPY <service>/target/<service>-*.jar`, exactly one, then
  `jarmode=tools extract --layers --launcher`. The runtime stage takes 4 layered `COPY`s and starts with
  `JarLauncher`; files are root-owned and not writable by `appuser`.
- No Maven, sources or JDK in Docker. `.dockerignore` is an allowlist of those jars.
- build-and-test uploads the jars `verify` tested (`service-jars`, uncompressed, 1 day). The smoke test
  (`needs: build-and-test`) puts them back into `<service>/target/` and is the only job that builds images.
- The per-service `docker-build` matrix is removed: same images, same jars, nothing more proven.
- The Maven repository cache moves from setup-java's `cache: maven` to `actions/cache`.
  - setup-java restores only an exact match of the POMs' hash, so every Renovate PR downloaded the whole tree.
  - The new cache restores by prefix within the month and is saved on a push to `main` only.
- `.mvn/maven.config` retries 429, 500, 502, 503 and 504 (the resolver default is 3 attempts, 5 s apart).
- `make dev-up` runs `make build` first, then `compose up -d --build`.
- #0-100 is fixed with it: a local jar would otherwise carry `application-local.yml` into a local image.
  - The root POM excludes `application-local.*` and `application-secrets.*` from every jar, `shared`'s too.
  - CI plants both in every module, and `check-no-local-config-in-jars.sh` (with `test-…sh`, 14 cases, mutation
    tested) checks each service jar and the `shared` jar nested in it.
- The smoke test runs `break-glass-mfa-reset` without options and expects its refusal (exit 1), which proves
  that `JarLauncher` passes the arguments to `main`.
- `snyk.yml` and `owasp-dependency-check.yml` restore the same Maven cache and never save.

**History of the decision.**
- **First:** retry plus one identical go-offline step in every Dockerfile, into a shared locked cache (A+B1').
  Measured on an empty builder: 245 → 237 s for the 7 images, one resolve instead of seven, builder cache
  9.7 → 6.5 GB.
- **Review r1 found** that its `/m2` copy is a 670 MB layer per image, which docker-build's
  `cache-to: type=gha,mode=max` would export into the 10 GB Actions cache.
- **Second (X):** keep Maven in the Dockerfile, but let CI replace a `FROM scratch AS jar` stage with
  build-and-test's jar.
  - Parts: `build-contexts`, a compose override, a contract checker, and a job building from source on
    `infra` PRs and `main`.
  - It was implemented and reviewed, then replaced before merge. That job downloaded from Central again on every
    push to `main` and on most Renovate PRs. The per-service matrix duplicated the smoke test, and each matrix
    runner downloaded all 800 MB of jars.
  - X's reason, a fresh clone without a JDK, does not hold: Java 21 is a prerequisite.
- **Rejected throughout:**
  - Jib: no Dockerfiles, which the checks and the hardening are written against; reconsider with the registry,
    #0-71.
  - Buildpacks: builder images of several GB.
  - A separate `package -DskipTests` job: two compilations.
  - A repository proxy.

**Measured** (locally, R1):
- `./mvnw clean package -DskipTests` takes 13 s, and the 7 images build in 13 s.
- Each image's build context is only its jar (about 120 MB).
- Layers (incident-service): dependencies 121 MB, loader 0.7 MB, snapshot dependencies 4 kB, application 0.7 MB.
  A code change replaced only the application layer.
- All 7 services are healthy. No `application-local` is in any jar or image, and CI's check catches a planted
  one. `docker compose run --rm auth-service break-glass-mfa-reset` reaches the command (its refusal, exit 1).
- No jar fails the build, and so do two.
- CI, measured on PR #498 (run 38891947846, all seven services) against `main`'s run 38862847045:
  - wall clock: 10 min 16 s instead of 9 min 46 s;
  - runner time: 11 min 41 s instead of 32 min 40 s;
  - the smoke test: 3 min 15 s instead of 6 min 08 s;
  - build-and-test: +44 s, from a first run that restored no cache (compile 37 s instead of 15 s, one download
    from Central).
- A change to one service or to `k8s/` alone now takes about 9.5 min instead of about 6 min 12 s (PR #497),
  because the smoke test follows build-and-test (ADR-0030).

---

### 0-143. The images do not use a CDS or AOT cache

**Type:** design · **Priority:** Low · **Status:** Open (left open by #0-141, ADR-0030, 2026-10-16)

**Problem.** Since #0-141 every image runs the jar extracted into Spring Boot's layers, the layout Spring Boot's
CDS support (and Java 24+'s AOT cache) is built on, but no image creates or uses an archive: each start loads and
links every class again. Start-up time matters for the HPAs' scale-up and for a rollout; not measured.

**Approach.** Measure the start-up of one service first. If worth it: a training run in the `layers` stage
(`-XX:ArchiveClassesAtExit=application.jsa -Dspring.context.exit=onRefresh`) and `-XX:SharedArchiveFile` in the
`ENTRYPOINT`; the training run starts the Spring context, so it must not need the database, Kafka or secrets
(check what each service's refresh touches), and the JVM flags of the training run and of the runtime must match.

---

### 0-151. Local actions are called by path, not by GitHub's self-repository syntax

**Type:** ci · **Priority:** Low · **Status:** Open (found in the review of #0-147, 2026-10-17; waits on actionlint)

**Problem.** The workflows call `.github/actions/restore-maven-caches` and `save-maven-caches` as
`uses: ./.github/actions/...`. GitHub's `$/` self-repository syntax (July 2026) names the repository the workflow
runs from rather than a path in the checkout, and zizmor's `self-repository` audit asks for it. The pinned
actionlint (v1.7.12) predates the syntax and would refuse it, so `.github/zizmor.yml` turns the audit off for the
whole repository (#0-147): a new local action called by path is not flagged either.

**Approach.** When Renovate bumps actionlint (#0-147, ADR-0033) to a release that accepts `$/`: switch the five
`uses: ./.github/actions/...` lines (zizmor's auto-fix), check that `check-maven-caches.rb` (the tenth structural
rule) still recognises the two actions under the new form, and delete the `self-repository` entry from
`.github/zizmor.yml`. Touches `.github/`, so human-only.

---
### 0-152. Refresh the Snyk CLI signing key before 2027-01-06

**Type:** ci · **Priority:** Medium · **Status:** Open (found in the review of #0-147, 2026-10-17; due before 2027-01-06)

**Problem.** `check-pinned-tools.sh` checks the Snyk CLI pin against `sha256sums.txt.asc`, signed by the key
committed as `.github/keys/467717A30B2B4658415975629691DA64D0025194.asc` (ADR-0033). That key (the primary,
which signs) and its encryption subkey expire on 2027-01-06. From that day gpg reports every signature by it as
`EXPKEYSIG`, the checker refuses it, and Lint Workflows is red on every pull request and on `main`, where
`main-guard.yml` then opens the `autopilot-stop` issue; both `snyk.yml` jobs, which run the same check before
installing the CLI, stop too, so no Snyk scan runs. The checker warns (`::warning` on a green job) only from 30
days ahead.

**Approach.** In December 2026: take the key from the snyk/cli repository
(`help/_about-this-project/snyk-code-signing-public.pgp`) and from keys.openpgp.org; if Snyk extended it (same
fingerprint, later expiry), replace the armor in the committed file; if Snyk rotated it, add the new key's file and
change `verify=gpg:` in `snyk.yml`, after checking the new fingerprint against docs.snyk.io. Either way the three
sources must agree, as on 2026-10-17. Run `check-pinned-tools.sh` with gpg before merging. Touches `.github/`, so
human-only.

---

### 0-153. Decide which paths need the owner's review, before auto-merge

**Type:** design · **Priority:** Medium · **Status:** Open — noted 2026-10-16 (PR #499) in #0-113, moved here from it and widened by its security review (2026-10-18); a precondition of phase 3 (auto-merge, `docs/ai-factory.md`)
**Autopilot:** human-only · **Risk:** high · **Complexity:** low · **Depends on:** —

**Problem.** Since #0-113 (ADR-0034) the code owner review of `.github/CODEOWNERS` is enforced, so what it does not
name is what a PR can change on green checks alone, and with auto-merge without anyone reading it. Three points are
known.

**1. `.dockerignore` (noted 2026-10-16, PR #499).** Since #0-141 the root
`.dockerignore` is the allowlist that keeps sources, `.env` files and `application-local.yml` out of every
image's build context (SEC-33). An agent may change it: a narrowing breaks the image build (the smoke test is
red), and a widening reaches an image only together with a Dockerfile `COPY`. Both changes go to the k8s
reviewer (PR #499) and to the security reviewer, who answers `NEEDS_HUMAN` for a widening; no script checks
it (`check-no-local-config-in-jars.sh` checks the jars, not the images). That is enough while the owner
reads every PR before merging. Once a PR can merge without that read, decide whether `.dockerignore` and
`*/Dockerfile.dockerignore` go on the build-config list of `.ai/rules/protected-paths.md` (the autopilot then
stops on any change to them, and Factory guards rule 6 asks for the owner's approval). Not done in #499: the
list is for what decides how the build is verified, and today a reviewer and the owner catch a widening.

**2. `.ai/context/` and `.ai/decisions/` (security review of #0-113, 2026-10-18).** Every agent and reviewer reads
them as authority (the architecture, security and CI rules, the decided patterns), but neither is in CODEOWNERS nor
on the protected list. A PR that changes only Markdown skips the build (`detect-changes`), so a bot PR rewriting
`.ai/context/security.md`, or adding an ADR that "decides" a pattern the reviewers then accept, merges on green.
Harmless while the owner reads every PR (shadow mode); decide before phase 3 whether they become code-owned (the
autopilot's own knowledge updates, `.ai/rules/implementation.md`, then wait for the owner) or stay open with
another check.

**Also (review round 2 of #0-166 PR 1, 2026-10-25).** The build-config list (`mvnw`, `mvnw.cmd`, `.mvn/`, and
`.ruby-version` since #0-166) is code-owned today only through the shadow-mode catch-all (point 3): `check-protected-paths.sh` asks
CODEOWNERS lines of the owner-only list alone, and Factory guards rule 6 is what holds the build-config list. When
phase 3 removes `*`, decide whether those paths get lines of their own.

**3. The shadow-mode catch-all (#0-113, 2026-10-18).** Until phase 3 `.github/CODEOWNERS` opens with `* @lukaszplawiak`,
so every PR needs the owner's approval (the machine account's own approval would otherwise count). Phase 3
removes that line; what this item decides is what stays owned once it is gone.

**Acceptance criteria.**
AC1. Each path above is either code-owned (CODEOWNERS, and `.ai/rules/protected-paths.md` when an agent must not
write it) or the reason it is not is recorded here and in `docs/ai-factory.md`.
AC2. `docs/ai-factory.md` phase 3 names the decision.

---

### 0-155. A scan that cannot run on `main` alerts no one

**Type:** ci · **Priority:** Medium · **Status:** Open — found in the analysis of #0-154 (2026-10-18)
**Autopilot:** human-only · **Risk:** low · **Complexity:** medium · **Depends on:** —

**Problem.** From 2026-10-18 06:16 every run of `snyk.yml`'s dependency scan on `main` ended in `Authentication
error (SNYK-0005)`: `SNYK_TOKEN` (last set 2026-07-19) no longer authenticates, most likely a Snyk token expired
after 90 days. Until then the same job failed on its findings (46–47 issues, #0-69), so a red Snyk run on `main` was
normal and the switch from "found issues" to "could not scan at all" changed nothing anyone sees. Nothing watches
it: `main-guard.yml` reacts to the CI workflow only, the code scan hides its errors behind `continue-on-error: true`
(its 403, #0-68), and so does the OWASP scan, whose NVD outage or bad key would look the same as a clean run. The
platform then has no dependency scan while every signal says what it always said.

**Approach (to analyse).** Tell a tool that could not run from one that found something: `snyk test` exits 1 on
findings and 2 on an error (authentication, configuration), 3 when it finds no project; dependency-check reports
an NVD failure in its log. A scan step could fail hard only on the error case, and something must then reach the
owner: `main-guard.yml` widened to the scan workflows for that case only (it would stop the autopilot, which may be
too much for a scan), or an issue of its own, opened by the scan's run on `main` or `schedule` and closed by its
next good run. Also, as #0-152 does for Snyk's signing key: record the token's expiry and warn ahead of it. Whatever
is chosen, the #0-69 and #0-68 failures must not raise it every week, or it says nothing either.

**Acceptance criteria.**
AC1. A scan on `main` (push or schedule) that cannot authenticate or reach its database reaches the owner in a way
that does not depend on opening the Actions tab.
AC2. A scan that only finds issues (#0-69) does not.

---

### 0-156. Secrets are scanned only after the push

**Type:** security · **Priority:** Medium · **Status:** Open — found in the analysis of #0-109 (2026-10-18)
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** #0-109

**Problem.** The Secret Scan job (#0-109, ADR-0036) and GitHub's push protection both run after the push. This
repository is public, so a secret on a pushed branch is published before either says anything: the finding
means rotating it, not deleting it. Nothing scans before the push: no git hook in the repository
(`.githooks`, `core.hooksPath`: none), and the autopilot's push path (`.claude/hooks/guard-git-push.sh`, the
shipper) checks branches, not content. Also open: an allow comment (`gitleaks:allow`) passes whatever its line
holds, its reason unchecked.

**Approach.** The same pinned betterleaks before the push: in the factory's push path (the session hook or the
shipper's script, `.claude/`, owner-only) on the commits the push sends, and as an opt-in pre-push hook for
developers (`core.hooksPath` to a committed directory), in the devcontainer by default. Decide whether an allow
comment must carry a reason the script checks (like `# not-a-download: <why>`, #0-149).

**Acceptance criteria.**
AC1. An agent's push of a commit holding a secret-shaped string is refused before it reaches GitHub.
AC2. A developer who enables the hook gets the same refusal, with the same rules and binary as CI.
AC3. README "Infrastructure Hardening" lists the control and drops the gap.

---

### 0-158. Spring Boot 3.5 is out of open-source support; decide the move to 4.x

**Type:** design · **Priority:** High · **Status:** Open — found with #0-157 (2026-10-20)
**Autopilot:** human-only · **Risk:** high · **Complexity:** high · **Depends on:** —

**Problem.** The platform runs Spring Boot 3.5.16 (root `pom.xml`), whose open-source support ended 2026-06-30
according to `.snyk` (to verify on spring.io). Snyk names the 4.x line (Spring Boot 4, Framework 7, Security 7,
spring-kafka 4) as the only fix for a growing share of the findings: `spring-webmvc` `SNYK-JAVA-ORGSPRINGFRAMEWORK-19267098`
(Critical, Arbitrary Code Injection), `spring-web` (XSS, cookie without `Secure`, two resource exhaustions),
`spring-expression`, `spring-beans`, `spring-security-core` (Timing Attack) and `spring-security-crypto`
(Predictable IV with CBC), `spring-kafka` `-19267472`, Netty via Lettuce, lz4 and zookeeper via
`spring-kafka-test`. Since June every such `.snyk` ignore says "tracked for the Spring Boot 4.x migration", but no
item tracks it.

**Approach (to analyse).** Commercial support for 3.5 against migrating. The migration's reach: Spring Security 7,
Jackson 3, spring-kafka 4 with kafka-clients 4, Hibernate 7, springdoc 3, Testcontainers 2, the starters split in
Boot 4. One parent POM means the services cannot move one at a time: decide one PR or steps (`shared` first,
behind a branch), and what CI proves at each. Record the decision in an ADR.

The migration also removes the root POM's `tomcat.version` override (#0-157), which keeps Tomcat 10.1 patched until
then.

**Acceptance criteria.**
AC1. An ADR decides: the migration with its plan, or a deferral with its reason and a date to revisit.
AC2. If migrating, the steps are items in this backlog, and the `.snyk` ignores citing "the 4.x migration" name them.

---

### 0-159. Security version overrides in the root POM are bumped and checked by hand

**Type:** ci · **Priority:** Medium · **Status:** Open — found by the `/review` of #0-157 (2026-10-22)
**Autopilot:** not-ready · **Risk:** medium · **Complexity:** low · **Depends on:** —

**Problem.** The root `pom.xml` overrides versions the Spring Boot parent manages (`tomcat.version`,
`postgresql.version`, `jackson-bom.version`, ...) to take CVE fixes. Two things nothing checks: (1) Renovate sees
no dependency that uses `tomcat.version` (it only drives the parent's BOM), so a new Tomcat 10.1 release with a
fix reaches us only when a person reads Apache's security page; `postgresql.version` was bumped by hand too (PR
#305). (2) Nothing fails when an override is lower than what the parent manages, so a parent bump can be held back
silently by a stale override (at the move to Spring Boot 4, #0-158, a leftover `tomcat.version` 10.1.x would force
Tomcat 10.1 onto Boot 4).

**Approach (to analyse).** (1) Whether Renovate's maven manager bumps these properties as they are, or a
`customManagers` regex (a `<!-- renovate: depName=... -->` comment before the property, like the CI tools' pins,
#0-147) is needed; limit Tomcat to the 10.1 line (`allowedVersions`). (2) A check in CI that every overridden
property is at least the parent's managed version: `maven-enforcer-plugin` with a custom rule, or a script over
`help:effective-pom` of the parent; consider it with #0-110 (Enforcer in the root POM).

**Acceptance criteria.**
AC1. A new Tomcat 10.1 or PostgreSQL JDBC release opens a Renovate PR for its property.
AC2. A root POM property lower than the version the Spring Boot parent manages fails CI, with the property named.

---

### 0-160. What a reviewer could not verify is never checked

**Type:** design · **Priority:** High · **Status:** Done, PRs #513 (D) and #514 (C) — found in the `/review` of #0-157
(2026-10-22); owner's decision: C + D below; in two PRs (2026-10-23): D first, then C. Kept in full for the decisions
(E2, the allow-list, the read scope) and the spikes
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** —
**Touches:** factory (.claude/workflows/backlog-autopilot.js, .claude/agents/ (a new verifier agent),
.claude/skills/review, review-procedure, ship-record, .claude/settings.autopilot.json, .claude/settings.json,
.claude/hooks/, scripts/factory/run-tests.sh), ci (.github/workflows/factory-guards.yml, .github/scripts/ tests and a
structural check), .devcontainer/init-firewall.sh, rules (.ai/rules/review/_common.md), root (README.md "Infrastructure Hardening", docs/ai-factory.md, an ADR in
.ai/decisions/)

**Problem.** The review agents are read-only on purpose (`readonly-bash.sh`: git only, no Maven, no network), so
every claim that depends on the build or on the outside goes to the verdict's `unverified` list
(`_common.md`). Nothing reads it: in the autopilot `unverified` exists only in the verdict schema
(`backlog-autopilot.js`), and neither the acceptance reviewer, the shipper nor the PR record sees it; in a manual
`/review` only the session closes it, if it does. In the review of #0-157 all six reviewers marked the core of the
change unverified (that Tomcat 10.1.61 exists, fixes the six CVEs and is resolved in every service): checked by the
session, by `dependency:tree`, `verify` and Apache's security page, not by the pipeline. Reviewers also see only
"the test gate passed", not what it produced.

**Decided (owner, 2026-10-22).** Reviewers stay without Maven and network (running `mvn` runs the plugins a
reviewed POM declares; a reviewer reads untrusted text, so a fetch could leak). Instead:
- **D. The test gate's artifacts for the reviewers:** the gate writes what it ran and produced (modules, test
  counts and failures, JaCoCo per module, `dependency:tree` of the affected modules) to a file under the run's work
  directory; the review prompt names it and reviewers read it with Read.
- **C. A verification stage after the panel:** a new agent receives only the panel's `unverified` items (never the
  diff, so the change cannot steer it), closes the local ones from D's artifacts and the external ones with
  WebFetch restricted to an allow-list of domains (to decide: tomcat.apache.org, security.snyk.io,
  repo1.maven.org, nvd.nist.gov, github.com/advisories, docs.spring.io ...), and answers per item: verified (with
  the source), refuted (becomes a blocking finding for the next round) or not verifiable (the item stops as
  `NEEDS_HUMAN`, or the owner accepts it in the PR). The result goes into the PR's review record (`ship-record`);
  the manual `/review` runs the same stage.

**Approach (to analyse).** How the allow-list is enforced (permission rules `WebFetch(domain:...)` in the
verifier's frontmatter/settings, or a PreToolUse hook; `settings.autopilot.json` denies WebFetch for everyone
today), that the verifier has no Bash and no write outside its result, what an item it cannot classify does, and
the cost (one agent per round, skipped when no item is unverified). An ADR records it.

**Decided (owner, 2026-10-23, after the analysis).** The Approach above assumed rules could be set per agent;
Claude Code's documentation says otherwise, and the decisions follow from it:
- **Enforcement — a session-level hook keyed on the agent (E2).** A bare `deny: WebFetch` removes the tool for the
  whole session, subagents included, and no allow rule or hook overrides it; a subagent has no permission rules
  of its own (only `tools`, `disallowedTools`, `permissionMode`, `hooks`); frontmatter hooks do not run in
  `claude -p`; session-level hooks do run in subagents and their input carries `agent_type`. So the bare deny
  goes from `settings.autopilot.json`, and a PreToolUse hook on `WebFetch` lets only the verifier through, only
  to `https` URLs under an allow-listed host and path prefix, without a query string (a `domain:` rule matches
  the host only, so `github.com/search?q=<data>` would pass it; and in `dontAsk` WebFetch's built-in preapproved
  documentation domains need no approval). Rejected: rules only (E1: host-only, every agent, preapproved
  domains); a separate `claude -p` process for the verifier with its own settings (E3: keeps the bare deny, but
  the verdicts live in the workflow's memory and would need a new path to a file, a nested authenticated
  `claude`, and a cost cap outside the agent budget) — the fallback if the first step below fails. The weaker
  layer (a hook instead of an absent tool) is named in the ADR and in README "Infrastructure Hardening".
- **First step of C:** confirm that `agent_type` reaches the hook for an agent the `Workflow` runtime spawns (the
  documentation says so for subagents; Workflow is another path). If it does not, E3. **Done 2026-10-23, positive**:
  a session-level PreToolUse hook logged `agent_type` `"review-docs"` for a Workflow agent with
  `agentType: 'review-docs'`, `"workflow-subagent"` for one without, `"Explore"` for an Agent-tool agent, and none for
  the session's own call. E2 stands (ADR-0037). **Second spike, 2026-10-24** (review round 2): the `verifier` run
  from a Workflow logged `agent_type` `"verifier"`, and its frontmatter `read-scope.sh` refused `README.md` and let
  `.ai/runs/…` through; the documentation promises frontmatter hooks only to agents spawned through the Agent tool,
  an @-mention or `--agent`, so the read scope is also session-level (`read-scope.sh --agent verifier`). Review
  round 3 narrowed it to the files the gate writes (`--name 'gate-r*.json' --name 'dependency-tree-r*.txt'`, no
  file with a second hard link): the rest of `.ai/runs/` holds the branch's test output; the other agents' guards
  are still frontmatter-only (#0-164).
- **The manual `/review` runs the gate too:** `run-tests.sh --working-tree` tests the uncommitted changes (the
  modules of the committed and the uncommitted files), records `dirty: true`, and is refused while an autopilot
  run holds the lock.
- **Allow-list, minimal:** `api.osv.dev/v1/`, `osv.dev/vulnerability/` (OSV aggregates GHSA, NVD and vendor
  advisories with fixed-version ranges), `github.com/advisories/`, `repo1.maven.org/maven2/`, and vendor pages
  by path prefix (`tomcat.apache.org/security-`, `spring.io/security/`). Every host beyond Maven Central and
  GitHub is also opened in the devcontainer firewall, for the whole container: that is why the list is short.
- **D's artifact** (`.ai/runs/<item>/gate-r<round>-<attempt>.json`; `.ai/runs/manual/gate-r1.json` for `/review`): the HEAD, per module the counts of surefire's
  `TEST-*.xml` and the line coverage of `jacoco.csv`, only from reports this run wrote (verify does not clean),
  and a `dependency:tree` file; a report the gate cannot read stops it, since missing numbers would read as
  "0 tests". The acceptance reviewer gets the path too.

**Acceptance criteria.**
AC1. Every reviewer of a round gets the test gate's artifacts for that exact HEAD.
AC2. No `unverified` item of the final round reaches a PR without a verdict (verified with a source, refuted, or
not verifiable), and the PR's review record lists them.
AC3. A refuted item blocks like a blocking finding; a not verifiable one stops the item as `NEEDS_HUMAN` unless the
owner accepts it.
AC4. The verifier fetches only from the allow-list (a fetch outside it is refused, shown by a test of the hook or
rule) and never receives the diff.
AC5. README "Infrastructure Hardening" (AI factory) and `docs/ai-factory.md` describe the stage and its network
access.

---

### 0-161. No review rule covers the AI factory itself

**Type:** design · **Priority:** Medium · **Status:** Done, PR #515 — `ruleGap` of review-security, review-architecture
and review-general in both rounds of the `/review` of #0-160 (2026-10-23/24); owner's decision: B′ below (2026-10-24).
Kept in full for the decisions (the trigger, the architect's exception, the `git show` pre-approval)
**Autopilot:** human-only · **Risk:** high · **Complexity:** low · **Depends on:** —
**Touches:** rules (.ai/rules/review/factory.md (new), security.md, architecture.md, _common.md,
.ai/rules/audit.md, .ai/rules/implementation.md, .ai/rules/protected-paths.md "Who uses it"), factory
(.claude/workflows/backlog-autopilot.js `RULE_FILE_OF`, .claude/agents/architect.md, review-security.md,
review-architecture.md, .claude/skills/review-procedure/SKILL.md, .claude/skills/review/SKILL.md), .ai/decisions/
(ADR-0038), .ai/context/security.md, docs (README.md "Infrastructure Hardening", docs/ai-factory.md, .ai/README.md)

**Problem.** `.ai/rules/review/security.md`, `architecture.md` and `general.md` are about the services' code
(tenant isolation, the security chain, decided patterns); nothing in them covers the factory: which agent has the
network, which hosts the firewall opens, that a hook refuses with exit 2 (any other exit code does not block),
that every agent declares its tools and scopes, that a control's list lives once and Factory guards checks its
copies. A finding needs a rule of its dimension by id to block (`_common.md`), so a real defect in a change to
`.claude/`, `.github/`, `scripts/factory/` or `.devcontainer/` (a hook that fails open) can only be
non-blocking, and reviewers report the gap as `ruleGap` every round.

**Decided (owner, 2026-10-24).** Not in `security.md`/`architecture.md`/`general.md` themselves (the implementer reads
them in full for every item, and no autopilot item can change the factory: protected paths), not in
`.ai/context/security.md` with ids (agents may edit `.ai/context/` — a rule that blocks must not live in a file the
judged change can rewrite, #0-153 — and the ids would map to no rule file), and not a new dimension `factory`
(an agent, a gate and an audit dimension for changes that reach only the owner's manual `/review`, too few for the
audit's ≥3 cases). **B′**: a rule file `.ai/rules/review/factory.md` with ids of the existing dimensions —
`FAC-S…` read by review-security, `FAC-A…` by review-architecture — read only when the diff touches the factory's
paths (one sentence in `security.md` and `architecture.md`; `_common.md` says the two prefixes belong to those
dimensions); `RULE_FILE_OF` in `backlog-autopilot.js` maps `FAC` to `factory`, so audits address the file. Plus a
section "AI factory" in `.ai/context/security.md`, without ids, as knowledge for the reader (why the verifier has
the network, where the allow-list is), like "CI workflows".

**Decided (owner, 2026-10-24, after the research).** The factory's paths are the **Owner-only list** of
`protected-paths.md`, not a list of `factory.md`'s own (a second copy would drift, #0-122; every reviewer reads
that file already). The architect reads every rule file but `factory.md` (`architect.md` said "all": without the
exception AC2 fails for it), and `_common.md`'s "nothing else from `.ai/rules/review/`" names the exception, as do
the two reviewers' definitions and `review-procedure`. `FAC-S01` is the rule of two (an agent reading untrusted
text never both reaches a secret and sends data out), the general case the other rules spell out; a section
"Enforced by checks" names what Factory guards and Lint Workflows already check, like `architecture.md` §4. CI
workflows stay out (`.github/workflows/` rules are #0-136's). The review of this change found that no audit reads
`RULE_FILE_OF` (only `ruleFilesOf`, for the architect's plan): its `FAC` entry is kept so that an `FAC-` id never
goes uncounted, not for the audits; `/review` decides whether `factory.md` applies (Step 2, from the list on
`main`) and tells the two reviewers, and ADR-0038 records the layout. Review round 2 (security, `FAC-S03`, blocking):
the first version pre-approved `Bash(git show *)`, which takes `--output=<file>` in a session with no Bash hook;
`/review` now pre-approves only `git show origin/main:.ai/rules/protected-paths.md`, an unreadable list means the
rules apply, and the report says whether they did; the older `git diff *` and the rules read from the branch are
#0-165.

**Acceptance criteria.**
AC1. A finding on a change to the factory's paths can cite a `FAC-S`/`FAC-A` rule by id and block.
AC2. A change that touches no factory path costs the reviewers, the implementer and the architect nothing new to
read.
AC3. The rules cover at least: network tools per agent and the allow-list's one source, firewall hosts (by IP),
tools and read/write scopes per agent, hooks that refuse with exit 2 on any failure, no secret readable by an agent
that can send, one list per control with its copies checked in CI, fail-closed agent answers, every new stage in
the PR record.
AC4. `review-security` and `review-architecture` are told to read `factory.md` exactly when the diff touches a path
of the Owner-only list (`_common.md`, their definitions, `review-procedure`), and no other agent is.

---

### 0-162. A hook that cannot load its helper lets the call through

**Type:** ci · **Priority:** Medium · **Status:** Done, PRs #516, #517 — found by review-security in round 2 of the
`/review` of #0-160 (2026-10-24); the two hooks #0-160 added are already fixed; owner's decision: C below, in two PRs
(2026-10-24). Kept in full for the decisions (both layers, the spikes, the version floor)
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** —
**Touches:** factory (.claude/hooks/*.sh, .claude/hooks/test-hooks.sh, .claude/settings.json,
.claude/settings.autopilot.json, .claude/agents/*.md (hook entries), scripts/factory/preflight.sh,
.github/scripts/test-preflight.sh, .github/scripts/ (a check of the hook entries), .github/workflows/factory-guards.yml),
.ai/decisions/ (an ADR), docs (README.md "Infrastructure Hardening", docs/ai-factory.md, .ai/rules/review/factory.md
`FAC-S04` if its wording changes, owner)

**Problem.** Every hook in `.claude/hooks/` starts with `. "$(dirname "$0")/_lib.sh"`. When that fails (the file
missing or unreadable), `block` is undefined and the hook ends with an exit code other than 2 — which Claude Code
treats as a non-blocking error: "The action goes ahead" (code.claude.com/docs/en/hooks.md, read 2026-10-24). The
guard then lets through what it exists to stop (a skipped test run, a protected-path write, a push). The same
for any other crash before `block` (an unset variable under `set -u`).
Shown by the research (2026-10-24), each hook copied without `_lib.sh`: `guard-tests.sh` passes `-DskipTests`,
`guard-git-push.sh` a force push to `main`, `guard-write-in-repo.sh` a Write of `.claude/settings.json`, and
`readonly-bash.sh` (13 agents' frontmatter) and `write-scope.sh` let everything through (exit 0);
`factory-ops-bash.sh` exits 1. Two more ways to the same end, found then:
- **`jq` fails** (installed, but exits non-zero — moved here from #0-132 by the owner): `json_get` returns 3 only
  when neither `jq` nor `python3` exists, so a parser that fails gives an empty value, which the Bash hooks answer
  with `[ -n "$cmd" ] || exit 0` (`guard-tests`, `guard-protected-bash`, `readonly-bash`: exit 0, with a `jq` stub
  that exits 5) and on which `factory-ops-bash.sh` crashes under `set -u` (exit 1). The write hooks refuse (an empty
  path is outside the repository). Already against `FAC-S04` ("the input does not parse").
- **The hook does not start** (the script missing, not executable, no `bash`) or times out: no change inside a
  script can catch that. Claude Code has a field for it, `"onFailure": "block"` on a `command` hook: a hook that
  cannot start, exits other than 0 or 2, times out or prints invalid JSON then blocks the call ("Requires Claude
  Code v2.1.295 or later", same page). The devcontainer installs whatever version is current when the image is
  built (`DISABLE_AUTOUPDATER`), and no check requires one; what an older version does with the field is not
  documented, nor whether a subagent's frontmatter hooks honour it.

**Decided (owner, 2026-10-24).** Option C of the research, both layers (a hook that fails exits 2 itself, and
Claude Code blocks a hook that fails), in two PRs:
- **PR 1, the scripts** (no dependency on the Claude Code version): each hook loads `_lib.sh` with
  `|| { echo "BLOCKED: …" >&2; exit 2; }` and sets, before it, a `trap … EXIT` that turns any exit status other
  than 0 or 2 into 2 (verified on bash 3.2 and 5: an unset variable, a missing command at the end, `exit 1`);
  `json_get` tells "no such field" (empty, 0) from "the parser failed" (non-zero), and the hooks refuse on the
  latter; a test per hook without `_lib.sh`, with an unset variable and with `jq` failing; Factory guards fails a
  hook in `.claude/hooks/` without a row in `test-hooks.sh`'s fail-closed table, whose cases (no `_lib.sh`, an
  abort while loading, `jq` failing) fail a hook without the guarded load or the trap — a behavioural check, not
  one of the text. From the review (round 1): `guard-migrations.sh` refuses when git fails on a migration's path,
  and lets the edit through with a warning only without `jq`/`python3` or without its base ref (`FAC-S04` names
  both); `json_get` refuses empty input (jq reads it as "no field"); the python3 fallback is tested too. Round 2:
  `guard-migrations.sh` asks `git ls-tree` (literal pathspec, exact name) instead of `cat-file -e`, which read
  any git failure as "not on the base".
- **PR 2, Claude Code's layer**: `"onFailure": "block"` on every hook entry of both settings and of every agent's
  frontmatter, checked by Factory guards (like `check-web-access.sh` reads the wiring); `preflight.sh` refuses a
  Claude Code older than 2.1.295; Factory guards also fails a hook command wired in either settings file or an
  agent's frontmatter that is not a `.claude/hooks/*.sh` with a row in the fail-closed table (security review of
  PR 1: today only the directory's files are enumerated); a spike first — does a frontmatter hook honour the field, and what does an older
  version do with it — recorded in an ADR, and a negative answer named as a weaker layer (README "Infrastructure
  Hardening", `FAC-A05`).

**PR 1 merged** (PR #516, 2026-10-24). **PR 2 (2026-10-25).** Spikes, each with an entry pointing at a missing
script: on 2.1.310 the field blocks in `claude -p --settings` (B) and in an Agent-tool agent's frontmatter (A); on
2.1.294 (C) it is ignored without a word while the rest of the file still applies. Owner's choices: the check in
Ruby, parsing the YAML and JSON (`.github/scripts/check-hook-entries.rb`, also refusing shell syntax in a hook's
arguments and `disableAllHooks`); the version is the running binary's (`$CLAUDE_CODE_EXECPATH`, else `claude` on
PATH, an unreadable one refuses); Workflow agents' frontmatter left untested, named in ADR-0039 with #0-164.

**Acceptance criteria.**
AC1. Every hook in `.claude/hooks/` exits 2 when it cannot load `_lib.sh`, shown by a test per hook.
AC2. A new hook without that pattern fails Factory guards.
AC3. Every hook exits 2 when it aborts after loading (an unset variable under `set -u`) and when `jq` fails on its
input, shown by a test per hook; `json_get`'s callers never read a parser failure as an empty value.
AC4. Every hook entry in `.claude/settings.json`, `.claude/settings.autopilot.json` and the agents' frontmatter has
`"onFailure": "block"`, and Factory guards fails one without it, or one whose command is not a hook of the
fail-closed table.
AC5. The autopilot does not start on a Claude Code older than 2.1.295 (`preflight.sh`, with a test).
AC6. The spike's result (frontmatter hooks, older versions) is in an ADR; a layer it shows not to hold is named in
README "Infrastructure Hardening".

---

### 0-163. The autopilot workflow's logic has no tests

**Type:** ci · **Priority:** Medium · **Status:** Done, PRs #522, #523 — from review-general, `/review` of #0-160
(2026-10-23/24); owner's decision below. Kept in full for the decisions (Node pinned like Ruby, how the test reads the script)
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** —
**Touches:** factory (.claude/workflows/backlog-autopilot.js, .claude/settings.autopilot.json, .claude/hooks/), ci
(.github/scripts/ (a new test, check-runtime-setup.rb, ci-local.sh), .github/workflows/, .github/actions/setup-node/,
.node-version), .ai/rules/protected-paths.md (owner), .ai/decisions/, docs (CLAUDE.md "Commands", README.md
"Infrastructure Hardening", .ai/context/infrastructure.md)

**Problem.** `backlog-autopilot.js` decides every branch of a run in code — `scopeOf`, `valid`, `openClaims`,
`claimId`, `verifyClaims` (the fail-closed downgrade of an unsourced answer), `refutedFindings`, `unsettledReason`,
the checks of `args` — and none of it is tested: the reviews of #0-160 checked it by reading, and the session by an
ad-hoc script outside the repository. A regression there passes Factory guards.

**Approach (to analyse).** The workflow is a script the runtime evaluates with `agent`, `parallel`, `phase`, `log`
and `args` in scope, not a module: either move the pure functions to a file the workflow and a `node` test both
load (if the runtime allows an import), or evaluate the script's helper section with stubs (what the ad-hoc check
of #0-160 did) — then the test breaks when the section's markers move, which is acceptable if it fails loudly.
Node is on the runner; mutation-check the cases.

**Decided (owner, 2026-10-25, after `/research`).** B + R1, in two PRs. Tests of the helper functions and scenario
tests of the whole run (the checks of `args`, the fail-closed stops), with a fake `agent()`: the workflow runtime
has no import, so a test evaluates the script's text with stubs. Node pinned like Ruby (ADR-0043, PR 1):
`.node-version`, `.github/actions/setup-node`, Node's built-in modules only (no npm); `check-ruby-setup.rb` becomes
`check-runtime-setup.rb` with Ruby and Node as rows of one table; `ci-local.sh` copies the same Node from the
official `node` image (not a tarball through `install-pinned-tool.sh`); `.node-version` on the build-config list.
PR 2: the tests, a Factory guards step, a one-off mutation run recorded in the PR.

**Acceptance criteria.**
AC1. Factory guards runs tests of the workflow's pure functions, among them the verification functions' fail-closed
paths and `acceptUnverifiable`'s id matching.
AC2. Inverting a decision in any of them turns a test red.

### 0-164. The agents' guards rely on frontmatter hooks that Workflow agents are not promised

**Type:** security · **Priority:** Medium · **Status:** Done, PR #520 — `nonBlocking` ARC-02 of review-architecture in
round 3 of the `/review` of #0-160 (2026-10-24); the verifier's read scope is already session-level; owner's decision
below (2026-10-25). Kept in full for the decisions (both layers, the spikes, the required guards)
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** —
**Touches:** factory (.claude/hooks/readonly-bash.sh, .claude/hooks/write-scope.sh, .claude/hooks/test-hooks.sh,
.claude/settings.json, .claude/settings.autopilot.json), ci (.github/scripts/ (a structural check),
.github/workflows/factory-guards.yml), docs (README.md "Infrastructure Hardening", docs/ai-factory.md)

**Problem.** Every read-only or write-scoped agent is guarded by a hook in its own frontmatter: `readonly-bash.sh`
for the seven reviewers, acceptance-reviewer, ready-checker, planner, the architect and the audit analysts;
`write-scope.sh` for the architect (`.ai/decisions/ .ai/work/`), the planner (`.ai/plan/ BACKLOG.md`) and
audit-synthesis (`.ai/audit/`). Claude Code documents frontmatter hooks only for an agent spawned through the
Agent tool, an @-mention or `--agent`, in a trusted workspace, never in `claude -p` (`sub-agents.md`, read
2026-10-24); the autopilot spawns them through the `Workflow` runtime. They ran there in the spike of 2026-10-24
(ADR-0037), but nothing promises it, and a release that stops running them would leave a reviewer with the
session's Bash and every agent's writes scoped only by the session-wide guards. README "Known gaps" names only
the `-p` case.

**Approach.** What #0-160 did for the verifier (ADR-0037): the same hooks once more at session level, keyed on
`agent_type` (`--agent <name>`, passing every other agent), in both settings files, next to the frontmatter ones;
a structural check that every agent with a frontmatter guard has its session-level twin with the same arguments.
`readonly-bash.sh` is called per agent with different options (`--architect`, `--k8s`, `--gh-read`), so one
session hook per option, or `--agent` taking a list.

**Also (security review, round 2 of #0-162 PR 2, 2026-10-25).** `check-hook-entries.rb` (ADR-0039) checks every
hook entry that exists, not that one exists: a change that deletes `guard-tests.sh`, `guard-protected-bash.sh`,
`guard-git-push.sh`, `guard-factory-scripts.sh`, `guard-write-in-repo.sh` or `guard-migrations.sh` from
`settings.autopilot.json`, or `guard-migrations.sh` from `settings.json`, passes Factory guards (only the
verifier's hooks are checked to be wired, by `check-web-access.sh`). The structural check of this item is the
place for it: one list of which guard each settings file must run, on which matcher (not a second list per
check, `FAC-A01`).

**Decided (owner, 2026-10-25, after `/research` and spikes; ADR-0041).** A1 + B1 + C1: the twins by hand, one per
guard and arguments, `--agent` taking a comma-separated list (`applies_to_agent` in `_lib.sh`, used by
`readonly-bash.sh`, `write-scope.sh`, `factory-ops-bash.sh` and `read-scope.sh`), in both settings files;
`check-hook-entries.rb` checks them against the frontmatter (the one list) and holds the table of required
session-wide guards (AC4). Spikes on 2.1.310: `claude --settings <file>` adds to the project's `.claude/settings.json`
(both files' hooks ran; the owner kept the twins in both files, and an identical command in both runs once, review
round 1); `agent_type` reaches a
session hook for an Agent-tool agent (`--agents`) and for a Workflow agent (`agentType: 'review-docs'` gave
`review-docs`, none gave `workflow-subagent`). factory-ops' `factory-ops-bash.sh` gets a twin too (frontmatter-only
as well; AC2 names every frontmatter guard).

**Acceptance criteria.**
AC1. For every agent whose frontmatter runs `readonly-bash.sh` or `write-scope.sh`, both settings files run the same
hook with the same arguments for that `agent_type`, shown by test cases (the agent refused, another agent passed).
AC2. Factory guards fails when an agent's frontmatter guard has no session-level twin, or a different one.
AC3. README "Infrastructure Hardening" names the session-level guards and no longer lists this gap.
AC4. Factory guards fails when a session-level guard of `settings.autopilot.json` or `settings.json` is removed or
moved to another matcher, shown by a test case per guard.

### 0-165. The owner's `/review` session trusts the branch it reviews

**Type:** security · **Priority:** Medium · **Status:** Done, PR #521 — from review-security, `/review` of #0-161
(2026-10-24); owner's decision below. Kept in full for the decisions (hook moved to #0-167, the session rule)
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** —
**Touches:** factory (.claude/skills/review/SKILL.md, .claude/settings.json, .claude/hooks/ (a session-level Bash
guard), .claude/hooks/test-hooks.sh, .claude/agents/review-*.md, .claude/skills/review-procedure/SKILL.md), docs
(README.md "Infrastructure Hardening", docs/ai-factory.md)

**Problem.** `/review` is meant for a branch the owner did not write too (its Step 3 names "a bot's branch
checked out"), but the session trusts it in two ways:
1. `allowed-tools: Bash(git diff *)` pre-approves every `git diff`, including `--output=<file>` (writes the diff
   anywhere: verified for `git show` with git 2.54, 2026-10-24), `--ext-diff` and `--textconv` (run a program the
   repository's config names). The session has no Bash hook (`readonly-bash.sh` guards the reviewers,
   `guard-protected-bash.sh` autopilot sessions), so text from the change under review that steers it into
   `git diff --output=~/.zshenv …` writes a file the owner's shell runs, without a prompt.
2. The reviewers read the rules they judge by — `_common.md`, their dimension's file, `factory.md` — from the
   working tree, so the branch under review can rewrite them. Only `factory.md`'s trigger list is read from
   `origin/main` (#0-161).

**Approach (to analyse).** For 1: a session-level PreToolUse hook on Bash for the owner's sessions that refuses
`--output`, `--ext-diff`, `--textconv` and `-c` on `git diff`/`git show`/`git log` (the parser of
`readonly-bash.sh` already splits the words; reuse it), or exact `allowed-tools` entries only. For 2: the
reviewers read `.ai/rules/review/` from `origin/main` (`git show`, which their `readonly-bash.sh` allows) when the
diff touches `.ai/rules/`, or `/review` refuses to run on a branch that changes `.ai/rules/` unless the owner says
it is theirs. Check whether Claude Code documents `allowed-tools` matching for options.

**Decided (owner, 2026-10-25, after `/research`; ADR-0042).** 1C + 2A + a written session rule: `/review` and
`/accept` pre-approve exact commands, `/review` fetches `origin/main` first; reviewers read `.ai/rules/review/` with
`git show refs/remotes/origin/main:…` (round 2: the short name loses to a local branch called `origin/main`). Two changes after round 1 of the review: the hook of 1C (a session-level Bash guard
refusing git's `--output`, `--ext-diff`, `--textconv`) moved to #0-167, since its first version could be passed and
a parser that reads a command as bash does is an item of its own; and the session rule is "read a foreign branch's
`.claude/`, `CLAUDE.md` and `.ai/context/` changes before `/review`", not "start the session on `main`": Claude Code's docs say it picks up changed
skills and agents during a session. AC1 is met by the exact commands (none carries an option); its test cases go
with the hook to #0-167. Not done: a Read hook enforcing the rules from main (README names it).

**Acceptance criteria.**
AC1. In the owner's `/review` and `/accept`, no pre-approved command can write a file outside the scratchpad or run
a program the branch names: every `allowed-tools` entry is an exact command without an option (reworded in round 2
of the review, 2026-10-25: the test cases for `--output`, `--ext-diff`, `--textconv`, `-c` went with the hook to
#0-167).
AC2. A branch under `/review` that changes a rule file is judged by the rules on `origin/main`.

---

### 0-166. Checking CI's Linux steps locally takes seven minutes

**Type:** ci · **Priority:** Low · **Status:** Done, PRs #518, #519 — measured during #0-162 PR 2 (2026-10-25); owner's decision below. Kept in full for the decisions (Ruby pinned in CI, the local runner's design)
**Autopilot:** human-only · **Risk:** low · **Complexity:** low · **Depends on:** —
**Touches:** ci (.github/scripts/ (a script that runs CI's lint and factory steps in Docker), .github/workflows/,
.github/actions/setup-ruby/, .ruby-version), factory (.claude/settings.autopilot.json, .claude/hooks/guard-protected-bash.sh,
.claude/hooks/test-hooks.sh), .ai/rules/protected-paths.md (owner), .ai/decisions/, docs (CLAUDE.md "Commands",
README.md "Infrastructure Hardening", .ai/context/infrastructure.md)

**Problem.** CI's Lint Workflows and Factory guards steps are Linux-only in practice (the pinned actionlint,
shellcheck and zizmor are x86_64 Linux binaries; macOS has no gpg, an older Ruby and BSD tools), so before a push
they are run by hand in a throwaway `ubuntu:24.04` container (memory note "Linux CI checks in Docker"; the script
lives only in a session's scratchpad and is rewritten every session). On the owner's Apple Silicon Mac one full run
took 428 s, measured per step on 2026-10-25:
- the whole container runs as `--platform linux/amd64`, emulated, because the linters exist only for x86_64;
- `test-hooks.sh` (128 s) and `test-web-access.sh` (122 s) start thousands of small processes (hook, jq, grep,
  sed per case), which is what emulation makes slow; `test-preflight.sh` 32 s, `test-factory-guards.sh` 33 s,
  shellcheck with its tests 32 s;
- every run starts from a clean image: `apt-get` 48 s and the tools' download 6 s, each time.

On GitHub's runners (native x86) the same steps take well under a minute, so this costs only the local loop, but
it costs it on every review round of a factory change.

**Approach.** One script in the repository (not a scratchpad copy) that:
1. builds once, and reuses, a local image with the apt packages and the pinned tools, installed with
   `install-pinned-tool.sh` at the versions and SHA-256 of `ci.yml`'s `env` (the image is rebuilt when those pins
   change, e.g. tagged with a hash of them);
2. runs the linters (actionlint, shellcheck through `check-shell.rb`, `check-downloads.rb`, zizmor) in the amd64
   image, and every `test-*.sh`/`check-*` of Factory guards in a native arm64 container (bash, jq, ruby and git
   exist for both);
3. takes a list of steps, so a review round runs only the affected ones, and runs all of them by default;
4. mounts the working tree read-only and copies it, as today (uncommitted files included).

**Also (PR #517, 2026-10-25).** The container must reproduce the runner's gems, not only its Ruby version: Ruby
3.2.3 in `ubuntu:24.04` has the json gem 2.6.3, the runner a newer one, and a check that relied on how `JSON.parse`
fills an `object_class` passed locally and failed on the runner (it fails from json 2.12, reproduced). The runner's
Ruby is not pinned at all: the nine `.github/scripts/*.rb` run on whatever Ruby and gems `ubuntu-latest` brings,
while every other CI tool is pinned (ADR-0033). **To decide:** pin Ruby in CI (e.g. `ruby/setup-ruby` with a
version, itself pinned by SHA) for every job that runs a `.rb`, and build the local image from the same version;
or keep the runner's Ruby and only have the local image read the runner's versions (`ruby --version`, `gem list`
in a CI step).

**Decided (owner, 2026-10-25, after `/research`).** S1 + S4 + R2, in two PRs:
- **PR 1, Ruby pinned in CI (R2, ADR-0040):** `.ruby-version` 4.0.9 (the owner chose the 4.0 branch over 3.4), set
  up by one local action, `.github/actions/setup-ruby` (`ruby/setup-ruby` pinned by SHA, then the versions of Ruby,
  json and psych), in the five jobs that run Ruby. From the review (round 1, owner: all proposals): lint-workflows'
  `check-ruby-setup.rb` refuses a step that runs Ruby before that action in its job, and any other `ruby/setup-ruby`;
  `.ruby-version` joins the build-config list (owner); ADR and README say the binary's integrity rests on the action.
  Whether the checks stay portable to Ruby 2.6 (CI no longer tests it) is decided in PR 2. Round 2: #0-135 and #0-153
  got an "Also"; the required "Changes that need the owner" step of Factory guards stays last (moving it first would
  skip the job's tests whenever it fails, and protects nothing the PR's own scripts could not reach before it).
  Left for later: a per-job step iterator in `run-blocks.rb`, so that `check-ruby-setup.rb` stops walking the
  workflows itself beside `RunBlocks.of` (architecture, round 2, ARC-02); PR 2 does not need it (it reads the jobs
  with `safe_load`).
- **PR 2, the script (S1 + S4):** two images built once and reused (`FROM ruby:<.ruby-version>-slim`), the linters in
  amd64 (exactly CI's binaries, through `install-pinned-tool.sh` and `ci.yml`'s pins), the tests native (arm64 on the
  owner's Mac), both at once; a list of steps, all by default. Measured before deciding (2026-10-25): natively on
  arm64 `test-hooks.sh` takes 8 s (128 s emulated), `test-web-access.sh` 4 s (122 s), `apt-get` 10 s (48 s).
- **PR 2, decided after its analysis (owner, 2026-10-25): A2 + B1 + C2 + D2.** A2: no list of steps of its own:
  `ci-local-steps.rb` runs the `run:` steps of the lint-workflows and factory-guards jobs from the workflows, skipping
  (and naming) `uses:`, `if:` and `${{ }}` steps; B1: the image is `ruby:<.ruby-version>-slim` with packages only,
  the linters come from the job's own install step (AC3 reworded); C2: every step runs after a failure, one line per
  step; D2: no more promise for Ruby 2.6 (ADR-0040 amended). No `--with-gh`: the two token steps need `gh`, which the
  image does not have; they stay on the host.
  Review round 1 (owner: proposals 1-8): the tree reaches the container as a tar on stdin, not a mount (security: a
  mounted tree let every step, with network, read the ignored `docker/.env` and `application-local.yml`), a file
  that cannot be read fails the run, a git worktree is refused; the runner prints Ruby's, json's and psych's
  versions (AC5) and removes its RUNNER_TEMP; `test-repository-settings.sh` is a step of its own in `ci.yml`, so it
  runs locally; the base image stays a tag (ADR-0040 says why). Measured then: run as linux/amd64 whole,
  lint-workflows took minutes (the newly local `test-repository-settings.sh`, bash and Ruby emulated); both jobs now
  run native and only the three x86_64 linters are emulated (Docker Desktop's Rosetta runs them in an arm64
  container; zizmor's glibc build needs `libc6:amd64` in the image): a full run 52 s with the image cached (AC2:
  428 s before, at most 143 s).
  Review round 2 (owner: proposals 1-6): the run is refused while `.git` could carry more than the tree (a stash, or
  credentials in the repository's git config) and inside a container (the devcontainer's Docker is the host's, its
  network outside the firewall); factory-guards runs with `--network none`; each image is built once before the
  jobs start; YAML anchors are read (`aliases: true`).

**Acceptance criteria.**
AC1. One command runs every step of Lint Workflows and Factory guards that needs no secret, with the tools pinned in
`ci.yml`, and prints one line per step with its exit code and time.
AC2. A second full run on the owner's Mac takes at most a third of the 428 s measured on 2026-10-25 (the
measurement is in the PR).
AC3. The image is rebuilt only when `.ruby-version` or its own definition changes; the tools always come from
`ci.yml`'s pins, through the job's own install step (reworded by the owner, 2026-10-25: the pins never enter the image).
AC4. CLAUDE.md "Commands" names the command.
AC5. Whether CI pins Ruby is decided (an ADR if it does), and the local image runs the same Ruby and json/psych gems
as the CI job that runs the `.rb` checks, shown by both printing their versions.

---

### 0-167. No hook of the owner's session refuses git's writing options

**Type:** security · **Priority:** Medium · **Status:** Open — moved out of #0-165 (2026-10-25), whose round-1
review found the first version of the hook passable
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** #0-165
**Touches:** factory (.claude/hooks/ (a session-level Bash guard, `_lib.sh`), .claude/hooks/readonly-bash.sh,
.claude/hooks/guard-protected-bash.sh, .claude/hooks/guard-factory-scripts.sh, scripts/factory/local-review.sh,
.claude/hooks/test-hooks.sh, .claude/settings.json, .claude/settings.autopilot.json, .claude/skills/research/SKILL.md,
.claude/skills/ship/SKILL.md, .github/scripts/check-hook-entries.rb, .github/scripts/test-hook-entries.sh),
.ai/rules/review/factory.md (owner, FAC-S03), .ai/decisions/, docs (README.md "Infrastructure Hardening",
.ai/context/security.md, docs/ai-factory.md)

**Problem.** `git diff`, `git show` and `git log` take `--output=<file>` (writes anywhere) and `--ext-diff`/
`--textconv` (run a program git's configuration names). In autopilot sessions too (#0-131, review round 3): `Bash(git log *)` and its
siblings are allowed there, and since #0-131 `guard-protected-bash.sh` refuses those options only in their plain
form, so a spliced form could still write `.ai/runs/LOCK`, the run's branch the push guard trusts. `/research` pre-approves `git log *`, `git diff *` and
`git show *`, `/ship` `git diff *`, and the owner's session has no Bash hook, so text the session reads (a
diff, a backlog item, a web page) that steers it into such a command runs without a prompt. #0-165 removed the
wildcards from `/review` and `/accept` only. `readonly-bash.sh` refuses the options for the reviewers by matching
the raw text, which quoting inside a word or a line continuation can split.

**What round 1 of #0-165 found in the first version** (a word split as bash splits it, then compared): an option
spliced with empty quotes or a backslash, a backslash-newline continuation, a quote spanning lines (a newline
reset the quote state), a word built at run time from variables set in the same command, and a hook that ignored
its check's exit status. Claude Code's own permission check refused brace expansion, `$( )` and `$'…'` but
pre-approved the variable case under `Bash(git log *)` (spike, 2026-10-25).

**Approach (to analyse).** Either a guard that reads a command as bash does (continuations, quotes, heredocs,
expansions; failing closed on what it cannot read) with mutation-tested cases for each shape above, or exact
`allowed-tools` in `/research` and `/ship` too (cheaper; `/research` then prompts for most git reads). Also decide
`readonly-bash.sh`'s raw-text match.

**Also (round 2 of the review of #0-165, 2026-10-25; non-blocking there).** `scripts/factory/local-review.sh` reads
`_common.md` and `general.md` from the working tree (`cat`), not from main; the autopilot's reviewers could read
the rules at the recorded `mergeBase` instead of `refs/remotes/origin/main`; `docs/ai-factory.md` "What isolates
what" does not describe #0-165 yet; and `factory.md` has no rule that a control reading the base names an
unambiguous ref, nor one listing the files of a branch that steer the owner's session (`.claude/`, `CLAUDE.md`,
`.ai/context/`).

**Acceptance criteria.**
AC1. No skill of the owner's session pre-approves a git command that can carry `--output`, `--ext-diff` or
`--textconv`, or a hook of every session refuses them, shown by test cases for each shape of round 1 of #0-165.
AC2. `readonly-bash.sh` refuses those options however the word is quoted or continued, shown by test cases.
AC3. README "Infrastructure Hardening" no longer lists this gap.

---

### 0-168. A copy of the protected paths can run ahead of the one list

**Type:** ci · **Priority:** Medium · **Status:** Done, PR #524 — from review-security and review-architecture, `/review` of
#0-163 PR 1 (2026-10-25); owner's decision below. Kept in full for the decisions (the runtime list, the flat hook line)
**Autopilot:** human-only · **Risk:** medium · **Complexity:** medium · **Depends on:** —
**Touches:** ci (.github/scripts/check-protected-paths.sh, .github/scripts/test-protected-paths.sh), factory
(.claude/settings.autopilot.json, .claude/hooks/guard-protected-bash.sh), .ai/rules/protected-paths.md (owner),
.ai/rules/review/factory.md (owner, if a rule is added)

**Problem.** `.ai/rules/protected-paths.md` is the one list of protected and build-config paths (#0-122); its copies
that cannot read it, the autopilot's Edit/Write denies and `guard-protected-bash.sh`'s regex, are checked by
`check-protected-paths.sh` in one direction only: every entry of the list must be in every copy. A copy that holds an
entry the list lacks passes. In #0-163 PR 1 `.node-version` was added to both copies, and five documents said it was
on the build-config list, before the list had it: `check-factory-guards.sh` rule 6 (which reads the list from the merge
base) would have let a PR changing it through without the owner, and `changed-paths.sh`, `next-item.sh` and
`check-queue.sh` would not have treated it as protected. Three reviewers found it by reading; no check would have.

**Approach (to analyse).** The other direction for the denies: each `Edit(./…)`/`Write(./…)` deny is an entry of the
list or of a named set of other denies (`.git/**`, `.ai/runs/LOCK`, `.ai/runs/state.json`, `.ai/STOP`, which are not
paths of the list on purpose). For the hook: its regex is tested by running it, not parsed (#0-122), so the other
direction there needs either the regex built from the list at a known place or a list of the hook's alternatives
kept next to it and compared. Decide whether a `FAC-S`/`FAC-A` rule should say so for review.

**Decided (owner, 2026-10-25, after `/research`).** R1 + B + the other way for the denies, no new `FAC-` rule (one
sentence at FAC-A01). The four paths outside the lists (`.git/`, `.ai/runs/LOCK`, `.ai/runs/state.json`,
`.ai/STOP`) become a third list of `protected-paths.md`, "Runtime state" (`runtime:start/end`), which only the copy
check reads; before it they were in both copies and checked by nothing. `check-protected-paths.sh` also checks
copy → list: every `Edit(./…)`/`Write(./…)` deny is an entry as written (a directory with its slash), and every
alternative of the hook's `protected=` line, now one flat alternation (the `.ai/runs/(LOCK|state\.json)` group
split), is an entry with or without its slash. Not taken: the hook building its regex from the list (#0-122 keeps
the last line from parsing Markdown), and generating the hook's line (it would change the hook's patterns).

**Acceptance criteria.**
AC1. Factory guards fails when `settings.autopilot.json` denies writing a path that is neither on the list nor in the
named set of other denies, shown by a test case.
AC2. Factory guards fails when `guard-protected-bash.sh` protects a path the list does not hold, or the decision why it
cannot is recorded, with the case shown either way.

---

### 0-169. A factory-ops call is not bound to the command the workflow named

**Type:** security · **Priority:** Medium · **Status:** Open — `nonBlocking` of review-security in round 2 of the
`/review` of #0-131 (2026-10-25)
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** #0-131
**Touches:** factory (.claude/agents/factory-ops.md, .claude/hooks/factory-ops-bash.sh, .claude/hooks/test-hooks.sh,
.claude/workflows/backlog-autopilot.js, .claude/workflows/audit.js), docs (README.md "Infrastructure Hardening")

**Problem.** Since #0-131 only the `factory-ops` agent runs `scripts/factory/` in an autopilot session, and the
lock (the run's base commit and its branch, the one the autopilot may push) moves only through those scripts. The
workflow tells `factory-ops` "Run exactly: <command>", but nothing binds what it runs to that line:
`factory-ops-bash.sh` lets it run any factory script with plain arguments, and its `maxTurns: 4` leaves room for
several. Part of what it relays is text the branch controls (`changed-paths.sh` lists file names), so a steered
`factory-ops` could run `state.sh unlock`, `preflight.sh` and `state.sh branch <another>` in one task.

**Approach (to analyse).** Bind each call to its task: a per-call value the workflow puts in the command and the
hook checks (it cannot see the prompt), or one command per task (`maxTurns` after a spike of what one turn allows),
or `state.sh` refusing a second `preflight`/`branch` in one run by a record the hook keeps.

**Acceptance criteria.**
AC1. A `factory-ops` call that runs a factory command other than the one its task named is refused, shown by test
cases.
AC2. README "Infrastructure Hardening" names the control.

---

### 0-170. `/seed-bugs` measured nothing: the seeder's commands were refused and reported as a skip

**Type:** ci · **Priority:** Medium · **Status:** Done, PR #528 (AC3 is shown by the rerun of #0-115's "after" measure) —
found by the first `/seed-bugs` run on record (P-10 on `253e5b2a3a06`, run `perf-after-p10`, 2026-10-25)
**Autopilot:** human-only · **Risk:** low · **Complexity:** low · **Depends on:** —
**Touches:** factory (.claude/agents/defect-seeder.md, .claude/workflows/seed-bugs.js), ci
(.github/scripts/test-autopilot-workflow.mjs)

**Problem.** The run came back SKIPPED with no reason. The seeder had tried `grep … && git show …` (refused by the
shell guard: a separator), then `git -C /workspace show --stat <commit>` and the `git switch -c seed/…` it needs
(refused in `dontAsk`: `git -C …` matches none of the session's allow rules), and answered `{"skipped": true}`: its
definition offers only "skipped" for "this pattern does not fit", and the workflow's schema had no `reason`, so a
blocked tool read as a result. `.ai/audit/benchmark/results.md` does not exist: no run had ever been recorded.

**Fix.** `defect-seeder.md` says how commands run in an autopilot session (from the root, one per call, no `git -C`,
no separators; Read and `git grep` to look) and answers `{"blocked": true, "reason"}` when a command is refused.
`seed-bugs.js` asks for `reason` and `blocked`: blocked is FAILED with the reason, a skip without a reason is FAILED,
and so is a ground truth the Record phase cannot use (no `seed/` branch, no file, a dimension outside the panel).
`test-autopilot-workflow.mjs` runs `seed-bugs.js` against a fake agent for each of them, and a measured run.
Review round 1 added: the answer must name this run's branch (`seed/<pattern>-<runId>`), the line and the rule (a
missing line made every catch read as a miss) and start from the commit given; the reason is cut to 300 characters.
Not here: a record of the runs that measured nothing (#0-171) and a write scope for the seeder (#0-172).

**Acceptance criteria.**
AC1. A refused command of the seeder ends the run FAILED with the refusal, never SKIPPED, shown by a test.
AC2. A skip without a reason, and an unusable ground truth, end it FAILED, shown by tests.
AC3. `/seed-bugs` P-10 on `253e5b2a3a06` in an autopilot session plants the pattern and records a row (the run of
#0-115's pilot, once this is merged).

---

### 0-171. A `/seed-bugs` run that measured nothing leaves no record

**Type:** ci · **Priority:** Low · **Status:** Open — `nonBlocking` FAC-A03 of review-architecture in the `/review` of
#0-170 (2026-10-25)
**Autopilot:** human-only · **Risk:** low · **Complexity:** low · **Depends on:** #0-170
**Touches:** factory (.claude/workflows/seed-bugs.js, .claude/agents/audit-synthesis.md), ci
(.github/scripts/test-autopilot-workflow.mjs), .ai/rules/audit.md (owner)

**Problem.** `seed-bugs.js` writes a row to `.ai/audit/benchmark/results.md` only for a MEASURED run; a FAILED or
SKIPPED one is returned to the session that started it and nowhere else. The audit then cannot tell a dimension that
was measured and caught nothing from one that was never measured, which is how #0-170 went unseen: no row had ever
been written.

**Approach (to analyse).** Record every run, its outcome and reason included (a row with `FAILED`/`SKIPPED`), and have
the audit count the attempts per dimension next to the catches.

**Acceptance criteria.**
AC1. A FAILED or SKIPPED run leaves a row with its outcome and reason, shown by a test.
AC2. The audit's benchmark section reports, per dimension, runs that measured nothing.

---

### 0-172. The defect-seeder writes and commits with no write scope

**Type:** security · **Priority:** Low · **Status:** Open — `nonBlocking` FAC-S03 of review-security in the `/review` of
#0-170 (2026-10-25)
**Autopilot:** human-only · **Risk:** medium · **Complexity:** low · **Depends on:** —
**Touches:** factory (.claude/agents/defect-seeder.md, .claude/settings.json, .claude/settings.autopilot.json,
.claude/hooks/test-hooks.sh), ci (.github/scripts/check-hook-entries.rb)

**Problem.** `defect-seeder` has Bash and Edit and commits onto a `seed/` branch, but neither its frontmatter nor a
session-level twin keyed on its `agent_type` runs `write-scope.sh` (ADR-0041): what it may write is bounded only by the
autopilot's denies of the protected paths. It plants a defect in code the merged change touched, so its scope is that
change's files.

**Approach (to analyse).** A `write-scope.sh` for it, in its frontmatter and as a twin in both settings files
(`check-hook-entries.rb` keeps them equal), with a scope the workflow can name per run (the merged change's paths) or a
fixed one (the services' `src/main/`).

**Also (review round 2 of #0-173, security FAC-S03).** The seeder's ban on `git switch`, `git checkout` and a new
branch is only a prompt. Since #0-173, `scripts/factory/seed-check.sh` detects it, but only after the fact: between a
switch and the check, the seeder's calls run on another tree's hooks. A guard keyed on its `agent_type` would refuse
those commands before they run. It belongs with this item's guard (the same places, the same entry check).

**Acceptance criteria.**
AC1. The defect-seeder cannot write outside its scope, shown by a test case.
AC2. Factory guards fails when its guard is missing from one of its places.
AC3. The defect-seeder cannot switch or create a branch, shown by a test case.

---

### 0-173. `/seed-bugs` switches the shared working tree to an old commit

**Type:** security · **Priority:** High · **Status:** Done, PR #529 (AC3 is shown by the owner's rerun of #0-115's "after" measure on `76379e806fb4`) — found by the rerun of
#0-115's pilot measure, after #0-170
(P-10 on `253e5b2a3a06`, run `perf-after-p10`, 2026-10-25)
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** #0-170
**Touches:** factory (.claude/agents/defect-seeder.md, .claude/workflows/seed-bugs.js, scripts/factory/seed-branch.sh,
scripts/factory/seed-check.sh), ci (.github/scripts/test-autopilot-workflow.mjs, .github/scripts/test-seed-branch.sh,
.github/workflows/factory-guards.yml), docs (docs/ai-factory.md, ADR-0044), .ai/audit/benchmark/patterns.md (owner, if
the choice of commit changes)

**Problem.** The seeder builds its branch on the merged change itself (`git switch -c seed/<pattern>-<run id>
<commit>`), in the working tree the session runs in. That tree is the one the devcontainer mounts and the owner's own
sessions use. Claude Code reads hook scripts from disk on every call and reloads agents, skills and `CLAUDE.md` when they
change (ADR-0042), so from the switch on the session ran on the definitions of that old commit: the run of 2026-10-25
switched to `253e5b2a` (about two weeks old) and got that commit's `guard-protected-bash.sh`, which refused the seeder's
reads, settings that named a `read-scope.sh` the old tree did not have (so every file read failed), and an older
`CLAUDE.md`; the owner's session on the host lost its `verifier` agent until the tree was switched back. A run that had
gone through would have had the panel review under the old agents and, worse, older and weaker guards. Nothing was
committed or pushed; the tree was clean and was switched back to `main` by hand.

**Approach (to analyse).**
- **F1.** Branch from the current base (`main`) and plant the defect in a file the merged change touched, as that file is
  now; the panel reviews the defect alone against `main`. Simple; the tree stays current; the measure is easier than
  intended (a small diff, not a defect hidden in a real change).
- **F2.** Branch from `main`, commit the merged change reverted (the base), then commit it back with the defect (the
  change under review, `mergeBase` = the revert commit): the panel sees the real change with the defect inside it, as
  the benchmark meant, while the tree keeps `main`'s hooks, agents and rules. A change that touched `.claude/` or the
  rules themselves is not a valid target.
- Either way: the run refuses to start unless the tree is on a clean `main`, and returns to `main` at its end, also when
  it fails.

**Decided (owner, 2026-10-25, after `/research`).** F2, through a factory script: `scripts/factory/seed-branch.sh` (run
by `factory-ops`) checks a clean `main` at `origin/main` and no `.ai/runs/LOCK` (no lock of its own: `preflight.sh`
also wants Docker and a green CI, which a measure does not need), refuses a change that touches a protected path or
`CLAUDE.md`, and builds `seed/<pattern>-<run id>` from `main`: the change's code reverted (the base the panel diffs
against), then back under the change's subject; Markdown is not replayed. The seeder only plants and commits on it,
in one of the change's files; the workflow ends on `main` in every outcome (`return-to-main.sh`). Tried on a copy of
the repository: `253e5b2a` no longer applies (refused, back on a clean `main`); `76379e80` (PR #472) does.

**Review round 1 (owner accepted every proposal, 2026-10-25).** Architecture found two blocking gaps (FAC-A02):
- the panel ran on the seeder's word for its branch. Now `scripts/factory/seed-check.sh` confirms the tree before the
  panel runs: on the seed branch, clean, one commit on top of the change, touching only the change's files;
- `finish()` ignored `return-to-main.sh`'s answer, and the Record phase wrote `results.md` while the seed branch was
  still checked out, so every measured run stayed on `seed/…` and the next run was refused. Now the run goes back to
  `main` before Record and an outcome that could not says where it left the tree (`leftOn`). The owner commits
  `results.md` between runs, and `seed-branch.sh` names that file when it refuses.

Also done in that round:
- `$files` became an array, read with `--literal-pathspecs`;
- `--no-renames` in both diffs, and the plantable files leave out the deleted ones;
- tests for a merge commit, a Markdown-only change and a rename or deletion;
- the workflow checks `seed.branch` against `seed/<pattern>-<run id>` and the two shas;
- ADR-0044 records the decision.

**Review round 2 (owner accepted the proposals, 2026-10-25).** Every reviewer approved. The non-blocking notes the
owner took:
- `seed-branch.sh` reads the two protected lists one at a time, each with its own refusal;
- `seed-check.sh` leaves the change's deleted files out of what the seeder may touch;
- a failed run that made the branch carries `cleanup`;
- ADR-0044 no longer says `patterns.md` names the commits.

A guard that refuses the seeder's `git switch` before the call went to #0-172.

**Acceptance criteria.**
AC1. During a `/seed-bugs` run the working tree's `.claude/`, `CLAUDE.md` and `.ai/rules/` are those of `main`, shown by
a test of the workflow's commands (no switch to the merged commit).
AC2. The run refuses to start on a tree that is not a clean `main`, and ends on `main`, shown by tests.
AC3. `/seed-bugs` P-10 on a commit whose code still applies to `main` (`76379e806fb4`) plants the pattern and records a
row (with #0-170's AC3).

---

### 0-174. `/seed-bugs` cannot start from its own command, and reads strings from factory-ops

**Type:** ci · **Priority:** High · **Status:** In progress — found by the owner's rerun of #0-115's "after" measure,
the first run after #0-173 (P-10 on `76379e806fb4`, run `perf-after-p10`, 2026-10-25)
**Autopilot:** human-only · **Risk:** low · **Complexity:** low · **Depends on:** #0-173
**Touches:** factory (.claude/workflows/seed-bugs.js), ci (.github/scripts/test-autopilot-workflow.mjs), docs
(docs/ai-factory.md), .ai/rules/review/factory.md (owner: FAC-A06)

**Problem.** Two defects, one after the other, and no run measured anything:
1. `/seed-bugs P-10 76379e806fb4 perf-after-p10` hands the workflow its words as one string (Claude Code's command
   form, `args: "P-10 …"`), and the script, which reads `args.pattern`, `args.commit` and `args.runId`, stopped
   `NOT_STARTED`. Only a hand-made `Workflow` call with an object started it.
2. `ops()` gave `factory-ops` the bare schema `{ type: 'object' }`. The agent (Haiku) answered `seed-branch.sh`'s JSON
   with every value a string (`"ok": "true"`, `"files": "[…]"`) and wrapped `return-to-main.sh`'s in a `parameter`
   key, so the run failed on a seed branch it had made (`ok !== true`), reported `leftOn: "unknown"` from a tree that
   was on `main`, and named no cleanup (the branch was deleted by hand). #0-173's tests could not see it: the fake
   agent of `test-autopilot-workflow.mjs` ignores `schema`. `backlog-autopilot.js` types most answers it reads
   (`PREFLIGHT` and the rest), but reads `pinned.branch` from `state.sh branch` under `ANY_OBJECT` (a wrong answer
   stops the item, it lets nothing through) and puts `local-review.sh`'s untyped answer in the PR record: #0-175.

**Decided (owner, 2026-10-25).** The script takes both forms: the object, or three words in the order
`<pattern> <commit> <run id>`, checked by the same `SAFE` pattern (a wrong count or an unsafe word: `NOT_STARTED`, no
agent runs). Each script's answer has a typed schema with `additionalProperties: false`: the fields the script prints,
plus `factory-ops`' own `{error, exitCode}`; no `required`, so an error answer still reaches the run's reason. The same
string-args gap in `/audit` and `/docs-audit`, and `backlog-autopilot.js`'s untyped answers, are #0-175. The review
rule is FAC-A06 in `.ai/rules/review/factory.md` (the owner's; review round 1, architecture `ruleGap`).

**Acceptance criteria.**
AC1. `/seed-bugs <pattern> <commit> <run id>` makes the same calls as the object form, and a wrong count or an unsafe
word starts nothing, shown by tests.
AC2. The schemas accept what `seed-branch.sh`, `seed-check.sh` and `return-to-main.sh` print and refuse the answers of
the 2026-10-25 run, shown by a test.
AC3. `/seed-bugs` P-10 on `76379e806fb4` plants the pattern and records a row (with #0-173's AC3).

---

### 0-175. The other workflows cannot start from their commands, and read untyped factory-ops answers

**Type:** ci · **Priority:** Medium (Low until review round 2 of #0-174: once FAC-A06 is on main, `:481` breaks it) ·
**Status:** Open — found with #0-174 (2026-10-25)
**Autopilot:** human-only · **Risk:** low · **Complexity:** low · **Depends on:** #0-174
**Touches:** factory (.claude/workflows/audit.js, .claude/workflows/docs-audit.js,
.claude/workflows/backlog-autopilot.js), .ai/rules/review/factory.md (owner: FAC-A06 to "Enforced by checks"), ci
(.github/scripts/test-autopilot-workflow.mjs), docs (docs/ai-factory.md)

**Problem.** Like `/seed-bugs` before #0-174: Claude Code passes the words typed after the command as one string, and
both scripts read object keys (`args.since`, `args.date`, `args.target`; `args.date`), so `/audit 2026-10-01 2026-10-25`
stops `NOT_STARTED`. `backlog-autopilot.js` is unaffected in its usual form (no args); `/backlog-autopilot #0-25` would
run the queue's next item instead of #0-25.

**Also (review round 1 of #0-174, architecture FAC-A02 and `ruleGap`).** `backlog-autopilot.js` reads
`pinned.branch` from `ops('state.sh', ['branch', …], ANY_OBJECT)` (`:481`): an answer in strings or wrapped in another
key stops the item ("could not record the run's branch"), the same class of defect as #0-174's, failing closed. Its
`local-review.sh` answer (`:559`) only reaches the PR record. FAC-A06 makes it a review finding; nothing checks it.

**Approach.** The same as #0-174: words in a fixed order, checked like the object's values, and a test per script;
typed schemas (FAC-A06) for every `ops()` answer a workflow reads; and a test in `test-autopilot-workflow.mjs` that
every such call's schema types what it reads (a structural check, so FAC-A06 moves to "Enforced by checks").

**Acceptance criteria.**
AC1. Each of `/audit` and `/docs-audit` starts from its command's words, and refuses a wrong count, shown by tests.
AC2. Every factory-ops answer a workflow reads has a typed schema, and a test fails when one does not.


### 0-121. A pipeline audit traces problems to the stage that introduced them

**Type:** design · **Priority:** Medium · **Status:** Done, PR #484 (owner's decision 2026-10-10, after #0-42). Kept in full
for the decision (one pipeline analyst, phase containment) and the first collected cases.

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

**Delivered.** `audit-data.sh` derives the cases (BLOCKED, rounds > 1, a scope category that points at a stage, acceptance
other than ACCEPT, owner labels `human:fp-*` / `human:missed-*` / `human:introduced-*`, BLOCKED runs without a PR)
and attaches each item's `/ready` PR, tested by `test-audit-data.sh`; a new read-only agent `audit-pipeline` traces
each case through every stage with one escape class per stage passed (no rule, rule not applied, rule allowed it,
no data) and recommends to the cheapest stage; `audit.js` gains the target `pipeline`, and `all` (the default)
runs it with the reviewers' audit on the same cadence; `audit.md` "Pipeline audit"; `audit-synthesis` writes its
report; owner labels `human:introduced-<stage>` (optional confirmation, set back on PR #477 and #482). Also:
both audit agents read text as data, not instructions; `/apply-audit` names its branch after the report file
(one report per target per audit); the `**Escaped from:**` convention takes any stage. The list below is the
history of the first cases; from now on they are derived, not written by hand.

**Collected cases** (stage introduced → stage detected; recorded here so the audit, once built, starts with them):
1. #0-42 — an acceptance criterion needed a new `.github/` check the autopilot may not write. Introduced in `/ready`
   (and passed by the architect), detected at `implement`: BLOCKED, draft PR #477. Fixed by #0-122.
2. #0-124 — README "Infrastructure Hardening" missing from Touches although the item adds a security control; the
   checker had asked, the answer dismissed it. Introduced in `/ready` (PR #481), detected at the scope comparison:
   `backlog-estimate-off` in PR #482 (the implementer added it, correctly). Fixed by #0-125.
3. PR #482 — DOC-10 on the Context section of an accepted ADR (ADR-0026), which records the state when it was
   decided; owner label `human:fp-docs`. Introduced and detected in review (docs dimension) — a reviewers-audit case.

---



### 0-129. The pipeline audit counts only escape markers the owner added

**Type:** security · **Priority:** Medium · **Status:** Done, PR #491 (split from #0-126 at its `/ready`, 2026-10-10).
Kept in full for the decisions (the PR opener vouches, nothing else does) and what three review rounds changed.
**Autopilot:** human-only · **Risk:** high · **Complexity:** medium · **Depends on:** — (#0-126 is Done; do in one PR with #0-128)
**Touches:** ci (scripts/factory/audit-data.sh, scripts/factory/_marker.sh, .github/scripts/test-audit-data.sh, .claude/workflows/audit.js, .claude/agents/audit-pipeline.md, .claude/agents/audit-reviewers.md), root (.ai/rules/audit.md, README.md "Infrastructure Hardening", docs/ai-factory.md)

**Problem.** `audit-data.sh` makes a case of every `**Fixes:** #0-N · **Escaped from:** <stage>` marker added to
`BACKLOG.md`/`BACKLOG-DONE.md` in the period, whoever's commit added it: a branch, or an agent steered by injected
text, could plant a case or a "hard fact" that raises an audit's confidence (`audit.md`, the confidence table). #0-126
closes the same hole for `human:*` labels; markers are a different source (commit history, not PR timelines).

**Approach.** Count a marker only when GitHub vouches for who wrote it: the commit that added it reached `main`
through a PR opened by the owner's login (a signature does not vouch, see AC2). Not enough on their
own: the commit's author or committer (free, unsigned text; the owner and the bot share the container) and a merge by
the owner (in shadow mode the owner merges every autopilot PR, so a planted line in a long diff would pass). Report
the others as unverified, never as cases. Same owner source as #0-126 (`gh repo view --json owner`). Do it together
with #0-128, whose CI check reads the same `MARKER` (`scripts/factory/_marker.sh`, AC10).

**Acceptance criteria.**
AC1. A marker whose adding commit belongs to a PR merged into `main` and opened by the owner's login is a case in
the audit data.
AC2. A marker whose commit names the owner only as author or committer (name or email), e.g. pushed straight to
`main` with no PR, is reported `unverified` and is not a case; a GitHub signature does not vouch for it either (this
narrows the Approach's "or a verified signature": the owner signs nothing, and the web-flow signature on merge
commits is GitHub's, not the owner's).
AC3. A marker in a PR opened by anyone other than the owner (the bot included) is `unverified`, even if the owner
merged that PR.
AC4. The PR is looked up with `gh api repos/<repo>/commits/<sha>/pulls`; when it returns several PRs the marker counts
only if one of them is merged into `main` and opened by the owner; a failed call makes the marker `unverified`.
AC5. `unverified` markers are listed in the output (count and lines), so the auditor sees what was dropped.
AC6. The owner login comes from `gh repo view --json owner`, as in #0-126; a failed lookup makes every marker
`unverified` (fail closed).
AC7. The raw `escaped` list read by the reviewers audit is filtered by the same rule (its adding commit found with
`git log -S`), its unverified markers reported separately.
AC8. README "Infrastructure Hardening" lists the control.
AC9. `.ai/rules/audit.md` states that a marker counts only when vouched for, and its confidence table no longer
treats an unverified marker as a hard fact.
AC10. `MARKER` is defined once, in `scripts/factory/_marker.sh`, which `audit-data.sh` and #0-128's check both
source (decided when building it, 2026-10-12: a shared definition like `_protected.sh`, rather than a check mode in
`audit-data.sh` or a check that parses the line out of it), and the vouching applies to that pattern.
AC11. While any autopilot PR of the period is authored by the owner's login, every marker is `unverified` — the
`ownerIsAutopilot` rule of #0-126: the autopilot then opens its PRs with the owner's token, so the opener proves
nothing (added when building it, 2026-10-12).
AC1–AC7, AC10 and AC11 are checked by `.github/scripts/test-audit-data.sh`; AC8–AC9 are judged by review-docs.

**Review round 1 (2026-10-13).** A marker sits on its item's `**Type:** … · **Status:** …` line, and the first build
traced and dated it by that whole line: a status change made the changer its author (the owner's edit vouched for
the bot's marker, the bot's close unvouched the owner's) and a new marker of the period. It is now traced by the
marker itself (`marker_span` in `_marker.sh`, which also reads the item and stage, so `audit-data.sh` has no second
pattern), and the found commit must add exactly that marker (`git log -S` matches substrings). Also: the history is
read without replace refs or grafts, `pulls_verdict` vouches with an explicit `OK`, and #0-128's check catches the
marker in any case, bold or not. Kept as a known limit, its own item: a commit pushed onto the owner's PR branch by
another account is in the owner's PR (#0-131).

**Review round 2 (2026-10-13).** A marker was new in a period when its lines there were added more often than
removed, but vouched by its first commit: the bot copying an old owner-vouched line, the original left in place,
made it a vouched case again. It is now new only when its origin commit is in the period. And the history is read
without textconv or external diff drivers (`.git/config` could rewrite what `-S`, `-p` and `show` see), and a
shallow clone vouches for no marker (its boundary commits show their whole files as added).

**Review round 3 (2026-10-13).** The period's history is read with `--since-as-filter`: `--since` stops at the
first older commit, so one back-dated commit hid every marker behind it. Each marker line reaches jq as its own
value (a tab in a marker split the old tab-separated records), and the commands in the vouching loop read
`/dev/null`, not the loop's input. `audit.md` states that the `ownerIsAutopilot` limit is wider for markers (any
earlier period); no such marker exists, the autopilot has always run as its own account.

---

### 0-130. A shell guard that fails refuses the command instead of allowing it

**Type:** security · **Priority:** Low · **Status:** Done, PR #492 (found by the security review of #0-126, 2026-10-10).
Kept in full for the decisions (option B, `readonly-bash.sh` in scope) and what four review rounds changed.
**Autopilot:** human-only · **Risk:** medium · **Complexity:** low · **Depends on:** —
**Touches:** root (.claude/hooks/guard-protected-bash.sh, .claude/hooks/readonly-bash.sh, .claude/hooks/_lib.sh, .claude/hooks/test-hooks.sh, README.md "Infrastructure Hardening")

**Problem.** `guard-protected-bash.sh` runs `why=$(git_grep_unsafe "$cmd")` and `why=$(gh_label_unsafe "$cmd")` and
lets the command through when `why` is empty. A runtime error inside either function (an unset variable, a failed
`sed`) also leaves `why` empty, so the guard fails open. The impact is bounded — the audit's own actor check is the
control for labels (#0-126), and the commit gates and PR checks for the rest — but a guard should fail closed.
`readonly-bash.sh` (the reviewers' allow-list) calls `git_grep_unsafe` the same way.

**Approach.** Both functions end every path with an explicit `return 0` (allowed) or print-and-return (refused), and
the caller refuses when the function's exit status is non-zero (`why=$(…) || block "the … check failed"`), with a
test that a forced error in each is refused.

**Decided (owner, 2026-10-14).** Every failure refuses, not only one that aborts the function (option B): a step that
fails without aborting — `grep -q … || return 0` taking grep's error (2) for "no match", a `sed`/`tr` that fails and
leaves an empty word list — returns 2 too. Matching moved from grep on a here-string to bash's own `[[ =~ ]]` and
`case`, which have no such failure (a here-string bash cannot write fails with exit 1, grep's "no match"); the
`sed`/`tr` substitutions keep the tools and end in `|| return 2`. `readonly-bash.sh` is in scope (the same pattern).
Left out, for its own item: `guard-protected-bash.sh`'s own grep checks (secrets, mvnw, protected paths, writes)
and `json_get` (a failing jq leaves an empty command, which exits 0) fail open the same way.
Review round 1 (2026-10-14), fixed here: a newline inside a quoted word split one argument into two words for both
checks, which read `shell_words`' output one word per line (`git grep 'x<newline>--' --untracked …` passed, also on
`main`: sec-4e1a) — a `git grep` or `gh` command with a newline in it is now refused (multi-line commit messages still
pass; a multi-line PR body goes in `--body-file`); and `matches` runs in the C locale, where a byte that is not valid
UTF-8 cannot turn a match into "no match" (sec-9c27). Review round 2, fixed here: `git grep` run behind something passed
the check, which starts at word 1 (`env`/`command`/`sh -c git grep`, `GIT_DIR=x git grep`, `/usr/bin/git grep`, a glob
such as `/usr/bin/gi[t]`; also on `main`, stopped in practice by the autopilot's allow-list) — refused now, as
`gh_label_unsafe` does for gh. Review round 4, fixed here: a glob where the subcommand goes (`git gr?p`, `git g[r]ep`,
`git *`, also behind git options) became grep once a file of that name existed — refused; the first filter also lets
through a glob made only of grep's letters, so an ordinary `git add src/*.java` is not touched.

---

### 0-131. The autopilot pushes only the branch of its own item

**Type:** security · **Priority:** Medium · **Status:** Done, PR #525 — found by the security review of #0-128/#0-129
(2026-10-13); owner's decision below. Kept in full for the decisions (the branch in the lock, the review rounds' bindings)
**Autopilot:** human-only · **Risk:** high · **Complexity:** low · **Depends on:** —
**Touches:** root (.claude/hooks/guard-git-push.sh, .claude/hooks/test-hooks.sh, README.md "Infrastructure Hardening", .ai/rules/audit.md)

**Problem.** `guard-git-push.sh` lets an autopilot session run `git push -u origin <type>/<name>` for any feature
branch, so it can fast-forward a branch of the owner's open PR. #0-129 counts an escape marker when its commit is in
a PR the owner opened and merged into `main`; a marker the bot pushed onto that branch is in the owner's PR, and
counts once the owner merges it without noticing the extra commit. The same push could slip any change into a PR
the owner believes is only theirs.

**Approach.** The push guard allows only the branch the picker created for the item of the current run (recorded
in the run's working files), and refuses every other branch, with a test per form (another item's branch, the
owner's branch, a branch that does not exist yet). Branch protection (#0-113) cannot express "this account only
its own branches" on a personal repository, so the hook is the control; the owner still reads the commits of
their own PR before merging. Remove the known limit from README and `audit.md` when done.

**Decided (owner, 2026-10-25, after `/research`).** Option A: the branch is recorded in `.ai/runs/LOCK`, not in the
run's working files (an agent can change those; no agent writes the lock: the autopilot's denies,
`guard-protected-bash.sh`, checked both ways since #0-168; code run through `./mvnw` can, #0-116). Review round 1
added two bindings: only the factory-ops agent runs `scripts/factory/` (`guard-factory-scripts.sh`, on `agent_type`;
before, any agent could `state.sh unlock`, `preflight.sh` and `state.sh branch <another>`), and the workflow binds
the picker's branch to `<type>/<item>-…` and, on resume, to the owner's `args.branch` before recording it. Round 2:
only a single read-only `git grep|diff|log|show|ls-files|blame` may name `scripts/factory/` for other agents (not a
match on how a script is run); `factory-ops` not being bound to its task's command is #0-169. Round 3: git's
`--output`, `--ext-diff`, `--textconv` count as writes in `guard-protected-bash.sh` (an allow-listed `git log` could
write the lock), in their plain form; a word spliced with quotes or a backslash is #0-167's, which now names the
autopilot's sessions and the lock too. `state.sh branch <name>` writes it as the lock's third
field, once per run (a second call, no lock or a name that is not a feature branch is refused); the workflow calls it
right after validating the picker's answer, on resume too, and stops the run when it fails. `guard-git-push.sh`
lets through only `git push -u origin <that branch>`; no lock, or no branch in it, refuses every push.

---

### 0-132. The shell guards' own checks refuse when they fail

**Type:** security · **Priority:** Low · **Status:** Done, PR #526 — found while fixing #0-130 (2026-10-14); owner's
decision below. Kept in full for the decisions (A1-E1, the whole-text match)
**Autopilot:** human-only · **Risk:** medium · **Complexity:** low · **Depends on:** #0-130
**Touches:** root (.claude/hooks/guard-protected-bash.sh, .claude/hooks/readonly-bash.sh, .claude/hooks/guard-git-push.sh, .claude/hooks/guard-factory-scripts.sh, .claude/hooks/factory-ops-bash.sh, .claude/hooks/_lib.sh, .claude/hooks/test-hooks.sh, README.md "Infrastructure Hardening")

**Problem.** #0-130 made `git_grep_unsafe` and `gh_label_unsafe` refuse when they fail. The guard's own checks
still read a failure as "nothing found": `printf … | grep -Eq "$secrets"` (a secret file), the `mvnw` write check,
`grep -Eq "$protected" || exit 0` (no protected path, so allowed) and the `writes` check take grep's error (2) for
"no match", and `json_get` returns an empty command when jq fails on the input, which the hooks answer with
`exit 0` (that part moved to #0-162 by the owner, 2026-10-24). Other hooks (`readonly-bash.sh`, `guard-git-push.sh`, `guard-factory-scripts.sh`, `factory-ops-bash.sh`)
read the command the same way. Bounded like #0-130: the deny rules, the commit gates and the PR checks stay.

**Approach.** The same contract as #0-130: matching with bash's own `[[ =~ ]]` / `case` where it can, a tool's
failure is a refusal (`json_get` telling "no such field" from "could not parse": #0-162). A test
per check with the tool broken, as in #0-130's. `shell_words` moves to the same three-way contract (refused = exit 0
and a reason, failed = non-zero), which removes `shell_words_refusal`, the shim that tells the two apart by the text
`REFUSE` (architecture review of #0-130), and refuses a newline inside a quoted word itself, as both its callers now
do separately because they read its output one word per line (architecture review round 2 of #0-130).
Also from #0-130's review round 3: the program list that `git_grep_unsafe` and `gh_label_unsafe` refuse a command
behind (`sh|bash|…|time`) is one copy each — one shared definition; and two forms neither check sees, an interpreter
run by its path or off the list (`/bin/sh -c "git grep --untracked …"`, `ksh -c`, `perl -e`) and a git alias
(`git -c alias.g='grep --untracked' g`). Both are stopped today by the autopilot's `dontAsk` allow-list and by
`readonly-bash.sh`'s first-two-words check; decide here whether the guard should refuse them too (e.g. any first word
that is a path, with a standalone `-c`/`-e`) or keep naming the allow-list as the control.
A known side effect to keep or narrow here: since #0-130 the first filter of `git_grep_unsafe` also lets through a
command with a glob and the word grep, so `./mvnw test -Dtest='Foo*' | grep FAIL` is refused ("a command with grep and
a glob") in every session the guard covers (architecture review round 4 of #0-130; grep is not on the autopilot's
allow-list, so it changes little there).

**Decided (owner, 2026-10-25, after `/research`).** A1 + B1 + C + D1 + E1, in one PR.
- **A1.** The guards' checks match with `finds` (`_lib.sh`, on `matches`: bash's own regex in the C locale, an
  invalid expression refuses) instead of `printf … | grep -Eq` (`guard-protected-bash.sh`, `guard-git-push.sh`,
  `guard-tests.sh`, `guard-factory-scripts.sh`). Found on the way: grep matched line by line, so an allow pattern
  anchored `^…$` (`git fetch origin main`, the push of the run's branch) passed a command whose first line alone
  matched; `finds` matches the whole command.
- **B1.** `shell_words` answers by its exit status alone (0 and the words, 3 and the reason, other = it failed),
  printing the words only at the end; `shell_words_refusal` is gone. Not moved into it: the refusal of a newline
  inside a quoted word, which its callers make only once the command is `git grep` or `gh pr create|edit` — inside
  `shell_words` it would refuse a multi-line commit message that names git grep (a test of #0-130 that must pass).
- **C.** One list of the programs a command can run behind (`WRAPPER_PROGRAMS`, `is_wrapper`).
- **D1.** An interpreter by its path or another name, and a git alias, stay the allow-list's to stop (`dontAsk`
  in autopilot sessions, `readonly-bash.sh`'s first two words for the reviewers); written at `WRAPPER_PROGRAMS`, and
  a guard that reads a command as bash does is #0-167.
- **E1.** The glob-and-grep refusal is kept, and written at the check.

---

### 0-123. Reviewers are told which tool to use, and every hook refusal names it

**Type:** bug · **Priority:** High · **Status:** Done, PR #479 (found in the manual `/review` of #0-42, 2026-10-10). Kept
in full for the decision (tell, do not loosen) and the reverted first draft.
**Autopilot:** human-only · **Risk:** high · **Complexity:** low · **Depends on:** —
**Touches:** root (.claude/hooks/readonly-bash.sh, .claude/hooks/test-hooks.sh, .claude/skills/review-procedure, .claude/agents, README.md)

**Problem.** The review agents (and `ready-checker`, `acceptance-reviewer`, the auditors) run Bash through
`readonly-bash.sh`, an allow-list of one read-only git command per call. `review-procedure` told them only to read
`git diff HEAD`, not how to read a file or search, so they reached for `cat`, chained commands, `git -C` and
`git grep -E "a|b"`; each was refused with a message that said what was forbidden but not what to use. In one
review the architecture reviewer was refused four times and ran for over 14 minutes; the performance reviewer
stopped reading after a refusal and reviewed half the change; `git diff HEAD` also hides new, untracked files,
which the owner had to list by hand for every round.

**Decided (owner, 2026-10-10).** Tell, do not loosen (option A): a "Tools" section in `review-procedure` (Read for
files, `git grep -n -e … -e … -- <paths>` to search and `git ls-files` to list, one command per call,
`git status --short` for new files, never stop reading) and every refusal a reading agent can hit names the
alternative, per hook mode. The first draft pointed agents at the Grep and Glob tools; the architecture review
(arc-3c7e) showed it contradicted `_common.md` and `implementation.md` (those tools do not exist in every Claude
Code version — that reviewer had only Read and Bash), so `git grep` stays the search, as decided there. The allow-list stays as it
is: `cat` would bypass the Read deny rules that keep secrets out of reach, and parsing quotes in the hook to allow
`|` inside them is a classic bypass (a parser that disagrees with the shell). Rejected: loosening the hook
(option B), adding `git -C` (option C, no need once the working directory is stated).

**Delivered.** `readonly-bash.sh` refusals (separators, git options, unsafe `git grep`, `git branch`, anything off
the list) carry a hint naming Read and `git grep -e`, plus the mode's extra commands (`--k8s`, `--gh-read`,
`--architect`); `test-hooks.sh` replays the forms reviewers tried (each refused with the hint; `git diff HEAD`,
`git status --short` and `git grep` with several `-e` pass); `review-procedure` "Tools" and the manual-review step
for untracked files; one line in `ready-checker`, `acceptance-reviewer`, `audit-reviewers`, `planner` and
`architect`, which do not preload the procedure; README "Least privilege per agent". Measured on the first review
with it: the architecture reviewer finished in 66 s, against more than 14 minutes and four refusals before.

---

### 0-122. Protected paths: one list, a Touches gate and the stop stage

**Type:** bug · **Priority:** High · **Status:** Done, PR #478 (found by #0-42's autopilot run, 2026-10-10). Kept in
full as the record of why the protected paths have one list; code comments cite `backlog #0-122`.
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

**Type:** docs · **Priority:** Medium · **Status:** In progress (owner's decision below, 2026-10-25)
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

**Gap to add to `architecture.md` (found in the review of #0-137, 2026-10-15).** No rule says that a platform-wide
infrastructure decision enforced by a CI check needs an ADR. #0-42 and #0-76 got one (ADR-0026, ADR-0027), #0-29 did
not, and #0-137 got ADR-0028 only because the architecture reviewer asked; the reviewer could report it as a rule gap,
not cite it. E.g. "A decision that CI enforces across the platform (a structural check in `.github/scripts/`) is
recorded in an ADR in the same PR. Check: a new rule in a check script points to an ADR, or the PR adds one."

**Decided (owner, 2026-10-25, after `/research`).** One file per PR (A), the pilot first: `performance.md` (13
rules, outside the core files the implementer reads on every item, benchmark patterns P-10…P-12). The form (F1 with a
title, like `factory.md`): `**<ID> <the invariant as a title>.** <what is true of the code>. Check: <what to look for in
a diff>.`; `factory.md` gets its "Check:" lines last. The measure (M2): `/seed-bugs` on the dimension's patterns once the
file is on main (the reviewers read the rules from `refs/remotes/origin/main`, #0-165), not the audit — with four
autopilot PRs so far, an audit's before and after would be noise. No "before" run (the owner, 2026-10-25): one sample
per pattern compares little, and the question is whether the new rules catch, not whether they catch more. An agent drafts
each file, the owner decides and copies it (`.ai/rules/` is the owner's). Then: `security`, `architecture` (with the ADR rule
above), `general`, `migration`, `docs`, `k8s` (ids first), `factory.md`.

**Progress.**
- `performance.md`: rewritten (13 rules, ids unchanged, none retired; PERF-10's startup check and PERF-22's budget
  validation named where they live). `/seed-bugs` P-10…P-12 on main: —.

**Acceptance criteria.**
AC1. Every rule of the rewritten file is an invariant with a "Check:" line, or retired with a reason.
AC2. No id was reused or renumbered.
AC3. Once each rewritten file is on main, `/seed-bugs` on its dimension's patterns has the panel catch each of them
(reworded by the owner, 2026-10-25: the audit had too few autopilot PRs to compare, and a before-and-after of one
sample per pattern compares little).
