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

Every service needs `src/main/resources/application-local.yml` (gitignored, never committed). At minimum `jwt.secret` (≥64 chars — services refuse to start without it). auth-service also needs `mfa.encryption-key`, postmortem-service `gemini.api-key`, incident-service `websocket.allowed-origins`. Full templates in README "Step 2". Use ASCII hyphens only in these files — em dashes break Spring Boot's YAML loading.

## Architecture

Maven multi-module: `shared` (library) + `service-parent` (POM-only) + 7 runnable Spring Boot services. Java 21, virtual threads enabled.

| Service | API | Mgmt | Role |
|---|---|---|---|
| auth-service | 8087 | 8097 | users, teams, API keys, MFA, integrations (modular monolith) |
| ingestion-service | 8081 | 8091 | alert normalization, Redis dedup, bucket4j rate limiting |
| incident-service | 8082 | 8092 | incident FSM, CQRS, WebSocket, central audit consumer |
| notification-service | 8083 | 8093 | Slack / email / SMS channels |
| escalation-service | 8084 | 8094 | `@Scheduled` + ShedLock escalation chain |
| postmortem-service | 8085 | 8095 | Gemini-generated postmortems |
| oncall-service | 8086 | 8096 | on-call schedules |

Event flow: `alerts.raw`/`alerts.resolved` (ingestion → incident) → `incidents.lifecycle` (incident → notification, escalation, postmortem, ingestion; escalation-service also publishes `IncidentEscalatedEvent` back onto it, which incident-service's `IncidentEscalationEventConsumer` reads to update `escalationLevel`) ; every service produces to `audit.events`, consumed only by incident-service's `AuditEventConsumer`. Each service that dead-letters has its own topic (`alerts.dead-letter`, `incidents.dead-letter`, `escalation.dead-letter`, `notification.dead-letter`, `postmortem.dead-letter`).

### `shared` is platform-wide policy, not a utility bag

`shared/src/main/java/com/incidentplatform/shared/` holds security (`JwtAuthFilter`, `JwtUtils`, `TenantContext`, `ServiceTokenProvider`, `ServicePrincipal`, `ServiceNames`, `SecurityRoles`), Kafka tenant interceptors + `TenantKafkaRecordResolver` + `DeadLetterPublisher`, Kafka event records, `AuditEventPublisher`, and `GlobalExceptionHandler`.

`SharedSecurityAutoConfiguration` (registered via `META-INF/spring/...AutoConfiguration.imports`) supplies the default `SecurityFilterChain` and `CorsConfigurationSource` for every service; both are `@ConditionalOnMissingBean`, so a service declaring its own `SecurityFilterChain` takes over completely — including re-wiring CORS and the shared `PUBLIC_PATHS`, which is a recurring source of bugs (see commits `d80541f`, `ec4eae4`). Prefer extending the shared chain's contract over silently forking it.

Changing `shared` changes all 7 services; CI rebuilds every Docker image when it's touched.

### Multi-tenancy invariants

Tenant isolation spans HTTP, Kafka and DB, and is the property most easily broken:

- HTTP: `JwtAuthFilter` sets `TenantContext` + MDC, and also stores the tenant as a request attribute (`TenantContext.REQUEST_ATTRIBUTE_TENANT_ID`) because the ThreadLocal is cleared before the observation filter finishes.
- Kafka: `TenantKafkaProducerInterceptor` stamps `X-Tenant-Id` on every record. Consumers must resolve tenant **per record** via `TenantKafkaRecordResolver` (header first, payload `tenantId` fallback, otherwise dead-letter) and clear `TenantContext` in a `finally` block. Never set tenant from a batch — `TenantKafkaConsumerInterceptor` is a validation layer only.
- Service-to-service HTTP: call with `ServiceTokenProvider.getToken(tenantId, ServiceNames.<TARGET>)` — the target's name, not your own. The token carries the tenant as a signed claim and `aud` names the one service that accepts it; `JwtAuthFilter` builds a `ServicePrincipal` from it and rejects it in any other service (fail closed: a filter with no service name, like auth-service's, accepts none). No filter reads `X-Tenant-Id` on HTTP, so a header alone authenticates nothing. A new HTTP client needs a connect/read timeout. `@AuthenticationPrincipal UserPrincipal` is `null` on a service call, so an endpoint open to `ROLE_SERVICE` must not dereference it. Fail-open client fallbacks must call `ClientFallbackMetrics.record` (backlog #0-11).
- Async: propagate with `TenantAwareTaskDecorator`; don't hand work to a plain executor.
- Queries are always tenant-scoped; `@PreAuthorize`/filter-chain rules use the non-prefixed `hasRole("ADMIN")` form (`SecurityRoles.*_NAME`), while JWT claims and `UserPrincipal.hasRole` use the `ROLE_`-prefixed constants.

### Persistence

All services share one PostgreSQL database (`incidentdb`) but each owns its tables and its own Flyway history table (`flyway_schema_history_<service>`). Migrations live in `<service>/src/main/resources/db/migration/V<n>__snake_case.sql`; `ddl-auto` is `validate`, so a schema change without a migration fails startup. Number new migrations after the highest existing `V<n>` in that service only.

### Patterns already decided

Transactional outbox for `incidents.lifecycle` (`IncidentEventOutbox` + scheduler, backlog #36); ShedLock on every `@Scheduled` job so replicas don't double-fire; optimistic locking (`@Version`) on mutable entities; idempotency checks before any outbound notification (keyed on incident + tenant + event type + escalation level, see `.ai/context/project.md`); 5-layer alert dedup (Redis SETNX/EXPIRE/DEL/AOF + Postgres fingerprint). Rate limiting is bucket4j backed by Redis (`ProxyManager`, `@CircuitBreaker`, fail-open — backlog #67); the earlier in-memory design was reversed. The README's "Design Decisions" section records why alternatives (Spring State Machine, Kafka Streams, full CQRS, RS256/Keycloak) were rejected — read it before proposing one of them.

## Conventions

- **Comment culture**: classes and non-obvious decisions carry Javadoc explaining *why*, including the bug or backlog item that motivated them (`<h2>Fixed (backlog #75): ...</h2>`). Match this density — a change that reverses an earlier decision should say so where the old reasoning lived. Backlog items are referenced as `backlog #N` in commits, Javadoc and config comments; open items are recorded in `BACKLOG.md` (new items are `backlog #0-1`, `#0-2`, ...; the older `backlog #1`–`#82` exist only as references in code). A `TODO` must cite an item — add it to `BACKLOG.md` first.
- **Commits**: Conventional Commits with a service scope — `fix(incident-service): ...`, `feat(oncall): ...`, `refactor: ...`. Branches: `fix/…`, `feat/…`, `refactor/…`, `docs/…`; work lands on `main` via PR.
- **Tests**: `<Class>Test` next to its package under `src/test/java`; Mockito for unit tests, Testcontainers (`postgres:16-alpine`) for repository integration tests — those need Docker running. Coverage gate is 60% overall and on changed files; a PR adding a class with no test will fail the JaCoCo comment gate.
- **`.ai/`**: project knowledge for AI assistants (`.ai/context/project.md`, `.ai/README.md`). Its stated rule: if a change introduces knowledge not inferable from source, update `.ai/` as part of the change.

## CI gotchas

`.github/workflows/ci.yml` runs build+test, a path-filtered Docker image matrix, Kustomize+kubeconform validation of base and all three overlays, and a docker-compose smoke test that boots Postgres/Redis/Kafka plus all 7 services and curls each health endpoint. Two structural rules it enforces:

- Every directory with a `Dockerfile` must have a matching Deployment in `k8s/base` (name-matched in the rendered manifest).
- A new runnable service must parent to `service-parent`, not the root POM — that's where `spring-boot-maven-plugin` is declared, and without it `java -jar app.jar` fails with "no main manifest attribute". `shared` deliberately stays on the root parent.

Security scans (OWASP Dependency-Check, Snyk) run in separate workflows. Unfixable CVEs are suppressed with a justification and expiry in `owasp-suppressions.xml` / `.snyk`; dependency version overrides for CVEs live in the root `pom.xml` properties with a comment naming the CVE.
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
