# Incident Platform

Incident Platform is a backend system that automates the detection, management, and resolution of production incidents. When a monitoring system detects a problem — high CPU, a security breach, a failed service — the platform ingests the alert, normalizes it from multiple sources (Prometheus, Wazuh), and deduplicates it to prevent noise. It then creates an actionable incident, tracks its full lifecycle from detection to resolution, and automatically notifies the on-call engineer directly via Slack DM, email, and SMS. If no one responds in time, the incident escalates automatically. The system is built for multiple tenants — each organization's data is fully isolated — and every state change is recorded in an audit log for accountability and postmortem analysis. The goal: reduce the time between "something broke" and "someone is fixing it".

---

## Architecture Overview

The platform follows a **microservices architecture** with event-driven communication via Apache Kafka. Each service has a single responsibility and communicates asynchronously.

```
Prometheus / Wazuh / Generic
           │
           ▼
  ┌─────────────────┐
  │ ingestion-service│  REST API (port 8081)
  │                 │  Normalizes alerts, deduplicates via Redis
  │                 │  Consumes incidents.lifecycle (dedup lifecycle)
  └────────┬────────┘
           │ alerts.raw / alerts.resolved
           ▼
  ┌─────────────────┐
  │ incident-service │  REST + WebSocket API (port 8082)
  │                 │  FSM-based lifecycle, PostgreSQL, CQRS
  └────────┬────────┘
           │ incidents.lifecycle
           ├──────────────────────────────┐
           ▼                              ▼
  ┌──────────────────┐        ┌────────────────────────┐
  │escalation-service│        │  notification-service   │
  │                  │        │                         │
  │ Timer-based      │        │  Email (Mailtrap SMTP)  │
  │ escalation via   │        │  Slack (Incoming        │
  │ @Scheduled       │        │  Webhooks)              │
  └──────────────────┘        │  SMS (simulated)        │
                              │  Strategy Pattern       │
                              │  OncallClient →         │
                              └──────────┬──────────────┘
                                         │ HTTP (service JWT)
                                         ▼
                              ┌────────────────────────┐
                              │    oncall-service       │  REST API (port 8086)
                              │                        │  On-call schedule mgmt
                              │  PRIMARY / SECONDARY   │  Who is on-call now?
                              │  / MANAGER roles       │
                              └────────────────────────┘
           │ incidents.lifecycle (IncidentResolvedEvent)
           ▼
  ┌──────────────────┐
  │postmortem-service│  REST API (port 8085)
  │                  │  Gemini API integration
  └──────────────────┘
```

---

## Services

### ingestion-service (port 8081)
Receives raw alerts from external monitoring systems and normalizes them into a unified format before publishing to Kafka.

- **Alert sources**: Prometheus (batch with firing/resolved), Wazuh (SIEM), Generic (custom)
- **Deduplication**: Redis SETNX with 5-minute TTL, extended to 7 days on `IncidentOpenedEvent`, cleared on `IncidentResolvedEvent` — prevents duplicate incidents for the lifetime of an active incident
- **Dedup lifecycle consumer**: Consumes `incidents.lifecycle` to manage Redis key TTL in sync with incident state
- **Circuit breaker**: Resilience4j circuit breaker on Redis — when Redis is unavailable, alerts pass through and `incident-service` idempotency layer prevents duplicates
- **Dead Letter Queue**: Failed messages published to `alerts.dead-letter` topic
- **Fingerprinting**: Each alert gets a deterministic fingerprint (`source:alertname:labels`) for dedup and auto-resolve matching
- **Request size limits**: Max 1MB per request — protection against oversized payloads

### incident-service (port 8082)
Core domain service. Manages the full lifecycle of incidents.

- **State Machine (FSM)**: Custom lightweight FSM — `OPEN → ACKNOWLEDGED → ESCALATED → RESOLVED → CLOSED`
- **CQRS**: Separate `IncidentCommandService` and `IncidentQueryService`
- **Optimistic locking**: `@Version` on the `Incident` entity prevents concurrent update conflicts
- **Idempotency**: Before creating a new incident, checks if an active incident with the same fingerprint already exists — defense-in-depth against Redis dedup failures
- **WebSocket**: Real-time incident updates via STOMP over `/ws`
- **Audit log**: Every state transition recorded in `incident_history` table
- **MTTA/MTTR**: Calculated from timestamps stored on the entity
- **Multi-tenancy**: Every query scoped by `tenantId` — `findByIdAndTenantId` pattern throughout

### notification-service (port 8083)
Consumes incident lifecycle events and delivers notifications through multiple channels.

