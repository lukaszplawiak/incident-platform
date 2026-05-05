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
| Containers | Docker Compose |

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

The ingestion pipeline implements defense-in-depth against alert flooding:

| Layer | Mechanism | Status |
|---|---|---|
| 1 | Cloudflare | TODO — when public domain |
| 2 | Nginx Ingress rate limiting per IP | TODO — when Kubernetes |
| 3 | bucket4j per-tenant + per-IP (application) | ✅ Implemented |
| 4 | Kafka consumer-side severity prioritization | ✅ Implemented (CRITICAL logged at WARN) |
| 4 (full) | Separate Kafka topics per severity | TODO — when Kubernetes with multiple replicas |
| 5 | Micrometer metrics: `rate_limit.tenant.rejected`, `rate_limit.ip.rejected` | ✅ Implemented |
| 5 (full) | Prometheus alerting rules on RateLimitExceeded | TODO — alerting rules not yet configured |

### Escalation Chain

When an incident is not acknowledged, escalation follows a structured chain with timeouts calibrated to severity:

```
T+0:    Incident OPEN → PRIMARY on-call: Slack DM + Email
T+5m*:  No ACK → Level 1: SECONDARY on-call: Slack DM + SMS
T+10m*: Still no ACK → Level 2: MANAGER: Email + SMS
        (* for CRITICAL — HIGH=15m, MEDIUM=30m, LOW=60m)
```

Each escalation level creates an independent `EscalationTask` in PostgreSQL. ACK at any point cancels all pending tasks.

### Centralized Audit Log

Every state change across all services is recorded in a single chronological timeline per incident. The audit log is event-driven — each service publishes `AuditEventMessage` to the `audit.events` Kafka topic, consumed and persisted by `incident-service`.

Audit event types:
- `INCIDENT_CREATED`, `INCIDENT_ACKNOWLEDGED`, `INCIDENT_ESCALATED`, `INCIDENT_RESOLVED`, `INCIDENT_CLOSED`, `INCIDENT_ASSIGNED`, `INCIDENT_SEVERITY_UPDATED`
- `NOTIFICATION_SENT`, `NOTIFICATION_FAILED`
- `ESCALATION_FIRED`, `ESCALATION_SCHEDULED`
- `POSTMORTEM_GENERATED`, `POSTMORTEM_FAILED`, `POSTMORTEM_UPDATED`

### Multi-Tenant Kafka — Per-Record Tenant Isolation

All Kafka topics are multi-tenant — records from different tenants are interleaved on the same topic. Tenant isolation is enforced at two levels:

- **Producer**: `TenantKafkaProducerInterceptor` automatically adds `X-Tenant-Id` header to every outgoing record, reading from the current thread's `TenantContext`
- **Consumer**: Each `@KafkaListener` method reads `X-Tenant-Id` from its own `ConsumerRecord` header before processing, and clears `TenantContext` in a `finally` block — guaranteeing no tenant leaks between records in the same batch
- **Validation**: `TenantKafkaConsumerInterceptor` validates that every incoming record carries a tenant header, logging a warning if missing — acts as an early detection layer before business logic runs

This design ensures that even if Kafka delivers records from `tenant-a` and `tenant-b` in the same poll batch, each record is processed with the correct tenant context.

### Concurrency Safety

- **Optimistic locking**: `@Version` field on `Incident` entity — concurrent PATCH requests on the same incident return `HTTP 409 Conflict` instead of silently overwriting each other
- **`GlobalExceptionHandler`**: Catches `ObjectOptimisticLockingFailureException` and returns a structured 409 response with `OPTIMISTIC_LOCK_CONFLICT` error code
- **ShedLock**: `EscalationScheduler` and `PostmortemRetryScheduler` use ShedLock to prevent duplicate job execution across multiple service instances

### Edge Cases Handled

**TTL expiry during active incident** — When `IncidentOpenedEvent` is consumed by `ingestion-service`, the Redis key TTL is extended to 7 days. When `IncidentResolvedEvent` arrives, the key is deleted immediately. If the resolved event is lost (Kafka failure), the 7-day TTL acts as a safety net.

**Resolved before fired** — If Prometheus sends a `resolved` event before the `firing` event arrives (network reorder), `incident-service` safely ignores the resolved event. The subsequent fired alert creates a new incident normally.

**Redis circuit breaker** — When Redis is unavailable, `DeduplicationService` opens a Resilience4j circuit breaker and lets alerts through. The `incident-service` fingerprint check (layer 5) prevents duplicate incidents.

**Notification deduplication** — `notification-service` checks `notification_log` before sending. Kafka at-least-once delivery never results in duplicate emails or Slack messages.

**Kafka consumer rebalancing** — All consumer services configure explicit timeouts to prevent rebalancing storms under load:

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

**Audit event resilience** — `AuditEventPublisher` uses `@Retryable` (3 attempts, exponential backoff) for transient Kafka failures. Serialization errors are not retried — bad data won't fix itself. The main business flow is never blocked by observability infrastructure.

