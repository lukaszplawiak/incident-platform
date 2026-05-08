# Incident Platform

Incident Platform is a backend system that automates the detection, management, and resolution of production incidents. When a monitoring system detects a problem — high CPU, a security breach, a failed service — the platform ingests the alert, normalizes it from multiple sources (Prometheus, Wazuh), and deduplicates it to prevent noise. It then creates an actionable incident, tracks its full lifecycle from detection to resolution, and automatically notifies the on-call engineer directly via Slack DM, email, and SMS. If no one responds in time, the incident escalates automatically through a configurable chain — from the primary on-call, to secondary, to manager. The system is built for multiple tenants — each organization's data is fully isolated — and every state change is recorded in a centralized audit log for accountability and postmortem analysis. The goal: reduce the time between "something broke" and "someone is fixing it".

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
  │                 │  Rate limiting per tenant + per IP
  │                 │  Consumes incidents.lifecycle (dedup lifecycle)
  └────────┬────────┘
           │ alerts.raw / alerts.resolved
           ▼
  ┌─────────────────┐
  │ incident-service │  REST + WebSocket API (port 8082)
  │                 │  FSM-based lifecycle, PostgreSQL, CQRS
  │                 │  Centralized audit log consumer
  └────────┬────────┘
           │ incidents.lifecycle
           ├──────────────────────────────┐
           ▼                              ▼
  ┌──────────────────┐        ┌────────────────────────┐
  │escalation-service│        │  notification-service   │
  │                  │        │                         │
  │ 2-level chain:   │        │  Slack Bot Token (DM)   │
  │ PRIMARY→SECONDARY│        │  Email (Mailtrap SMTP)  │
  │ →MANAGER         │        │  SMS (simulated)        │
  │ Timeouts per     │        │  Strategy Pattern       │
  │ severity         │        │  OncallClient →         │
  └──────────────────┘        └──────────┬──────────────┘
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

  All services → audit.events → incident-service audit consumer → audit_events table
```

---

## Services

### ingestion-service (port 8081)
Receives raw alerts from external monitoring systems and normalizes them into a unified format before publishing to Kafka.

- **Alert sources**: Prometheus (batch with firing/resolved), Wazuh (SIEM), Generic (custom)
- **Deduplication**: Redis SETNX with 5-minute TTL, extended to 7 days on `IncidentOpenedEvent`, cleared on `IncidentResolvedEvent` — prevents duplicate incidents for the lifetime of an active incident
- **Dedup lifecycle consumer**: Consumes `incidents.lifecycle` to manage Redis key TTL in sync with incident state
- **Circuit breaker**: Resilience4j circuit breaker on Redis — when Redis is unavailable, alerts pass through and `incident-service` idempotency layer prevents duplicates
- **Rate limiting**: bucket4j per-tenant + per-IP rate limiting — defense-in-depth against DDoS and noisy neighbors
- **Dead Letter Queue**: Failed messages published to `alerts.dead-letter` topic
- **Fingerprinting**: Each alert gets a deterministic fingerprint (`source:alertname:labels`) for dedup and auto-resolve matching
- **Request size limits**: Max 1MB per request — protection against oversized payloads

### incident-service (port 8082)
Core domain service. Manages the full lifecycle of incidents.

- **State Machine (FSM)**: Custom lightweight FSM — `OPEN → ACKNOWLEDGED → ESCALATED → RESOLVED → CLOSED`
- **CQRS**: Separate `IncidentCommandService` and `IncidentQueryService`
- **Optimistic locking**: `@Version` on the `Incident` entity prevents concurrent update conflicts — HTTP 409 Conflict returned on collision
- **Idempotency**: Before creating a new incident, checks if an active incident with the same fingerprint already exists — defense-in-depth against Redis dedup failures
- **WebSocket**: Real-time incident updates via STOMP over `/ws`
- **Centralized audit log**: `AuditEventConsumer` collects events from all services via `audit.events` Kafka topic and persists them to `audit_events` table
- **Audit API**: `GET /api/v1/incidents/{id}/audit` — full chronological timeline of every event for an incident
- **MTTA/MTTR**: Calculated from timestamps stored on the entity
- **Multi-tenancy**: Every query scoped by `tenantId` — `findByIdAndTenantId` pattern throughout

### notification-service (port 8083)
Consumes incident lifecycle events and delivers notifications through multiple channels.

- **Strategy Pattern**: `NotificationChannel` interface with `EmailNotificationChannel`, `SlackNotificationChannel`, `SmsNotificationChannel`
- **Slack Bot Token**: Uses `chat.postMessage` API — sends DM directly to the on-call engineer's Slack User ID AND posts to `#incidents` channel for team visibility
- **On-call integration**: `OncallClient` queries `oncall-service` before sending — notifications go to the current on-call engineer, not a hardcoded address
- **Fallback**: When `oncall-service` is unavailable (circuit breaker OPEN), falls back to configured default addresses — notifications always go out
- **Real integrations**: Email via Spring `JavaMailSender` + Mailtrap SMTP; Slack via Bot Token + `RestClient`
- **Routing logic**: `NotificationRouter` maps event types to channels (escalations → EMAIL + SLACK + SMS)
- **Idempotency**: Checks `notification_log` before sending — Kafka redelivery never causes duplicate notifications
- **Fault isolation**: Failure of one channel does not block others