- **Strategy Pattern**: `NotificationChannel` interface with `EmailNotificationChannel`, `SlackNotificationChannel`, `SmsNotificationChannel`
- **On-call integration**: `OncallClient` queries `oncall-service` before sending — notifications go to the current on-call engineer, not a hardcoded address
- **Fallback**: When `oncall-service` is unavailable (circuit breaker OPEN), falls back to configured default addresses — notifications always go out
- **Real integrations**: Email via Spring `JavaMailSender` + Mailtrap SMTP; Slack via Incoming Webhooks API + `RestClient`
- **Routing logic**: `NotificationRouter` maps event types to channels (escalations → EMAIL + SLACK + SMS)
- **Idempotency**: Checks `notification_log` before sending — Kafka redelivery never causes duplicate notifications
- **Audit log**: Every notification attempt recorded in `notification_log` (SENT / FAILED / SKIPPED)
- **Fault isolation**: Failure of one channel does not block others

### escalation-service (port 8084)
Monitors open incidents and automatically escalates those not acknowledged within a configurable time window.

- **Timer-based escalation**: `@Scheduled` checks every N seconds for overdue incidents
- **EscalationTask**: Each opened incident gets a scheduled task in PostgreSQL with a configurable threshold (default: 15 minutes, 2 minutes locally for testing)
- **Idempotency**: Re-delivery of `IncidentOpenedEvent` from Kafka does not create duplicate tasks
- **ACK cancellation**: `IncidentAcknowledgedEvent` cancels the pending escalation task before the timer fires
- **Event publishing**: Publishes `IncidentEscalatedEvent` back to `incidents.lifecycle` — picked up by notification-service and incident-service

### postmortem-service (port 8085)
Automatically generates postmortem drafts using the Gemini AI API after incidents are resolved.

- **Triggered by**: `IncidentResolvedEvent` from `incidents.lifecycle`
- **Gemini integration**: HTTP call via `RestClient` — no SDK, full vendor neutrality through `GeminiClient` interface
- **Resilience**: Resilience4j retry + circuit breaker on Gemini API calls; `PostmortemRetryScheduler` retries FAILED postmortems
- **Lifecycle**: `GENERATING → DRAFT → REVIEWED` (or `FAILED` on API error)
- **Prompt engineering**: Structured prompt with incident title, severity, duration, and timeline — generates sections: Summary, Timeline, Root Cause, Impact, Resolution, Action Items, Lessons Learned
- **REST API**: Engineers can retrieve and edit generated drafts via `GET/PATCH /api/v1/postmortems/incident/{id}`
- **Fault tolerance**: Gemini API errors are caught and recorded — never block the Kafka consumer

### oncall-service (port 8086)
Manages on-call schedules and answers "who is on-call right now?" for each tenant.

- **Schedule management**: REST API for creating, listing and deleting on-call schedule entries
- **Three roles**: `PRIMARY` (first responder), `SECONDARY` (backup), `MANAGER` (escalation) — all optional, system works with any combination
- **Slack DM support**: Stores `slackUserId` per engineer — enables direct Slack DM to the on-call person instead of a generic channel post
- **Service-to-service auth**: Endpoint `/api/v1/oncall/current` requires `ROLE_SERVICE` JWT — only internal services can query it
- **Multi-tenancy**: Each tenant has independent on-call schedules
- **Overlap detection**: Creating a schedule that overlaps an existing one for the same role is rejected at the application level

---

## Tech Stack

| Category | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5 |
| Messaging | Apache Kafka (KRaft mode) |
| Database | PostgreSQL 16 + Flyway migrations |
| Cache / Dedup | Redis 7 (AOF persistence enabled) |
| Security | Spring Security + JWT (JJWT HS512) |
| Real-time | WebSocket (STOMP) |
| Email | Spring Mail + Mailtrap SMTP |
| Slack | Incoming Webhooks + RestClient |
| AI | Gemini API via HTTP (RestClient) |
| Resilience | Resilience4j (circuit breaker, retry) |
| API Docs | SpringDoc OpenAPI 3 |
| Build | Maven (multi-module) |
| Observability | Spring Actuator, SLF4J + MDC (tenantId, requestId, userId) |
| CI | GitHub Actions |
| Containers | Docker Compose |

---

## Production Hardening

The system includes multiple layers of defense against real-world failure scenarios.

### Alert Deduplication — Defense in Depth

Five layers prevent duplicate incidents even under partial failures:

| Layer | Mechanism | Protects against |
|---|---|---|
| 1 | Redis SETNX short TTL (5 min) | Burst of duplicate alerts |
| 2 | Redis EXPIRE 7 days on `IncidentOpenedEvent` | Alert flood during active incident |
| 3 | Redis DEL on `IncidentResolvedEvent` | Stale dedup block after resolution |
| 4 | Redis AOF persistence | Dedup state loss on Redis restart |
| 5 | `incident-service` fingerprint check | Redis unavailability, race conditions |

### Edge Cases Handled

