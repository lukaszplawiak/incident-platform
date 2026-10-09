# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

<!-- agent-editable:start commands — the implementer may correct this block when a command changes; everything outside agent-editable blocks is human-owned (CODEOWNERS) -->

```bash
# The whole docker-compose stack: infrastructure, monitoring, the log pipeline (Loki, Alloy, a Docker socket proxy)
# and, if built, the services — needs docker/.env with the three passwords (see below)
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
<!-- agent-editable:end commands -->

### Local run prerequisite

Every service needs `src/main/resources/application-local.yml` (gitignored, never committed). At minimum `jwt.secret` (≥64 chars — services refuse to start without it). auth-service also needs `mfa.encryption-key` and `slack.encryption-key` (two separate 32-byte keys), postmortem-service `gemini.api-key`, incident-service `websocket.allowed-origins`. Full templates in README "Step 2". Use ASCII hyphens only in these files — em dashes break Spring Boot's YAML loading.

`make dev-up` (and every `docker compose` command) also needs `docker/.env`: `cp docker/.env.example docker/.env`. `DB_PASSWORD` and `POSTGRES_ADMIN_PASSWORD` are required — compose refuses to start without them (backlog #0-78) — and so is `GRAFANA_ADMIN_PASSWORD`, empty in the template on purpose: Grafana reads every tenant's logs, listens on `127.0.0.1` only, and its password applies to a new `grafana_data` volume only (#0-94 step 2). A service run with `spring-boot:run` doesn't read that file: the six services with a database need `spring.datasource.password` in `application-local.yml` (or an exported `DB_PASSWORD`) — `application.yml` has no default (backlog #0-66), and a missing one shows up as `password authentication failed`, not as a placeholder error.

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

Details of `shared`, persistence and the decided patterns: `.ai/context/architecture.md`. Tenant
isolation and authentication: `.ai/context/security.md`. CI rules ("CI gotchas"): `.ai/context/infrastructure.md`.
Which service owns what: `.ai/context/project.md` ("Service Map").

## Invariants: the short version

Each line is a rule that has been broken before. The full rules, and why, are in the file named after
it — read that file before changing code in its area (`.ai/README.md` has the reading order per task).

- **A tenant comes from authentication**, travels in the data, and is never taken from a request body or
  an unsigned header. Every query is tenant-scoped. → `.ai/context/security.md`
- **Kafka**: send only through `TenantRecords.forTenant`; resolve the tenant **per record** with
  `TenantKafkaRecordResolver`; clear `TenantContext` in `finally`; acknowledge only once processed or
  dead-lettered (`DeadLetterPublisher.deadLetterThenAcknowledge`). → `security.md`, `architecture.md`
- **Async** work goes through `TenantAwareTaskDecorator`, never a plain executor. → `security.md`
- **Service-to-service HTTP**: `ServiceTokenProvider.getToken(tenantId, ServiceNames.<TARGET>)`, a
  connect/read timeout, and a fail-open fallback calls `ClientFallbackMetrics.record`. → `security.md`
- **A forked `SecurityFilterChain`** re-wires CORS and `PUBLIC_PATHS`, adds `ApiKeyAuthFilter` itself, and
  ends in `authenticatedExceptPurposeTokens()`. Prefer extending the shared chain. → `architecture.md`
- **Tenant content reaches only members of that tenant**; no platform-wide destination. → `security.md`
- **Changing `shared` changes all 7 services.** → `architecture.md`
- **Migrations**: number after the highest `V<n>` of that service only; `ddl-auto` is `validate`; no
  superuser rights; never edit a migration that is already on `main`. → `architecture.md`
- **Decided patterns**: outbox for `incidents.lifecycle` and audit events, ShedLock on every
  `@Scheduled`, `@Version Long` left uninitialised, `clearAutomatically` always with
  `flushAutomatically`, deadline-bounded retries. → `architecture.md`, `.ai/decisions/`
- **Nothing under `src/main/resources`** carries a Spring profile or a literal secret. → `infrastructure.md`
- **Logs** are one ECS JSON object per line, set by `shared` (`StructuredLoggingDefaults`); nothing deployed
  changes the format, and no log line carries a token, secret, password, key or raw payload. → CLAUDE.md
  "Conventions" (Logs)
- **Before proposing an alternative** (Spring State Machine, Kafka Streams, full CQRS, RS256/Keycloak),
  read README "Design Decisions": they were rejected on purpose.

## AI factory

Work from `BACKLOG.md` can run unattended through the autopilot (`/backlog-autopilot`, a dynamic
workflow in `.claude/workflows/`), in the order of the approved queue `.ai/plan/queue.md`
(`/plan-backlog` proposes it). Its agents, rules and limits are described in `.ai/README.md` and
`docs/ai-factory.md`. Two things matter in any session:

- `.ai/rules/` and `.ai/plan/` are human-owned: agents read them and never edit them (the planner only
  proposes, in a PR). Changes to them, to `.claude/` and to `.github/` go through the owner
  (CODEOWNERS; `/apply-audit` for audit recommendations).
- The "Working style" below is for interactive sessions with the owner. An autopilot agent cannot ask:
  it follows its own definition in `.claude/agents/` (decide and record a reversible choice in an ADR,
  stop the item as BLOCKED on an irreversible or ambiguous one).

## Conventions

- **Comment culture**: classes and non-obvious decisions carry Javadoc explaining *why*, including the bug or backlog item that motivated them (`<h2>Fixed (backlog #75): ...</h2>`). Match this density — a change that reverses an earlier decision should say so where the old reasoning lived. Backlog items are referenced as `backlog #N` in commits, Javadoc and config comments; open items are recorded in `BACKLOG.md`, finished ones in `BACKLOG-DONE.md` (new items are `backlog #0-1`, `#0-2`, ...; the older `backlog #1`–`#82` exist only as references in code). A `TODO` must cite an item — add it to `BACKLOG.md` first.
- **Commits**: Conventional Commits with a service scope — `fix(incident-service): ...`, `feat(oncall): ...`, `refactor: ...`. Branches: `fix/…`, `feat/…`, `refactor/…`, `docs/…`; work lands on `main` via PR.
- **Tests**: `<Class>Test` next to its package under `src/test/java`; Mockito for unit tests, Testcontainers (`postgres:16-alpine`) for repository integration tests, and `apache/kafka` (the compose tag) where only a real broker shows the behaviour (`DeadLetterPublisherKafkaIntegrationTest`, backlog #0-96) — those need Docker running. Coverage is enforced twice (backlog #0-57): `jacoco:check` needs 60% LINE per module in `verify`, and on a PR `diff-cover` needs 60% of the changed Java lines covered, summed over the whole diff (excluded: `*Config`, `*Application`, `dto`, domain events). The madrapps coverage report is informational only and goes to the job summary, not a PR comment (the workflow's `permissions:` keeps the token read-only, backlog #0-59). JaCoCo counts only a module's own tests, so a change to `shared` needs tests in `shared`. Surefire's `<argLine>` must keep `@{argLine}` first, or the JaCoCo agent is dropped and both checks silently skip.
- **Logs**: every service logs one JSON object per line in ECS (`shared`'s `StructuredLoggingDefaults`, backlog #0-94); a service whose logs would not be ECS refuses to start (`StructuredLoggingGuard`: a format other than `ecs`, a Logback file, a reshaped `logging.structured.json.*`), so nothing deployed sets any of them. The one way past it is `platform.logging.plain-text: true` (with an empty console format, for plain text): meant for a developer's gitignored `application-local.yml` (README "Step 2"); the guard cannot tell where it came from, so set elsewhere (a deployment's environment) it works too and is logged as a WARN at every start, and only CI's sixth structural rule keeps it out of tracked files. The encoder escapes every value, so a log line can carry a value from outside; what it must not carry is a token, a secret, a password, a key or a raw payload (a provider's text goes to the log only, never to a tenant-readable record: #0-93). In docker-compose, Alloy ships every `incident-*` container's lines to Loki (15 days, read in Grafana; #0-94 step 2): labels only `service`/`container`/`level`, the MDC ids as structured metadata, never labels (a stream per value); Alloy reads the Docker API only through `docker-socket-proxy`, never the socket; Loki and Alloy (no authentication) are on the internal `logs` network with Grafana and Prometheus only (Alloy also on the internal `docker-api` network, with the proxy alone), and none of the proxy, Loki, Alloy and Grafana is on `default` (Grafana there would be a way around `logs`; the smoke test checks it); Loki's delete API is off. Nothing in k8s (#0-106). Trace ids are #0-94's step 3.
- **Security inventory**: README "Infrastructure Hardening" lists every platform security control by area and every known gap with its backlog item (first written from the 2026-09-30 audit). A change that adds, removes or weakens a control, or closes a gap, updates that section in the same PR.
- **`.ai/`**: project knowledge for AI assistants and the rules the AI factory works by (`.ai/README.md` is the map and the file contract). Its stated rule: if a change introduces knowledge not inferable from source, update `.ai/` as part of the change; a new decision is an ADR in `.ai/decisions/`.

## Working style (read before implementing anything)

> Applies to interactive sessions with the owner. Autopilot agents follow `.claude/agents/<name>.md`
> and `.ai/rules/` instead (see "AI factory" above).

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
