# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Infrastructure (Postgres, Redis, Kafka, Kafka UI, pgAdmin)
make dev-up                 # start        make dev-down   # stop
make dev-reset              # stop + wipe volumes (clean DB)

# Build / test
./mvnw clean install -DskipTests        # full build; required after changing `shared`
./mvnw test -pl incident-service        # one module
./mvnw test -pl incident-service -Dtest=IncidentFsmTest        # one class
./mvnw test -pl incident-service -Dtest=IncidentFsmTest#methodName
./mvnw verify -pl shared,service-parent,auth-service,ingestion-service,incident-service,notification-service,escalation-service,postmortem-service,oncall-service
                                        # what CI runs (tests + JaCoCo)

# Run a service locally (needs `make dev-up` and application-local.yml first)
./mvnw spring-boot:run -pl incident-service -Dspring-boot.run.profiles=local
make run-incident           # same thing; run-ingestion / run-escalation / run-notification / run-postmortem also exist
                            # (no run-auth or run-oncall target — use ./mvnw spring-boot:run -pl <service> ...)

# Validate k8s manifests the way CI does
kubectl kustomize k8s/overlays/dev | kubeconform -strict -summary -
```

`make test-unit` (`-Dgroups="unit"`) is in the Makefile but no test carries a JUnit `unit` tag — it runs nothing. Use `./mvnw test`.

### Local run prerequisite

Every service needs `src/main/resources/application-local.yml` (gitignored, never committed). At minimum `jwt.secret` (≥64 chars — services refuse to start without it). auth-service also needs `mfa.encryption-key` and `slack.encryption-key` (two separate 32-byte keys), postmortem-service `gemini.api-key`, incident-service `websocket.allowed-origins`. Full templates in README "Step 2". Use ASCII hyphens only in these files — em dashes break Spring Boot's YAML loading.

`make dev-up` (and every `docker compose` command) also needs `docker/.env`: `cp docker/.env.example docker/.env`. `DB_PASSWORD` and `POSTGRES_ADMIN_PASSWORD` are required — compose refuses to start without them (backlog #0-78). A service run with `spring-boot:run` doesn't read that file: the six services with a database need `spring.datasource.password` in `application-local.yml` (or an exported `DB_PASSWORD`) — `application.yml` has no default (backlog #0-66), and a missing one shows up as `password authentication failed`, not as a placeholder error.

## Architecture

Maven multi-module: `shared` (library) + `service-parent` (POM-only) + 7 runnable Spring Boot services. Java 21, virtual threads enabled.

| Service | API | Mgmt | Role |
|---|---|---|---|
| auth-service | 8087 | 8097 | users, teams, API keys, MFA, integrations, tenant provisioning (modular monolith) |
| ingestion-service | 8081 | 8091 | alert normalization, Redis dedup, bucket4j rate limiting, Integration API key auth |
| incident-service | 8082 | 8092 | incident FSM, CQRS, WebSocket, central audit consumer |
| notification-service | 8083 | 8093 | Slack / email / SMS channels |
| escalation-service | 8084 | 8094 | `@Scheduled` + ShedLock escalation chain |
| postmortem-service | 8085 | 8095 | Gemini-generated postmortems |
| oncall-service | 8086 | 8096 | on-call schedules |

Event flow: `alerts.raw`/`alerts.resolved` (ingestion → incident) → `incidents.lifecycle` (incident → notification, escalation, postmortem; ingestion-service consumes nothing; escalation-service also publishes `IncidentEscalatedEvent` back onto it, which incident-service's `IncidentEscalationEventConsumer` reads to update `escalationLevel`) ; every service produces to `audit.events`, consumed only by incident-service's `AuditEventConsumer`. Each service that dead-letters has its own topic (`alerts.dead-letter`, `incidents.dead-letter`, `escalation.dead-letter`, `notification.dead-letter`, `postmortem.dead-letter`).

### `shared` is platform-wide policy, not a utility bag

`shared/src/main/java/com/incidentplatform/shared/` holds security (`JwtAuthFilter`, `JwtUtils`, `TenantContext`, `ServiceTokenProvider`, `ServicePrincipal`, `ServiceNames`, `SecurityRoles`), Kafka tenant interceptors + `TenantKafkaRecordResolver` + `DeadLetterPublisher`, Kafka event records, `AuditEventPublisher`, and `GlobalExceptionHandler`.

`SharedSecurityAutoConfiguration` (registered via `META-INF/spring/...AutoConfiguration.imports`) supplies the default `SecurityFilterChain` and `CorsConfigurationSource` for every service; both are `@ConditionalOnMissingBean`, so a service declaring its own `SecurityFilterChain` takes over completely — including re-wiring CORS and the shared `PUBLIC_PATHS`, which is a recurring source of bugs (see commits `d80541f`, `ec4eae4`). Prefer extending the shared chain's contract over silently forking it. A forked chain must also add `ApiKeyAuthFilter` itself if it needs API keys, and end in `anyRequest().access(SharedSecurityAutoConfiguration.authenticatedExceptPurposeTokens())`, not `authenticated()` (backlog #0-16). In auth-service an API key reaches only the routes `SecurityConfig` lists for keys, with the scope each names (`ApiKeyAccess`, backlog #0-89); a new route open to keys must be listed there.

Changing `shared` changes all 7 services; CI rebuilds every Docker image when it's touched.

### Multi-tenancy invariants

Tenant isolation spans HTTP, Kafka and DB, and is the property most easily broken:

- HTTP: `JwtAuthFilter` sets `TenantContext` + MDC, and also stores the tenant as a request attribute (`TenantContext.REQUEST_ATTRIBUTE_TENANT_ID`) because the ThreadLocal is cleared before the observation filter finishes.
- Kafka: `TenantKafkaProducerInterceptor` stamps `X-Tenant-Id` on every record (from `TenantContext`, never overriding a header the sender set; audit, alert and incident-event senders set it explicitly from the payload's tenant — auth-, notification-, postmortem- and oncall-service do not register the interceptor yet, so the dead-letter records of notification- and postmortem-service go without it: backlog #0-91). Consumers must resolve tenant **per record** via `TenantKafkaRecordResolver` (header first, payload `tenantId` fallback, otherwise dead-letter) and clear `TenantContext` in a `finally` block. Never set tenant from a batch — `TenantKafkaConsumerInterceptor` is a validation layer only.
- Service-to-service HTTP: call with `ServiceTokenProvider.getToken(tenantId, ServiceNames.<TARGET>)` — the target's name, not your own. The token carries the tenant as a signed claim and `aud` names the one service that accepts it; `JwtAuthFilter` builds a `ServicePrincipal` from it and rejects it in any other service (fail closed: a filter built with no service name accepts none). auth-service accepts only `aud=auth-service`, and only its one `ROLE_SERVICE` endpoint (`/api/v1/internal/slack-workspace`, read by notification-service) uses it — a service reading auth-service-owned data goes through this narrow HTTP pull, not Kafka replication (backlog #0-21/#0-30). No filter reads `X-Tenant-Id` on HTTP, so a header alone authenticates nothing. A new HTTP client needs a connect/read timeout. `@AuthenticationPrincipal UserPrincipal` is `null` on a service call, so an endpoint open to `ROLE_SERVICE` must not dereference it. Fail-open client fallbacks must call `ClientFallbackMetrics.record` (backlog #0-11).
- The one tenant-less service token: a *purpose token* (`ServiceTokenProvider.getPurposeToken(TokenPurposes.API_KEY_INTROSPECTION, ServiceNames.AUTH_SERVICE)`, backlog #0-16), used by ingestion-service to ask auth-service which tenant an Integration API key belongs to before it knows the tenant. It becomes an `IntrospectionPrincipal` (no tenant), accepted only by a `JwtAuthFilter` built with that purpose and only on `POST /api/v1/internal/api-keys/introspect`; every other route denies it. The body carries the key's SHA-256, never the raw key. Don't add a second purpose without the same deny-by-default.
- Alert sources (incl. the platform's own Alertmanager) authenticate to ingestion-service with an Integration API key (only TENANT keys introspect as active; a PERSONAL key gets `active:false`), validated by that introspection with a 60 s cache: `401` = definite "no", `503` + `Retry-After` = could not check (Alertmanager retries 5xx, drops 4xx). ingestion-service accepts no service tokens. Tenant ids `platform-operator` (the operator tenant that receives the platform's own alerts) and `system` are reserved (`ReservedTenants`).
- Notifications: tenant content (incident title, id, severity) goes only to members of that tenant. No platform-wide destination for it (no fallback address, no platform-wide Slack channel: since backlog #0-21 each tenant's Slack messages go only to its own workspace, with its own opt-in broadcast channel). When nobody in the tenant can be reached the entry becomes `UNDELIVERABLE` and the operator gets a content-free alert (identifiers and reason only). Between teams of one tenant the default is also "no": `teamId` flows from `Incident` through every `IncidentEvent` (`shared`) and `NotificationQueueEntry`, and the PRIMARY on-call lookup is scoped to it via oncall-service's `/current?teamId=...` (backlog #0-12) — `null` for an incident with no team assignment falls back to tenant-wide, same as before #0-12.
- The one cross-tenant capability: tenant provisioning (backlog #0-80). An admin of `platform-operator` with a JWT, in a session that completed MFA within 12 h, with a factor whose MFA_ENABLED email was sent at least 24 h ago (#0-83: `auth_tokens.mfa_verified_at` + `users.mfa_enabled_notice_sent_at`, checked server-side per request from the token's `sessionId` by `MfaSessionStatusService`, no JWT claim; every MFA enable/disable emails the account through the auth email outbox), creates a customer tenant and invites its first admin through `/api/v1/platform/tenants` (auth-service, `TenantProvisioningService`); `PlatformAccess` refuses every other principal (customer admins, password-only sessions, API keys, service and purpose tokens) in the filter chain and on every method. Its writes are rate-limited per operator and in total, fail-closed (`PlatformRateLimiter`). It never acts inside a tenant once that tenant has an admin. Keep it that narrow: a new operator action needs the same rule, audit in the operator tenant, and a backlog decision (suspension/offboarding is #0-82). The operator tenant itself still bootstraps by invite (`OperatorTenantBootstrap`).
- Async: propagate with `TenantAwareTaskDecorator`; don't hand work to a plain executor.
- Queries are always tenant-scoped; `@PreAuthorize`/filter-chain rules use the non-prefixed `hasRole("ADMIN")` form (`SecurityRoles.*_NAME`), while JWT claims and `UserPrincipal.hasRole` use the `ROLE_`-prefixed constants.

### Persistence

All services share one PostgreSQL database (`incidentdb`) but each owns its tables and its own Flyway history table (`flyway_schema_history_<service>`). Migrations live in `<service>/src/main/resources/db/migration/V<n>__snake_case.sql`; `ddl-auto` is `validate`, so a schema change without a migration fails startup. Number new migrations after the highest existing `V<n>` in that service only. A `CREATE INDEX CONCURRENTLY` (for a large, busy table) sits alone in its file, so Flyway runs it outside a transaction, and needs `spring.flyway.postgresql.transactional-lock: false` in that service, or it waits for ever on Flyway's own lock transaction (incident-service V13, backlog #0-84). Every service connects as `incident_app`, the owner of the database and its tables but not a superuser; the image's `POSTGRES_USER` is the admin and no service uses it (backlog #0-78, `docs/database-roles.md`). So a migration must not need superuser rights — only *trusted* extensions, no `ALTER SYSTEM`/`COPY ... PROGRAM`/role changes; the Testcontainers tests connect as a superuser and would not notice, the CI smoke test would (see CI gotchas). As the owner, `incident_app` is still bound by neither grants nor RLS — per-service roles are #0-67. Don't hard-code a role name: probes, CI and the migration read `POSTGRES_USER`/`APP_DB_USER`. The superuser never touches anything outside `pg_catalog` in `incidentdb`, not even to read it — service data, backups and restores go through an `incident_app` login, because `SET ROLE` is no boundary for planted code (guide, "Working as the admin").

### Patterns already decided

Transactional outbox for `incidents.lifecycle` (`IncidentEventOutbox` + scheduler, backlog #36) and for audit events (`AuditOutbox` + `AuditOutboxRelay` in `shared`, a table per service named by `audit.outbox.table`; an event is written in the transaction of the action it records, so an audit call followed by a throw in the same transaction loses its event: audit a refusal after the rollback, as `MfaService`'s verify methods do — backlog #0-84; notification-, escalation- and postmortem-service still send directly until its second step); ShedLock on every `@Scheduled` job so replicas don't double-fire; optimistic locking (`@Version`) on mutable entities, with a `Long` version field left uninitialised — `= 0L` makes Spring Data treat a new entity as existing and `save()` merge instead of persist (backlog #0-47); a bulk `@Modifying(clearAutomatically = true)` query also sets `flushAutomatically = true`, or an earlier change to another table in the same transaction is silently discarded (backlog #0-83); idempotency checks before any outbound notification (keyed on incident + tenant + event type + escalation level, see `.ai/context/project.md`); 5-layer alert dedup (Redis SETNX/EXPIRE/DEL/AOF + Postgres fingerprint). Retries of an outbound send are bounded by a deadline (how long the request is worth sending), not an attempt count: a backoff list whose last step repeats, the last attempt moved to the deadline (`AuthEmailRetryPolicy`, backlog #0-52) — the precedent for #0-32's per-channel notification retry. An outbox row has one writer after its INSERT (the scheduler), and a request path never updates an existing row; with one writer, state-guarded conditional UPDATEs (`WHERE id = ? AND status IN (...)`, row count checked) replace `@Version` — the auth email outbox and `AuthToken.markUsedIfUnused` are the two such exceptions. Emailed credentials are created when the email is sent, and never stored raw. No migration or config holds a user's password: a tenant's first admin is invited by email (`TenantAdminReconciler`; backlog #0-80 removed the seeded `changeme` admin). Rate limiting is bucket4j backed by Redis (`ProxyManager`, `@CircuitBreaker`, fail-open — backlog #67); the earlier in-memory design was reversed. The fail-closed limiters are the platform API's (`PlatformRateLimiter`, #0-83) and the admin MFA reset's (`MfaResetRateLimiter`, #0-88): security controls on rare, privileged operations, where waiting costs nothing and a limit that vanishes with Redis is the wrong default. They share `RateLimitDecision`, `RedisTokenBuckets` and the lazy Redis connection, each with its own circuit breaker. A third kind is `ApiKeyCreationLimit` (#0-89, keys per user per hour): counted in Postgres on `api_keys`' creator index, under a `FOR NO KEY UPDATE NOWAIT` row lock on the creator (a busy row is a 429), so no Redis, no breaker and no fail-open/closed choice; it shares `RateLimitDecision` and the 429 mapping (`RateLimitResponses`). auth-service also runs as a one-off break-glass command (subcommand `break-glass-mfa-reset` as the first argument, options `--break-glass.mfa-reset.*`, backlog #0-88): in that mode `SchedulerConfig` schedules nothing (its `@EnableScheduling` is conditional on `NotBreakGlassCommand`) and `AuthServiceApplication.main` exits with the command's code, so keep both when touching them. The README's "Design Decisions" section records why alternatives (Spring State Machine, Kafka Streams, full CQRS, RS256/Keycloak) were rejected — read it before proposing one of them.

## Conventions

- **Comment culture**: classes and non-obvious decisions carry Javadoc explaining *why*, including the bug or backlog item that motivated them (`<h2>Fixed (backlog #75): ...</h2>`). Match this density — a change that reverses an earlier decision should say so where the old reasoning lived. Backlog items are referenced as `backlog #N` in commits, Javadoc and config comments; open items are recorded in `BACKLOG.md` (new items are `backlog #0-1`, `#0-2`, ...; the older `backlog #1`–`#82` exist only as references in code). A `TODO` must cite an item — add it to `BACKLOG.md` first.
- **Commits**: Conventional Commits with a service scope — `fix(incident-service): ...`, `feat(oncall): ...`, `refactor: ...`. Branches: `fix/…`, `feat/…`, `refactor/…`, `docs/…`; work lands on `main` via PR.
- **Tests**: `<Class>Test` next to its package under `src/test/java`; Mockito for unit tests, Testcontainers (`postgres:16-alpine`) for repository integration tests — those need Docker running. Coverage is enforced twice (backlog #0-57): `jacoco:check` needs 60% LINE per module in `verify`, and on a PR `diff-cover` needs 60% of the changed Java lines covered, summed over the whole diff (excluded: `*Config`, `*Application`, `dto`, domain events). The madrapps coverage report is informational only and goes to the job summary, not a PR comment (the workflow's `permissions:` keeps the token read-only, backlog #0-59). JaCoCo counts only a module's own tests, so a change to `shared` needs tests in `shared`. Surefire's `<argLine>` must keep `@{argLine}` first, or the JaCoCo agent is dropped and both checks silently skip.
- **Security inventory**: README "Infrastructure Hardening" lists every platform security control by area and every known gap with its backlog item (first written from the 2026-09-30 audit). A change that adds, removes or weakens a control, or closes a gap, updates that section in the same PR.
- **`.ai/`**: project knowledge for AI assistants (`.ai/context/project.md`, `.ai/README.md`). Its stated rule: if a change introduces knowledge not inferable from source, update `.ai/` as part of the change.

## CI gotchas

`.github/workflows/ci.yml` runs build+test, a path-filtered Docker image matrix, Kustomize+kubeconform validation of base and all three overlays, a docker-compose smoke test that boots Postgres/Redis/Kafka plus all 7 services and curls each health endpoint (then checks the services' DB role, backlog #0-78; `docker-compose.yml` requires `DB_PASSWORD` and `POSTGRES_ADMIN_PASSWORD`, so the job sets them in its job-level `env` — every compose command, teardown included, interpolates them), `postgres-roles` (`.github/scripts/test-postgres-roles.sh`: the Postgres init script and `docs/database-roles-migrate.sql` against the image named in `docker-compose.yml`; plus `.github/scripts/check-db-password-config.rb` and its bypass test `test-db-password-config.sh`: every committed `spring.datasource.password` is exactly `${DB_PASSWORD}`, no literal or defaulted datasource/Flyway password, backlog #0-66), and `validate-monitoring-config` (backlog #0-16): `promtool check config` + `promtool test rules docker/prometheus.rules.test.yml`, and `amtool check-config` + `amtool config routes test` assertions on `docker/alertmanager.yml` (Watchdog reaches only the dead man's switch, critical platform alerts reach both operator email and the webhook). The smoke test never starts Prometheus/Alertmanager, so this job is the only check of that config. It runs the `prom/prometheus` / `prom/alertmanager` images pinned by tag in the job itself, and those tags must equal the ones in `docker/docker-compose.yml` — bump both together, or CI validates against a different version than compose runs. A change to a route or receiver in `alertmanager.yml` usually needs a matching `routes test` line in the job. Three structural rules it enforces:

- Every directory with a `Dockerfile` must have a matching Deployment in `k8s/base` (name-matched in the rendered manifest).
- A new runnable service must parent to `service-parent`, not the root POM — that's where `spring-boot-maven-plugin` is declared, and without it `java -jar app.jar` fails with "no main manifest attribute". `shared` deliberately stays on the root parent.
- The rendered staging and prod k8s overlays set no Spring profile, in any form (backlog #0-63): only `k8s/overlays/dev` sets `SPRING_PROFILES_ACTIVE`, because the dev profile enables incident-service's unauthenticated `/dev/token`. A real prod profile would need that check changed on purpose.

Every workflow under `.github/workflows/` declares its own `permissions:` at workflow level (`contents: read`; backlog #0-59) instead of relying on the repository's "Workflow permissions" setting. A job that needs more declares its own block, which replaces the workflow's, so it repeats `contents: read`. A job that runs a PR's own code never gets a write scope. No check enforces this — review does.

Every `uses:` is pinned to a full commit SHA with its tag in a comment (`owner/action@<sha> # vX.Y.Z`; backlog #0-60), and every `actions/checkout` sets `persist-credentials: false`. The repository setting `sha_pinning_required` is on (since 2026-09-29, after the #0-60 PR merged and `main`'s run was green), so a workflow with a tag-pinned `uses:` fails to start — current state in README "Infrastructure Hardening", how to resolve a SHA in `.ai/context/project.md`.

Security scans (OWASP Dependency-Check, Snyk) run in separate workflows. The Snyk CLI is pinned by version and SHA-256 in `snyk.yml`'s `env` and the token is set only on the scan step (backlog #0-61); bump version and checksum together by hand, Renovate cannot. Unfixable CVEs are suppressed with a justification and expiry in `owasp-suppressions.xml` / `.snyk`; dependency version overrides for CVEs live in the root `pom.xml` properties with a comment naming the CVE.
## Working style (read before implementing anything)

- **Analysis before code.** For anything beyond a trivial one-line fix:
    1. Describe the current state and its impact on the rest of the system.
    2. Note how this class of problem is typically handled in production systems
       (current best practices, not what's "trendy").
    3. Check whether a similar problem is already solved elsewhere in this codebase —
       decide whether to align with that existing solution for consistency, or whether
       fixing this one thing should also touch the related parts for consistency.
    4. Present each reasonable option with pros/cons — don't silently pick one.
    5. State your recommended approach and why.
- **No shortcuts.** Production-correct over convenient. Don't suggest
  `validate-on-migrate: false`, disabling a check, or a quick workaround to make
  something pass — fix the actual cause. If a proper fix is significantly more work
  than a shortcut, say so explicitly and let me decide, don't default to the easy path.
- **Git commands ready to paste.** When a change is done, give me the exact
  `git add` / `git commit` / branch commands to run — Conventional Commits, scoped
  per service as noted above — not just a description of what to do.
- **Production-grade code quality bar** applies to every change in this repo,
  including learning/throwaway experiments unless I explicitly say otherwise:
  proper error handling, no swallowed exceptions, tests for new logic, no
  `TODO`/`FIXME` left without a backlog reference.
- **Don't guess silently on ambiguity.** If a request could reasonably mean two
  different things (which service, which layer, whether to touch the DB schema),
  ask rather than assuming the simpler interpretation.
- **Delete the branch after every merge.** Once a PR is merged, delete its branch
  both on `origin` (`git push origin --delete <branch>`) and locally
  (`git branch -d <branch>`), after switching back to `main` and pulling. Don't let
  merged branches accumulate.