### escalation-service (port 8084)
Monitors open incidents and automatically escalates those not acknowledged within a configurable time window.

- **Two-level escalation chain**: Level 1 notifies SECONDARY on-call, Level 2 notifies MANAGER — if no ACK at any level
- **Severity-based timeouts**: CRITICAL=5min, HIGH=15min, MEDIUM=30min, LOW=60min — urgency matches incident severity
- **Timer-based escalation**: `@Scheduled` + ShedLock checks every N seconds for overdue incidents — safe in multi-instance deployments
- **EscalationTask**: Each opened incident gets a scheduled task in PostgreSQL — one per level
- **Idempotency**: Re-delivery of `IncidentOpenedEvent` from Kafka does not create duplicate tasks
- **ACK cancellation**: `IncidentAcknowledgedEvent` cancels ALL pending escalation tasks (level 1 and 2)
- **Event publishing**: Publishes `IncidentEscalatedEvent` with escalation level back to `incidents.lifecycle`

### postmortem-service (port 8085)
Automatically generates postmortem drafts using the Gemini AI API after incidents are resolved.

- **Triggered by**: `IncidentResolvedEvent` from `incidents.lifecycle`
- **Gemini integration**: HTTP call via `RestClient` — no SDK, full vendor neutrality through `GeminiClient` interface. API key passed via `x-goog-api-key` header (not query param) per Google security best practices
- **Resilience**: Resilience4j retry + circuit breaker on Gemini API calls; `PostmortemRetryScheduler` retries FAILED postmortems
- **Lifecycle**: `GENERATING → DRAFT → REVIEWED` (or `FAILED` on API error)
- **Prompt engineering**: Structured prompt with incident title, severity, duration, and timeline — generates sections: Summary, Timeline, Root Cause, Impact, Resolution, Action Items, Lessons Learned
- **REST API**: Engineers can retrieve and edit generated drafts via `GET/PATCH /api/v1/postmortems/incident/{id}`
- **Fault tolerance**: Gemini API errors are caught and recorded — never block the Kafka consumer

### oncall-service (port 8086)
Manages on-call schedules and answers "who is on-call right now?" for each tenant.

- **Schedule management**: REST API for creating, listing and deleting on-call schedule entries
- **Three roles**: `PRIMARY` (first responder), `SECONDARY` (backup), `MANAGER` (escalation) — all optional, system works with any combination
- **Slack DM support**: Stores `slackUserId` per engineer — enables direct Slack DM to the on-call person
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
| Slack | Bot Token + chat.postMessage API |
| AI | Gemini API via HTTP (RestClient) |
| Resilience | Resilience4j (circuit breaker, retry) |
| Rate Limiting | bucket4j (per-tenant + per-IP) |
| API Docs | SpringDoc OpenAPI 3 |
| Build | Maven (multi-module) |
| Observability | Micrometer + Prometheus + Grafana, SLF4J + MDC (tenantId, requestId, userId) |
| CI | GitHub Actions |
| Containers | Docker Compose + Kubernetes (Kustomize) |