**TTL expiry during active incident** — When `IncidentOpenedEvent` is consumed by `ingestion-service`, the Redis key TTL is extended to 7 days. When `IncidentResolvedEvent` arrives, the key is deleted immediately. If the resolved event is lost (Kafka failure), the 7-day TTL acts as a safety net.

**Resolved before fired** — If Prometheus sends a `resolved` event before the `firing` event arrives (network reorder), `incident-service` safely ignores the resolved event. The subsequent fired alert creates a new incident normally.

**Redis circuit breaker** — When Redis is unavailable, `DeduplicationService` opens a Resilience4j circuit breaker and lets alerts through. The `incident-service` fingerprint check (layer 5) prevents duplicate incidents.

**Notification deduplication** — `notification-service` checks `notification_log` before sending. Kafka at-least-once delivery never results in duplicate emails or Slack messages.

### Security

- **JWT secret**: No default value — application refuses to start without `JWT_SECRET` environment variable set explicitly
- **Service-to-service auth**: Internal HTTP calls between services use `ServiceTokenProvider` which generates and caches JWT tokens with `ROLE_SERVICE` — not exposed to end users
- **Dev endpoints**: `DevTokenController` is gated with `@Profile("local")` — never available in production
- **Request size limits**: `ingestion-service` rejects payloads over 1MB — protection against DoS via oversized alerts

### Kafka Consumer Reliability

All consumer services configure explicit timeouts to prevent rebalancing storms:

```yaml
spring:
  kafka:
    consumer:
      properties:
        max.poll.interval.ms: 30000   # max processing time per batch
        session.timeout.ms: 15000     # broker considers consumer dead after this
        heartbeat.interval.ms: 5000   # must be < session.timeout / 3
        max.poll.records: 10          # limits records per poll cycle
```

---

## Design Decisions

**Why a custom FSM instead of Spring State Machine?**
Spring State Machine adds significant complexity and weight for a portfolio project. A simple `Map<IncidentStatus, Set<IncidentStatus>>` of allowed transitions is transparent, easily testable, and sufficient for this use case.

**Why CQRS without separate databases?**
Full CQRS with read replicas is overkill here. The lightweight split between `CommandService` and `QueryService` within the same PostgreSQL instance demonstrates understanding of the pattern without unnecessary infrastructure.

**Why Consumer-Driven Contracts for notification-service?**
The notification consumer deserializes Kafka messages to `JsonNode` and extracts only the fields it needs. This decouples the consumer from the exact producer schema — a change to `IncidentOpenedEvent` that adds new fields won't break notification-service.

**Why separate DLQ strategies for ingestion vs. incident?**
- `ingestion-service` uses a **custom** `DeadLetterPublisher` because it processes batches (one bad alert should not block the rest)
- `incident-service` uses **Spring Kafka DLT** because it processes single messages and Spring handles retries correctly

**Why `@Scheduled` for escalation instead of Kafka Streams?**
Kafka Streams would require windowing, state stores, and a significantly more complex setup. `@Scheduled` with a PostgreSQL-backed task table is transparent, easily testable with Mockito, and sufficient for this use case. On production the same pattern can be replaced with Quartz or a dedicated job scheduler without changing the business logic.

**Why no Gemini SDK for postmortem-service?**
Using the raw HTTP API via `RestClient` keeps the integration vendor-neutral — the `GeminiClient` interface means switching to a different AI provider requires changing only one class. It also avoids an additional Maven dependency and makes the HTTP contract explicit and debuggable.

**Why a separate oncall-service instead of extending notification-service?**
On-call schedule management is a distinct bounded context. A separate service allows independent scaling, independent deployment, and future extension (e.g. PagerDuty integration, calendar sync) without touching the notification pipeline. The `OncallClient` interface in `notification-service` keeps the coupling minimal — if `oncall-service` is unavailable, notification-service falls back to configured defaults.

**Why HS512 for JWT instead of RS256 or Keycloak?**
HS512 with a shared secret is sufficient for a controlled environment where all services are owned by the same team. The tradeoff is documented and understood: a single compromised secret affects all services. The `ServiceTokenProvider` is already abstracted behind an interface — migrating to RS256 or Keycloak requires changing one class per service.

---

## Running Locally

**Prerequisites**: Java 21, Docker Desktop, Maven

```bash
# 1. Start infrastructure
docker-compose -f docker/docker-compose.yml up -d

# 2. Build all modules
./mvnw clean install -DskipTests

# 3. Start services (separate terminals)
./mvnw spring-boot:run -pl ingestion-service -Dspring-boot.run.profiles=local
./mvnw spring-boot:run -pl incident-service -Dspring-boot.run.profiles=local
./mvnw spring-boot:run -pl notification-service -Dspring-boot.run.profiles=local
./mvnw spring-boot:run -pl escalation-service -Dspring-boot.run.profiles=local
./mvnw spring-boot:run -pl postmortem-service -Dspring-boot.run.profiles=local
./mvnw spring-boot:run -pl oncall-service -Dspring-boot.run.profiles=local
```