**Payload size limit** — `ingestion-service` rejects payloads over 1MB at the application layer (explicit size check before processing). Oversized payloads return `413 Payload Too Large` with no Kafka publishing.

### Security

- **JWT secret**: No default value — application refuses to start without `JWT_SECRET` environment variable set explicitly
- **Service-to-service auth**: Internal HTTP calls between services use `ServiceTokenProvider` which generates and caches JWT tokens with `ROLE_SERVICE` — not exposed to end users
- **Dev endpoints**: `DevTokenController` is gated with `@Profile("local")` — never available in production
- **Management port isolation**: Prometheus metrics endpoint exposed on separate management port (8091–8096) — never co-located with the business API port
- **API key security**: Gemini API key passed via `x-goog-api-key` HTTP header — never embedded in URLs where it could appear in server access logs or CDN caches
- **Request size limits**: `ingestion-service` rejects payloads over 1MB — protection against DoS via oversized alerts
- **Slack Bot Token**: Minimal OAuth scopes (`chat:write`, `im:write`) — principle of least privilege
- **Sensitive field redaction**: `GlobalExceptionHandler` redacts fields named `password`, `secret`, `token`, `apiKey` from validation error responses

---

## Observability

The platform is fully instrumented for production monitoring.

### Metrics (Micrometer + Prometheus + Grafana)

All services expose metrics via `/actuator/prometheus` on the management port. Prometheus scrapes every 15 seconds. Grafana provides dashboards for:

- HTTP request rate and error rate per service
- JVM heap and non-heap memory
- Rate limit rejections per tenant and per IP
- Kafka consumer lag

**Infrastructure**: Prometheus and Grafana are included in `docker-compose.yml` — monitoring starts with a single `docker-compose up`.

### Distributed Tracing Context

Every log line includes MDC context for correlation across services:

```
13:45:01.234 [acme-corp] [req-abc123] [user-1] INFO  IncidentCommandService - Incident created
```

Format: `[tenantId] [requestId] [userId]` — allows filtering all logs for a specific incident or tenant across all services.

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

**Why Slack Bot Token instead of Incoming Webhook?**
Incoming Webhooks can only post to a single channel. Bot Token (`xoxb-`) with `chat.postMessage` supports both direct messages (to the on-call engineer's Slack User ID) and channel posts — with a single API. Bot Token also enables future ACK-via-Slack (Interactive Components) without architectural changes.

**Why bucket4j in-memory instead of Redis-backed rate limiting?**
In-memory rate limiting (ConcurrentHashMap) is sufficient for a single instance. The tradeoff is documented: each instance maintains independent counters, so a load-balanced deployment would allow N×limit requests. Migration to bucket4j-redis requires changing one class — the `RateLimitingService` — when moving to Kubernetes.

**Why a centralized audit log via Kafka instead of per-service history tables?**
Per-service history tables scatter the timeline across databases and require multi-service HTTP calls to reconstruct a full incident timeline. The `audit.events` Kafka topic acts as a single audit stream — any service can publish events and the consumer assembles them into a unified chronological view accessible via one API endpoint.

**Why per-record TenantContext in Kafka listeners instead of the consumer interceptor?**
`TenantKafkaConsumerInterceptor.onConsume()` receives an entire batch — setting TenantContext from the first record and breaking means subsequent records from different tenants are processed with the wrong context. Reading `X-Tenant-Id` per-record directly in each `@KafkaListener` guarantees correctness regardless of batch composition. The interceptor is kept as a validation layer that logs warnings for records missing the tenant header.


---

## Kubernetes

The platform ships with a complete Kubernetes configuration using **Kustomize** with environment overlays. All 6 services are production-ready to deploy to any Kubernetes cluster.

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
    ├── staging/                 # Staging overrides
    └── prod/                    # Production overrides
```

### Key Features

**Rolling updates** — zero downtime deployments out of the box:
```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0   # never reduce below requested replicas
    maxSurge: 1         # allow one extra pod during update
```

**Init containers** — each service waits for its dependencies before starting, preventing crash loops on cold start:
```yaml
initContainers:
  - name: wait-for-postgresql
    command: ["sh", "-c", "until nc -z postgresql 5432; do sleep 2; done"]
  - name: wait-for-kafka
    command: ["sh", "-c", "until nc -z kafka 9092; do sleep 2; done"]
```

**Health probes** — Kubernetes routes traffic only to ready pods:
```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8092          # management port, separate from API port
  initialDelaySeconds: 30
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8092
  initialDelaySeconds: 60
```

**Horizontal Pod Autoscaler** — CPU and memory-based autoscaling for all services:
```yaml
minReplicas: 1
maxReplicas: 3
metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        averageUtilization: 80
```

**ShedLock** — prevents duplicate scheduled job execution when a service scales to multiple replicas (escalation-service, postmortem-service).

**Management port isolation** — Prometheus metrics and health endpoints run on a separate port (8091–8096), never mixed with the business API port.

### Running on Minikube

```bash
# Start Minikube with enough resources for 6 JVM services
minikube start --cpus=4 --memory=7500 --driver=docker

# Build images directly into Minikube Docker daemon (no registry needed)
eval $(minikube docker-env)
docker build -t incident-service:dev -f incident-service/Dockerfile .
# ... repeat for each service

# Deploy dev overlay
kubectl apply -k k8s/overlays/dev

# Watch pods come up
kubectl get pods -n incident-platform-dev -w

# Access the API (tunnel or port-forward)
minikube tunnel
# or
kubectl port-forward svc/incident-service 8082:8082 -n incident-platform-dev
```

The dev overlay applies patches on top of base:
- 1 replica per service (overrides HPA)
- 768Mi memory limit (JVM needs more than the 512Mi base default on Minikube)
- Relaxed probe delays (readiness=90s, liveness=120s) to account for slower JVM startup


---

## Running Locally

**Prerequisites**: Java 21, Docker Desktop, Maven

```bash
# 1. Start infrastructure (PostgreSQL, Redis, Kafka, Prometheus, Grafana)
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

**Infrastructure URLs**:
- Kafka UI: http://localhost:8090
- pgAdmin: http://localhost:5050
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)