---

## Production Hardening

The system is built with production readiness as a first-class concern — not an afterthought. Every design decision includes explicit handling of failure modes.

### Alert Deduplication — Defense in Depth

Five independent layers prevent duplicate incidents even under partial infrastructure failures:

| Layer | Mechanism | Protects against |
|---|---|---|
| 1 | Redis SETNX short TTL (5 min) | Burst of duplicate alerts |
| 2 | Redis EXPIRE 7 days on `IncidentOpenedEvent` | Alert flood during active incident |
| 3 | Redis DEL on `IncidentResolvedEvent` | Stale dedup block after resolution |
| 4 | Redis AOF persistence | Dedup state loss on Redis restart |
| 5 | `incident-service` fingerprint check | Redis unavailability, race conditions |

No single layer failure results in duplicate incidents. Each layer independently catches what the others miss.

### Multi-Layer DDoS Protection

| Layer | Mechanism | Status |
|---|---|---|
| 1 | Cloudflare | TODO — when public domain |
| 2 | Nginx Ingress rate limiting per IP | TODO — when Kubernetes |
| 3 | bucket4j per-tenant + per-IP (application) | ✅ Implemented |
| 4 | Kafka consumer-side severity prioritization | ✅ Implemented |
| 5 | Micrometer metrics: `rate_limit.tenant.rejected`, `rate_limit.ip.rejected` | ✅ Implemented |

### Escalation Chain

```
T+0:    Incident OPEN → PRIMARY on-call: Slack DM + Email
T+5m*:  No ACK → Level 1: SECONDARY on-call: Slack DM + SMS
T+10m*: Still no ACK → Level 2: MANAGER: Email + SMS
        (* for CRITICAL — HIGH=15m, MEDIUM=30m, LOW=60m)
```

### Centralized Audit Log

Every state change across all services is recorded in a single chronological timeline per incident via the `audit.events` Kafka topic. Audit event types include: `INCIDENT_CREATED`, `INCIDENT_ACKNOWLEDGED`, `INCIDENT_ESCALATED`, `INCIDENT_RESOLVED`, `NOTIFICATION_SENT`, `ESCALATION_FIRED`, `POSTMORTEM_GENERATED` and more.

### Multi-Tenant Kafka

All topics are multi-tenant. `TenantKafkaProducerInterceptor` adds `X-Tenant-Id` to every outgoing record. Each `@KafkaListener` reads it per-record and clears `TenantContext` in a `finally` block — guaranteeing no tenant leaks between records in the same batch.

### Security

- **JWT secret**: No default value — application refuses to start without `JWT_SECRET` set
- **Dev endpoints**: `DevTokenController` gated with `@Profile("local")` — never available in production
- **Management port isolation**: Prometheus metrics on separate port (8091–8096), never mixed with business API
- **Sensitive field redaction**: `GlobalExceptionHandler` redacts `password`, `secret`, `token`, `apiKey` from error responses

---

## Observability

All services expose metrics via `/actuator/prometheus` on the management port. Every log line includes MDC context:

```
13:45:01.234 [acme-corp] [req-abc123] [user-1] INFO  IncidentCommandService - Incident created
```

Format: `[tenantId] [requestId] [userId]` — allows filtering all logs for a specific incident across all services.

---

## Design Decisions

**Why a custom FSM instead of Spring State Machine?**
A simple `Map<IncidentStatus, Set<IncidentStatus>>` is transparent, easily testable, and sufficient for this use case.

**Why CQRS without separate databases?**
The `CommandService`/`QueryService` split within the same PostgreSQL instance demonstrates the pattern without unnecessary infrastructure.