**Required in `application-local.yml` per service**:
```yaml
# All services require:
jwt:
  secret: local-development-secret-key-minimum-32-characters-long-not-for-production

# postmortem-service additionally requires:
gemini:
  api-key: your-api-key-here
```

**Infrastructure ports**:
- Kafka UI: http://localhost:8090
- pgAdmin: http://localhost:5050

**Service ports**:

| Service | API | Management |
|---|---|---|
| ingestion-service | 8081 | 8091 |
| incident-service | 8082 | 8092 |
| notification-service | 8083 | 8093 |
| escalation-service | 8084 | 8094 |
| postmortem-service | 8085 | 8095 |
| oncall-service | 8086 | 8096 |

---

## End-to-End Test

```bash
# Get a dev token (local profile only)
TOKEN=$(curl -s "http://localhost:8081/dev/token?userId=user-1&tenantId=acme-corp" | jq -r '.token')

# (Optional) Register an on-call schedule so notifications go to the right person
curl -X POST http://localhost:8086/api/v1/oncall/schedules \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "userId": "user-1",
    "userName": "Jan Kowalski",
    "email": "jan@acme-corp.com",
    "slackUserId": "U0123456789",
    "role": "PRIMARY",
    "startsAt": "2024-01-01T00:00:00Z",
    "endsAt": "2024-12-31T23:59:59Z"
  }'

# Send a firing alert
curl -X POST http://localhost:8081/api/v1/alerts/prometheus \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "alerts": [{
      "status": "firing",
      "labels": {
        "alertname": "HighCpuUsage",
        "severity": "critical",
        "instance": "prod-server-1:9100"
      },
      "annotations": {
        "summary": "High CPU Usage on prod-server-1"
      }
    }]
  }'
```

After sending the alert:
- An incident is created in PostgreSQL with status `OPEN`
- `IncidentOpenedEvent` is published to `incidents.lifecycle`
- `ingestion-service` extends the Redis dedup TTL to 7 days
- `notification-service` queries `oncall-service` for the current PRIMARY on-call
- notification-service sends an email and Slack message to the on-call engineer
- escalation-service schedules an escalation task (fires after 2 minutes locally)
- After 2 minutes without ACK: `IncidentEscalatedEvent` is published, notification-service sends EMAIL + SLACK + SMS

To test the postmortem flow, resolve the incident via the incident-service API:
```bash
curl -X POST http://localhost:8082/api/v1/incidents/{incidentId}/resolve \
  -H "Authorization: Bearer $TOKEN"
```
- `IncidentResolvedEvent` is published to `incidents.lifecycle`
- `ingestion-service` deletes the Redis dedup key — same alert can create a new incident
- postmortem-service generates a draft via Gemini API
- Draft is available at `GET http://localhost:8085/api/v1/postmortems/incident/{incidentId}`

---

## Running Tests

```bash
# All unit tests
./mvnw test -pl shared,ingestion-service,incident-service,notification-service,escalation-service,postmortem-service,oncall-service

# Single module
./mvnw test -pl notification-service
```

**Test coverage highlights**:
- `IncidentFsmTest` — 25 combinations of allowed/forbidden state transitions
- `NotificationRouterTest` — routing logic for all 5 event types, fallback when oncall-service unavailable
- `NotificationServiceTest` — orchestration, fault isolation, idempotency, audit log
- `JwtUtilsTest` — token generation, validation, expiry, edge cases
- `EscalationServiceTest` — scheduling, cancellation, idempotency
- `EscalationSchedulerTest` — timer logic, fault isolation across multiple tasks
- `PostmortemServiceTest` — generation, Gemini failure handling, CRUD operations
- `PostmortemRetrySchedulerTest` — retry logic for FAILED postmortems
- `OncallScheduleServiceTest` — schedule creation, overlap detection, current on-call resolution

---

## Project Structure

```
incident-platform/
├── shared/                  # Common: events, DTOs, security (JWT, TenantContext, ServiceTokenProvider)
├── ingestion-service/       # Alert normalization, deduplication, incident lifecycle consumer
├── incident-service/        # Incident lifecycle management
├── notification-service/    # Multi-channel notifications with on-call routing
├── escalation-service/      # Automatic escalation via @Scheduled
├── postmortem-service/      # AI-generated postmortems via Gemini API
├── oncall-service/          # On-call schedule management
└── docker/
    └── docker-compose.yml   # PostgreSQL, Redis (AOF), Kafka, pgAdmin, Kafka UI
```

---

## Author

Built by Łukasz Pławiak as a portfolio project demonstrating production-oriented Java/Spring Boot backend development.