# Incident Platform

Incident Platform is a backend system that automates the detection, management, and resolution of production incidents. When a monitoring system detects a problem — high CPU, a security breach, a failed service — the platform ingests the alert, normalizes it from multiple sources (Prometheus, Wazuh), and deduplicates it to prevent noise. It then creates an actionable incident, tracks its full lifecycle from detection to resolution, and automatically notifies on-call engineers via Slack, email, and SMS. If no one responds in time, the incident escalates automatically. The system is built for multiple tenants — each organization's data is fully isolated — and every state change is recorded in an audit log for accountability and postmortem analysis. The goal: reduce the time between "something broke" and "someone is fixing it".

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
  ┌──────────────────┐        ┌───────────────────────┐
  │escalation-service│        │ notification-service   │
  │                  │        │                        │
  │ Timer-based      │        │ Email (Mailtrap SMTP)  │
  │ escalation via   │        │ Slack (Incoming        │
  │ @Scheduled       │        │ Webhooks)              │
  └──────────────────┘        │ SMS (simulated)        │
                              │ Strategy Pattern       │
                              └───────────────────────┘
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
- **Deduplication**: Redis SETNX with 5-minute TTL — prevents duplicate incidents from repeated alerts
- **Dead Letter Queue**: Failed messages published to `alerts.dead-letter` topic
- **Fingerprinting**: Each alert gets a deterministic fingerprint (`source:alertname:labels`) for dedup and auto-resolve matching

### incident-service (port 8082)
Core domain service. Manages the full lifecycle of incidents.

- **State Machine (FSM)**: Custom lightweight FSM — `OPEN → ACKNOWLEDGED → ESCALATED → RESOLVED → CLOSED`
- **CQRS**: Separate `IncidentCommandService` and `IncidentQueryService`
- **Optimistic locking**: `@Version` on the `Incident` entity prevents concurrent update conflicts
- **WebSocket**: Real-time incident updates via STOMP over `/ws`
- **Audit log**: Every state transition recorded in `incident_history` table
- **MTTA/MTTR**: Calculated from timestamps stored on the entity
- **Multi-tenancy**: Every query scoped by `tenantId` — `findByIdAndTenantId` pattern throughout

### notification-service (port 8083)
Consumes incident lifecycle events and delivers notifications through multiple channels.

- **Strategy Pattern**: `NotificationChannel` interface with `EmailNotificationChannel`, `SlackNotificationChannel`, `SmsNotificationChannel`
- **Real integrations**: Email via Spring `JavaMailSender` + Mailtrap SMTP; Slack via Incoming Webhooks API + `RestClient`
- **Routing logic**: `NotificationRouter` maps event types to channels (escalations → EMAIL + SLACK + SMS)
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
- **Lifecycle**: `GENERATING → DRAFT → REVIEWED` (or `FAILED` on API error)
- **Prompt engineering**: Structured prompt with incident title, severity, duration, and timeline — generates sections: Summary, Timeline, Root Cause, Impact, Resolution, Action Items, Lessons Learned
- **REST API**: Engineers can retrieve and edit generated drafts via `GET/PATCH /api/v1/postmortems/incident/{id}`
- **Fault tolerance**: Gemini API errors are caught and recorded — never block the Kafka consumer

---

## Tech Stack

| Category | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5 |
| Messaging | Apache Kafka (KRaft mode) |
| Database | PostgreSQL 16 + Flyway migrations |
| Cache / Dedup | Redis 7 |
| Security | Spring Security + JWT (JJWT HS512) |
| Real-time | WebSocket (STOMP) |
| Email | Spring Mail + Mailtrap SMTP |
| Slack | Incoming Webhooks + RestClient |
| AI | Gemini API via HTTP (RestClient) |
| API Docs | SpringDoc OpenAPI 3 |
| Build | Maven (multi-module) |
| Observability | Spring Actuator, SLF4J + MDC (tenantId, requestId, userId) |
| CI | GitHub Actions |
| Containers | Docker Compose |

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
```

**Note**: postmortem-service requires a Gemini API key in `postmortem-service/src/main/resources/application-local.yml`:
```yaml
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

---

## End-to-End Test

```bash
# Get a dev token (local profile only)
TOKEN=$(curl -s "http://localhost:8081/dev/token?userId=user-1&tenantId=acme-corp" | jq -r '.token')

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
- notification-service sends an email (Mailtrap) and a Slack message
- escalation-service schedules an escalation task (fires after 2 minutes locally)
- After 2 minutes without ACK: `IncidentEscalatedEvent` is published, notification-service sends EMAIL + SLACK + SMS

To test the postmortem flow, resolve the incident via the incident-service API:
```bash
curl -X POST http://localhost:8082/api/v1/incidents/{incidentId}/resolve \
  -H "Authorization: Bearer $TOKEN"
```
- `IncidentResolvedEvent` is published to `incidents.lifecycle`
- postmortem-service generates a draft via Gemini API
- Draft is available at `GET http://localhost:8085/api/v1/postmortems/incident/{incidentId}`

---

## Running Tests

```bash
# All unit tests
./mvnw test -pl shared,ingestion-service,incident-service,notification-service,escalation-service,postmortem-service

# Single module
./mvnw test -pl notification-service
```

**Test coverage highlights**:
- `IncidentFsmTest` — 25 combinations of allowed/forbidden state transitions
- `NotificationRouterTest` — routing logic for all 5 event types
- `NotificationServiceTest` — orchestration, fault isolation, audit log
- `JwtUtilsTest` — token generation, validation, expiry, edge cases
- `EscalationServiceTest` — scheduling, cancellation, idempotency
- `EscalationSchedulerTest` — timer logic, fault isolation across multiple tasks
- `PostmortemServiceTest` — generation, Gemini failure handling, CRUD operations

---

## Project Structure

```
incident-platform/
├── shared/                  # Common: events, DTOs, security (JWT, TenantContext)
├── ingestion-service/       # Alert normalization and deduplication
├── incident-service/        # Incident lifecycle management
├── notification-service/    # Multi-channel notifications
├── escalation-service/      # Automatic escalation via @Scheduled
├── postmortem-service/      # AI-generated postmortems via Gemini API
└── docker/
    └── docker-compose.yml   # PostgreSQL, Redis, Kafka, pgAdmin, Kafka UI
```

---

## Author

Built by Łukasz Pławiak as a portfolio project demonstrating production-oriented Java/Spring Boot backend development.