**Why `@Scheduled` for escalation instead of Kafka Streams?**
`@Scheduled` with a PostgreSQL-backed task table is transparent, testable with Mockito, and sufficient. ShedLock prevents duplicate execution across instances.

**Why no Gemini SDK?**
`RestClient` via the `GeminiClient` interface keeps the integration vendor-neutral — switching AI providers requires changing one class.

**Why HS512 for JWT instead of RS256 or Keycloak?**
Sufficient for a controlled environment where all services are owned by the same team. `ServiceTokenProvider` is abstracted behind an interface — migrating to RS256 requires changing one class per service.

**Why per-record TenantContext in Kafka listeners?**
`TenantKafkaConsumerInterceptor.onConsume()` receives an entire batch — reading `X-Tenant-Id` per-record in each `@KafkaListener` guarantees correctness regardless of batch composition.

---

## Kubernetes

The platform ships with a complete Kubernetes configuration using **Kustomize** with environment overlays.

### Structure

```
k8s/
├── base/                        # Environment-agnostic base configuration
│   ├── infrastructure/          # PostgreSQL, Kafka (KRaft), Redis, Ingress, ConfigMap
│   ├── {service}/
│   │   ├── deployment.yml       # Deployment with health probes, init containers, resource limits
│   │   └── service-hpa.yml      # ClusterIP Service + HorizontalPodAutoscaler
│   └── kustomization.yml
└── overlays/
    ├── dev/                     # Minikube — 1 replica, relaxed probes, 768Mi memory limit
    ├── staging/
    └── prod/
```

---

## Running Locally (Manual)

### Prerequisites

- Java 21
- Docker Desktop (at least 4GB RAM allocated)
- `jq`

### Step 1 — Start infrastructure

```bash
docker compose -f docker/docker-compose.yml up -d postgres redis kafka kafka-ui pgadmin
```

Wait until all containers are healthy:

```bash
docker compose -f docker/docker-compose.yml ps
```

### Step 2 — Create application-local.yml for each service

Each service requires `src/main/resources/application-local.yml` (excluded from git). Create one file per service.

> **Important**: Use only standard ASCII hyphens (`-`) in comments — em dashes (`—`) in YAML comments prevent Spring Boot from loading the file.

**All 6 services** need at minimum:

```yaml
jwt:
  secret: local-development-secret-key-minimum-64-characters-long-absolutely-not-for-production-use-only

logging:
  level:
    com.incidentplatform: DEBUG
```

**incident-service** additionally needs WebSocket origins:

```yaml
jwt:
  secret: local-development-secret-key-minimum-64-characters-long-absolutely-not-for-production-use-only

websocket:
  allowed-origins:
    - http://localhost:4200
    - http://localhost:3000

logging:
  level:
    com.incidentplatform: DEBUG
```

**postmortem-service** additionally needs a Gemini API key:

```yaml
jwt:
  secret: local-development-secret-key-minimum-64-characters-long-absolutely-not-for-production-use-only

gemini:
  api-key: your-gemini-api-key-here

logging:
  level:
    com.incidentplatform: DEBUG
```

### Step 3 — Build all modules

```bash
./mvnw clean install -DskipTests
```

### Step 4 — Start all 6 services (separate terminals)

```bash
# Terminal 1 — start first, it generates the Alertmanager token
./mvnw spring-boot:run -pl ingestion-service -Dspring-boot.run.profiles=local

# Terminal 2
./mvnw spring-boot:run -pl incident-service -Dspring-boot.run.profiles=local

# Terminal 3
./mvnw spring-boot:run -pl notification-service -Dspring-boot.run.profiles=local

# Terminal 4
./mvnw spring-boot:run -pl escalation-service -Dspring-boot.run.profiles=local

# Terminal 5
./mvnw spring-boot:run -pl postmortem-service -Dspring-boot.run.profiles=local

# Terminal 6
./mvnw spring-boot:run -pl oncall-service -Dspring-boot.run.profiles=local
```

### Step 5 — Verify all services are up

