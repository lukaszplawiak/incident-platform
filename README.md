# Incident Platform

A production-grade, multi-tenant incident management platform built as a portfolio project to demonstrate backend engineering skills. Inspired by tools like PagerDuty and OpsGenie, the system ingests alerts from monitoring sources, creates and manages incidents, escalates unacknowledged incidents, and notifies on-call engineers through multiple channels.

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
  │postmortem-service│  Gemini API integration (planned)
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

### escalation-service (port 8084) — in progress
Monitors open incidents and escalates those not acknowledged within a configurable time window.

### postmortem-service (port 8085) — planned
Automatically generates postmortem drafts using the Gemini API after incidents are resolved.

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

---

## Running Tests

```bash
# All unit tests
./mvnw test -pl shared,ingestion-service,incident-service,notification-service

# Single module
./mvnw test -pl notification-service
```

**Test coverage highlights**:
- `IncidentFsmTest` — 25 combinations of allowed/forbidden state transitions
- `NotificationRouterTest` — routing logic for all 5 event types
- `NotificationServiceTest` — orchestration, fault isolation, audit log
- `JwtUtilsTest` — token generation, validation, expiry, edge cases

---

## Project Structure

```
incident-platform/
├── shared/                  # Common: events, DTOs, security (JWT, TenantContext)
├── ingestion-service/       # Alert normalization and deduplication
├── incident-service/        # Incident lifecycle management
├── escalation-service/      # Automatic escalation (in progress)
├── notification-service/    # Multi-channel notifications
├── postmortem-service/      # AI-generated postmortems (planned)
└── docker/
    └── docker-compose.yml   # PostgreSQL, Redis, Kafka, pgAdmin, Kafka UI
```

---

## Author

Built by Łukasz Pławiak as a portfolio project demonstrating production-oriented Java/Spring Boot backend development.