**Service ports**:

| Service | API | Management (Prometheus) |
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
- notification-service sends Slack DM to the on-call engineer + posts to `#incidents`
- escalation-service schedules level 1 escalation task (fires after 5 minutes for CRITICAL locally)
- After timeout without ACK: level 1 fires → SECONDARY notified via Slack DM + SMS
- After another timeout: level 2 fires → MANAGER notified via Email + SMS
- All events recorded in audit log — query via `GET /api/v1/incidents/{id}/audit`

To test the postmortem flow, resolve the incident:
```bash
curl -X PATCH http://localhost:8082/api/v1/incidents/{incidentId}/status \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"status": "RESOLVED"}'
```
- `IncidentResolvedEvent` published to `incidents.lifecycle`
- `ingestion-service` deletes the Redis dedup key — same alert can create a new incident
- postmortem-service generates a draft via Gemini API
- Draft available at `GET http://localhost:8085/api/v1/postmortems/incident/{incidentId}`

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
- `IncidentCommandServiceTest` — deduplication logic, severity escalation on duplicate alerts, optimistic lock, FSM validation
- `IncidentQueryServiceTest` — filter routing (Specification vs. simple query), tenant scoping, DTO mapping
- `IncidentKafkaConsumerTest` — per-record tenant isolation, TenantContext cleanup in finally, no cross-tenant leaks
- `NotificationServiceTest` — orchestration, fault isolation between channels, idempotency, audit event publishing
- `NotificationRouterTest` — routing logic for all 5 event types, fallback when oncall-service unavailable
- `NotificationIncidentEventConsumerTest` — header-based tenant resolution, event routing, TenantContext lifecycle
- `EscalationServiceTest` — level 1/2 scheduling, cancellation, idempotency, severity-based timeouts
- `EscalationSchedulerTest` — timer logic, level 2 scheduling after level 1, fault isolation
- `EscalationIncidentEventConsumerTest` — per-record tenant isolation, event routing, sequential records without leaks
- `PostmortemServiceTest` — generation, Gemini failure handling, CRUD, audit event publishing
- `PostmortemRetrySchedulerTest` — retry logic for FAILED postmortems, max retry limit
- `PostmortemIncidentEventConsumerTest` — header tenant wins over payload tenant, ignored event types
- `JwtUtilsTest` — token generation, validation, expiry, edge cases, secret length validation
- `TenantContextTest` — ThreadLocal isolation between threads, TenantAwareTaskDecorator propagation
- `OncallScheduleServiceTest` — schedule creation, overlap detection, current on-call resolution

---

## Project Structure

```
incident-platform/
├── shared/                  # Common: events, DTOs, security (JWT, TenantContext, ServiceTokenProvider, AuditEventPublisher)
│                            # Kafka interceptors: TenantKafkaProducerInterceptor, TenantKafkaConsumerInterceptor
├── ingestion-service/       # Alert normalization, deduplication, rate limiting, lifecycle consumer
├── incident-service/        # Incident lifecycle, FSM, audit log consumer + API
├── notification-service/    # Multi-channel notifications, Slack Bot Token, on-call routing
├── escalation-service/      # 2-level escalation chain, severity-based timeouts, ShedLock
├── postmortem-service/      # AI-generated postmortems via Gemini API, retry scheduler
├── oncall-service/          # On-call schedule management
└── docker/
    ├── docker-compose.yml   # PostgreSQL, Redis (AOF), Kafka, pgAdmin, Kafka UI, Prometheus, Grafana
    ├── prometheus.yml       # Prometheus scrape configuration
    └── grafana/
        └── provisioning/    # Grafana auto-provisioning (datasources)
```

---

## Author

Built by Łukasz Pławiak as a portfolio project demonstrating production-oriented Java/Spring Boot backend development.

Frontend companion: [incident-platform-frontend](https://github.com/your-username/incident-platform-frontend) — Angular 21 SPA with real-time WebSocket dashboard.