```bash
for port in 8091 8092 8093 8094 8095 8096; do
  echo -n "Port $port: "
  curl -s http://localhost:$port/actuator/health | jq -r .status
done
```

Expected:
```
Port 8091: UP
Port 8092: UP
Port 8093: UP
Port 8094: UP
Port 8095: UP
Port 8096: UP
```

### Service Ports

| Service | API Port | Management Port |
|---|---|---|
| ingestion-service | 8081 | 8091 |
| incident-service | 8082 | 8092 |
| notification-service | 8083 | 8093 |
| escalation-service | 8084 | 8094 |
| postmortem-service | 8085 | 8095 |
| oncall-service | 8086 | 8096 |

### Infrastructure URLs

| Tool | URL | Credentials |
|---|---|---|
| Kafka UI | http://localhost:8090 | - |
| pgAdmin | http://localhost:5050 | admin@admin.com / admin |
| Prometheus | http://localhost:9090 | - |
| Grafana | http://localhost:3000 | admin / admin |

---

## Running on Kubernetes (Minikube)

### Prerequisites

- Docker Desktop (at least 6GB RAM allocated)
- [Minikube](https://minikube.sigs.k8s.io/docs/start/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- `jq`

### Step 1 — Start Minikube

```bash
minikube start --cpus=4 --driver=docker
```

Verify the cluster is ready:

```bash
kubectl get nodes
# Expected: minikube   Ready   control-plane
```

### Step 2 — Configure Docker to use Minikube's daemon

```bash
eval $(minikube docker-env)
```

> Run this in every terminal session where you build images. It only affects the current shell.

### Step 3 — Build all Docker images

```bash
for service in ingestion-service incident-service notification-service escalation-service postmortem-service oncall-service; do
  echo "Building $service..."
  docker build -t $service:dev -f $service/Dockerfile .
done
```

First run takes 20-40 minutes (Maven downloads dependencies). Verify all images were built:

```bash
docker images | grep ":dev"
# Expected: 6 images listed
```

### Step 4 — Configure secrets

Review and update `k8s/overlays/dev/secrets.yml`:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: app-secrets
type: Opaque
stringData:
  JWT_SECRET: "local-development-secret-key-minimum-64-characters-long-not-for-production-k8s"
  GEMINI_API_KEY: "your-gemini-api-key-here"
  SLACK_BOT_TOKEN: "your-slack-bot-token-here"
  SLACK_SIGNING_SECRET: "your-slack-signing-secret-here"
```

### Step 5 — Deploy to Minikube

```bash
kubectl apply -k k8s/overlays/dev
```

### Step 6 — Wait for all pods to be ready

```bash
kubectl get pods -n incident-platform-dev -w
```

Wait until all pods show `1/1 Running`. Init containers wait for PostgreSQL and Kafka — this takes 2-5 minutes. Press `Ctrl+C` when done.

Expected final state:
```
escalation-service-xxx     1/1   Running   ...
incident-service-xxx       1/1   Running   ...
ingestion-service-xxx      1/1   Running   ...
kafka-0                    1/1   Running   ...
notification-service-xxx   1/1   Running   ...
oncall-service-xxx         1/1   Running   ...
postgresql-0               1/1   Running   ...
postmortem-service-xxx     1/1   Running   ...
redis-xxx                  1/1   Running   ...
```

### Step 7 — Configure local DNS

```bash
echo "127.0.0.1 incident-platform.local" | sudo tee -a /etc/hosts
```

### Step 8 — Start Minikube tunnel (separate terminal, keep it running)

```bash
minikube tunnel
```

### Step 9 — Verify the cluster is reachable

```bash
curl -s -o /dev/null -w "%{http_code}" http://incident-platform.local/api/v1/incidents
# Expected: 403 (reachable — authentication required)
```

---

## End-to-End Test

These steps work for both local and Kubernetes deployments.

### Step 1 — Generate a dev token

**Local:**
```bash
TOKEN=$(curl -s "http://localhost:8082/dev/token?userId=11111111-1111-1111-1111-111111111111&tenantId=test-tenant&email=admin@test.com&roles=ROLE_ADMIN" | jq -r .token)
echo "Token: ${TOKEN:0:50}..."
```

**Kubernetes** (`/dev/token` is not exposed via ingress by design — use port-forward):
```bash
kubectl port-forward svc/ingestion-service 8081:8081 -n incident-platform-dev &
sleep 2
TOKEN=$(curl -s "http://localhost:8081/dev/token?userId=11111111-1111-1111-1111-111111111111&tenantId=test-tenant&email=admin@test.com&roles=ROLE_ADMIN" | jq -r .token)
echo "Token: ${TOKEN:0:50}..."
```

### Step 2 — Send a firing alert (simulating Prometheus/Alertmanager)

**Local:**
```bash
curl -s -X POST http://localhost:8081/api/v1/alerts/prometheus \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" \
  -H "Content-Type: application/json" \
  -d '{
    "alerts": [{
      "status": "firing",
      "labels": {
        "alertname": "HighCPU",
        "severity": "critical",
        "instance": "server-01"
      },
      "annotations": {
        "summary": "CPU usage above 90%",
        "description": "Server server-01 CPU is at 95%"
      }
    }]
  }' | jq .
```

**Kubernetes:**
```bash
curl -s -X POST http://incident-platform.local/api/v1/alerts/prometheus \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" \
  -H "Content-Type: application/json" \
  -d '{
    "alerts": [{
      "status": "firing",
      "labels": {
        "alertname": "HighCPU",
        "severity": "critical",
        "instance": "server-01"
      },
      "annotations": {
        "summary": "CPU usage above 90%",
        "description": "Server server-01 CPU is at 95%"
      }
    }]
  }' | jq .
```

Expected response:
```json
{
  "received": 1,
  "processed": 1,
  "duplicates": 0,
  "fullySuccessful": true
}
```

### Step 3 — Verify incident was created

**Local:**
```bash
curl -s http://localhost:8082/api/v1/incidents \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" | jq '.content[]'
```

**Kubernetes** (use port-forward for a token signed with the k8s JWT_SECRET):
```bash
kubectl port-forward svc/incident-service 8082:8082 -n incident-platform-dev &
sleep 2
TOKEN_K8S=$(curl -s "http://localhost:8082/dev/token?userId=11111111-1111-1111-1111-111111111111&tenantId=test-tenant&email=admin@test.com&roles=ROLE_ADMIN" | jq -r .token)

curl -s http://incident-platform.local/api/v1/incidents \
  -H "Authorization: Bearer $TOKEN_K8S" \
  -H "X-Tenant-Id: test-tenant" | jq '.content[]'
```

Expected response:
```json
{
  "id": "<incident-id>",
  "tenantId": "test-tenant",
  "status": "OPEN",
  "title": "CPU usage above 90%",
  "severity": "CRITICAL",
  "allowedTransitions": ["ACKNOWLEDGED", "ESCALATED"]
}
```

### Step 4 — Acknowledge the incident

```bash
INCIDENT_ID="<id from previous response>"

# Local
curl -s -X PATCH http://localhost:8082/api/v1/incidents/$INCIDENT_ID/status \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" \
  -H "Content-Type: application/json" \
  -d '{"status": "ACKNOWLEDGED"}' | jq .
```

Expected response includes:
```json
{
  "status": "ACKNOWLEDGED",
  "acknowledgedAt": "...",
  "mttaMinutes": 0,
  "allowedTransitions": ["RESOLVED"]
}
```

### Step 5 — Resolve the incident

```bash
# Local
curl -s -X PATCH http://localhost:8082/api/v1/incidents/$INCIDENT_ID/status \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" \
  -H "Content-Type: application/json" \
  -d '{"status": "RESOLVED"}' | jq .
```

Expected response includes:
```json
{
  "status": "RESOLVED",
  "resolvedAt": "...",
  "mttaMinutes": 0,
  "mttrMinutes": 1,
  "allowedTransitions": ["CLOSED"]
}
```

After resolving, `postmortem-service` automatically generates a draft via Gemini API. Retrieve it with:

```bash
# Local
curl -s http://localhost:8085/api/v1/postmortems/incident/$INCIDENT_ID \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" | jq .
```

### Step 6 — Check the audit log

```bash
# Local
curl -s http://localhost:8082/api/v1/incidents/$INCIDENT_ID/audit \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" | jq '.[]'
```

Shows the full chronological timeline of every event across all services for this incident.

### Step 7 — (Optional) Register an on-call schedule

For notifications and escalations to reach the right person:

```bash
# Local
curl -s -X POST http://localhost:8086/api/v1/oncall/schedules \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "11111111-1111-1111-1111-111111111111",
    "userName": "Jan Kowalski",
    "email": "jan@example.com",
    "slackUserId": "U0123456789",
    "role": "PRIMARY",
    "startsAt": "2026-01-01T00:00:00Z",
    "endsAt": "2026-12-31T23:59:59Z"
  }' | jq .
```

---

## Running Tests

```bash
# All unit tests
./mvnw test -pl shared,ingestion-service,incident-service,notification-service,escalation-service,postmortem-service,oncall-service

# Single module
./mvnw test -pl notification-service
```

**Test coverage highlights**:
- `IncidentFsmTest` — 25 combinations of allowed/forbidden state transitions using `@ParameterizedTest` + `@CsvSource`
- `IncidentCommandServiceTest` — deduplication logic, severity escalation, optimistic lock, FSM validation
- `IncidentKafkaConsumerTest` — per-record tenant isolation, TenantContext cleanup in finally, no cross-tenant leaks
- `NotificationServiceTest` — orchestration, fault isolation between channels, idempotency
- `NotificationRouterTest` — routing logic for all 5 event types, fallback when oncall-service unavailable
- `EscalationServiceTest` — level 1/2 scheduling, cancellation, idempotency, severity-based timeouts
- `EscalationSchedulerTest` — timer logic, level 2 scheduling after level 1, fault isolation
- `PostmortemServiceTest` — generation, Gemini failure handling, CRUD, audit event publishing
- `PostmortemRetrySchedulerTest` — retry logic for FAILED postmortems, max retry limit
- `JwtUtilsTest` — token generation, validation, expiry, secret length validation
- `TenantContextTest` — ThreadLocal isolation between threads, TenantAwareTaskDecorator propagation
- `OncallScheduleServiceTest` — schedule creation, overlap detection, current on-call resolution

---

## Project Structure

```
incident-platform/
├── shared/                  # Common: events, DTOs, security (JWT, TenantContext, AuditEventPublisher)
│                            # Kafka interceptors: TenantKafkaProducerInterceptor, TenantKafkaConsumerInterceptor
├── ingestion-service/       # Alert normalization, deduplication, rate limiting
├── incident-service/        # Incident lifecycle, FSM, audit log consumer + API
├── notification-service/    # Multi-channel notifications, Slack Bot Token, on-call routing
├── escalation-service/      # 2-level escalation chain, severity-based timeouts, ShedLock
├── postmortem-service/      # AI-generated postmortems via Gemini API
├── oncall-service/          # On-call schedule management
├── docker/
│   ├── docker-compose.yml   # PostgreSQL, Redis (AOF), Kafka, pgAdmin, Kafka UI, Prometheus, Grafana
│   ├── prometheus.yml       # Prometheus scrape config
│   └── grafana/
│       └── provisioning/    # Grafana auto-provisioning
└── k8s/
    ├── base/                # Environment-agnostic Kubernetes manifests
    └── overlays/
        ├── dev/             # Minikube: 1 replica, 768Mi memory, relaxed probe delays
        ├── staging/
        └── prod/
```

---

## Author

Built by Łukasz Pławiak as a portfolio project demonstrating production-oriented Java/Spring Boot backend development.

Frontend companion: [incident-platform-frontend](https://github.com/lukaszplawiak/incident-platform-frontend) — Angular 21 SPA with real-time WebSocket dashboard.