# Incident Platform

[![CI](https://github.com/lukaszplawiak/incident-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/lukaszplawiak/incident-platform/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Apache_Kafka-KRaft-231F20?logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-Kustomize-326CE5?logo=kubernetes&logoColor=white)](https://kubernetes.io/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](https://www.docker.com/)
[![License](https://img.shields.io/badge/license-MIT-2563eb)](LICENSE)

A production-oriented microservices backend that automates the full lifecycle of production incidents — from alert ingestion through escalation to AI-generated postmortems. Built to demonstrate real-world engineering: event-driven architecture, multi-tenancy, observability, and Kubernetes-ready deployment.

(BONUS) Frontend companion: [incident-platform-frontend](https://github.com/lukaszplawiak/incident-platform-frontend) — Angular 21 SPA with real-time WebSocket dashboard.

[Overview](#overview) | [Architecture](#architecture) | [Design Decisions](#design-decisions) | [Tech Stack](#tech-stack) | [Resilience & Security](#resilience--security) | [Observability](#observability) | [CI/CD](#cicd) | [Running Locally](#running-locally) | [Running on Kubernetes](#running-on-kubernetes) | [End-to-End Test](#end-to-end-test) | [Running Tests](#running-tests) | [Project Structure](#project-structure)

---

## Overview

When a monitoring system detects a problem — high CPU, a security breach, a failed service — the platform ingests the alert, normalizes it from multiple sources, and deduplicates it to prevent noise. It then creates an actionable incident, tracks its full lifecycle from detection to resolution, and automatically notifies the on-call engineer via Slack DM, email, and SMS. If no one responds in time, the incident escalates automatically through a configurable chain. Every state change is recorded in a centralized audit log, and resolved incidents trigger an AI-generated postmortem draft.

**The goal**: reduce the time between "something broke" and "someone is fixing it."

The system is built for **multiple tenants** — each organization's data is fully isolated at every layer: HTTP, Kafka, and database.

### What the platform covers

- **Alert ingestion** from Prometheus, Wazuh, and generic sources with normalization and 5-layer deduplication
- **Incident lifecycle** managed by a finite state machine: `OPEN → ACKNOWLEDGED → ESCALATED → RESOLVED → CLOSED`
- **Automatic escalation** through a severity-calibrated chain: PRIMARY → SECONDARY → MANAGER
- **Multi-channel notifications** via Slack Bot Token (direct messages), email (Mailtrap SMTP), and SMS
- **AI-generated postmortems** via Gemini API triggered automatically on incident resolution
- **Centralized audit log** — every event across all services assembled into a single chronological timeline per incident
- **Real-time updates** via WebSocket (STOMP) for live incident dashboards
- **Full observability** — Prometheus metrics, Grafana dashboards, structured logging with MDC context

---

## Architecture

The platform follows a **microservices architecture** with event-driven communication via Apache Kafka. Each service has a single responsibility and communicates asynchronously.

```
Prometheus / Wazuh / Generic
           │
           ▼
  ┌─────────────────────┐
  │   ingestion-service  │  port 8081
  │                     │  Normalizes alerts, deduplicates via Redis
  │                     │  Rate limiting per tenant + per IP
  │                     │  Consumes incidents.lifecycle (dedup lifecycle)
  └──────────┬──────────┘
             │ Kafka: alerts.raw / alerts.resolved
             ▼
  ┌─────────────────────┐
  │   incident-service   │  port 8082
  │                     │  FSM-based lifecycle, PostgreSQL, CQRS
  │                     │  WebSocket real-time updates
  │                     │  Centralized audit log consumer
  └──────────┬──────────┘
             │ Kafka: incidents.lifecycle
             ├─────────────────────────────────┐
             ▼                                 ▼
  ┌────────────────────┐           ┌──────────────────────┐
  │ escalation-service │           │  notification-service  │
  │   port 8084        │           │      port 8083         │
  │                    │           │                        │
  │ 2-level chain:     │           │  Slack Bot Token (DM)  │
  │ PRIMARY→SECONDARY  │           │  Email (Mailtrap SMTP) │
  │ →MANAGER           │           │  SMS (simulated)       │
  │ Timeouts per       │           │  Strategy Pattern      │
  │ severity           │           │  OncallClient →        │
  └────────────────────┘           └──────────┬─────────────┘
                                              │ HTTP (service JWT)
                                              ▼
                                   ┌──────────────────────┐
                                   │    oncall-service     │  port 8086
                                   │                      │  On-call schedule mgmt
                                   │  PRIMARY / SECONDARY │  Who is on-call now?
                                   │  / MANAGER roles     │
                                   └──────────────────────┘
             │ Kafka: incidents.lifecycle (IncidentResolvedEvent)
             ▼
  ┌─────────────────────┐
  │  postmortem-service  │  port 8085
  │                     │  Gemini API integration
  │                     │  Auto-generated postmortem drafts
  └─────────────────────┘

  All services → Kafka: audit.events → incident-service audit consumer → audit_events table
```

### Services

| Service | Port | Responsibility |
|---|---|---|
| ingestion-service | 8081 | Alert normalization, deduplication, rate limiting |
| incident-service | 8082 | Incident lifecycle FSM, WebSocket, audit log |
| notification-service | 8083 | Multi-channel notifications, on-call routing |
| escalation-service | 8084 | Severity-based auto-escalation chain |
| postmortem-service | 8085 | AI-generated postmortem drafts via Gemini |
| oncall-service | 8086 | On-call schedule management |

### Kafka Topics

| Topic | Producer | Consumers |
|---|---|---|
| `alerts.raw` | ingestion-service | incident-service |
| `alerts.resolved` | ingestion-service | incident-service |
| `incidents.lifecycle` | incident-service | notification-service, escalation-service, postmortem-service, ingestion-service |
| `audit.events` | all services | incident-service (audit consumer) |
| `alerts.dead-letter` | ingestion-service | — |

---

## Design Decisions

**Why a custom FSM instead of Spring State Machine?**
Spring State Machine adds significant complexity and weight. A simple `Map<IncidentStatus, Set<IncidentStatus>>` of allowed transitions is transparent, easily testable, and sufficient for this use case. The FSM is covered by 25 parameterized test cases for all allowed and forbidden transitions.

**Why CQRS without separate databases?**
Full CQRS with read replicas is overkill here. The lightweight split between `IncidentCommandService` and `IncidentQueryService` within the same PostgreSQL instance demonstrates understanding of the pattern without unnecessary infrastructure complexity.

**Why `@Scheduled` for escalation instead of Kafka Streams?**
Kafka Streams would require windowing, state stores, and a significantly more complex setup. `@Scheduled` with a PostgreSQL-backed `EscalationTask` table is transparent, testable with Mockito, and sufficient. ShedLock prevents duplicate execution when the service scales to multiple replicas.

**Why no Gemini SDK?**
Using the raw HTTP API via `RestClient` through a `GeminiClient` interface keeps the integration vendor-neutral — switching to a different AI provider requires changing exactly one class. It also makes the HTTP contract explicit and debuggable without additional Maven dependencies.

**Why HS512 for JWT instead of RS256 or Keycloak?**
HS512 with a shared secret is sufficient for a controlled environment where all services are owned by the same team. `ServiceTokenProvider` is abstracted behind an interface — migrating to RS256 or Keycloak requires changing one class per service. The tradeoff is documented and understood.

**Why Slack Bot Token instead of Incoming Webhook?**
Incoming Webhooks can only post to a single channel. Bot Token (`xoxb-`) with `chat.postMessage` supports both direct messages to the on-call engineer's Slack User ID and channel posts with a single API. Bot Token also enables future ACK-via-Slack (Interactive Components) without architectural changes.

**Why a centralized audit log via Kafka instead of per-service history tables?**
Per-service history tables scatter the timeline across databases and require multi-service HTTP calls to reconstruct a full incident view. The `audit.events` topic acts as a single audit stream — any service publishes events and the consumer assembles them into a unified chronological view via one API endpoint.

**Why per-record TenantContext in Kafka listeners instead of the consumer interceptor?**
`TenantKafkaConsumerInterceptor.onConsume()` receives an entire batch — setting TenantContext from the first record would contaminate subsequent records from different tenants. Reading `X-Tenant-Id` per-record directly in each `@KafkaListener` guarantees correctness regardless of batch composition. The interceptor is kept as a validation layer only.

**Why Consumer-Driven Contracts for notification-service?**
The notification consumer deserializes Kafka messages to `JsonNode` and extracts only the fields it needs. This decouples the consumer from the exact producer schema — a producer adding new fields to `IncidentOpenedEvent` won't break notification-service.

**Why separate DLQ strategies for ingestion vs. incident?**
`ingestion-service` processes batches — one bad alert must not block the rest, so it uses a custom `DeadLetterPublisher`. `incident-service` processes single messages where Spring Kafka's built-in DLT handles retries correctly.

**Why bucket4j in-memory instead of Redis-backed rate limiting?**
In-memory rate limiting is sufficient for a single instance. The tradeoff is documented: each instance maintains independent counters in a load-balanced deployment. Migration to bucket4j-redis requires changing one class — `RateLimitingService`.

**Why a separate oncall-service instead of extending notification-service?**
On-call schedule management is a distinct bounded context. A separate service allows independent scaling, independent deployment, and future extension (PagerDuty integration, calendar sync) without touching the notification pipeline.

---

## Tech Stack

| Category | Technology | Why |
|---|---|---|
| Language | Java 21 | Virtual threads, records, pattern matching |
| Framework | Spring Boot 3.5 | Production-grade auto-configuration, actuator |
| Messaging | Apache Kafka (KRaft) | Durable, ordered, replayable event stream |
| Database | PostgreSQL 16 + Flyway | ACID, versioned schema migrations |
| Cache / Dedup | Redis 7 (AOF) | Sub-millisecond SETNX dedup, AOF for durability |
| Security | Spring Security + JWT (HS512) | Stateless auth, service-to-service tokens |
| Real-time | WebSocket (STOMP) | Live incident dashboard updates |
| Email | Spring Mail + Mailtrap SMTP | Real SMTP integration, safe sandbox |
| Slack | Bot Token + chat.postMessage | DM + channel posts, future Interactive Components |
| AI | Gemini API via RestClient | Vendor-neutral, no SDK lock-in |
| Resilience | Resilience4j | Circuit breaker on Redis and Gemini, retry with backoff |
| Rate Limiting | bucket4j | Per-tenant + per-IP, in-memory, production-replaceable |
| API Docs | SpringDoc OpenAPI 3 | Auto-generated, available at `/swagger-ui.html` |
| Build | Maven multi-module | Shared dependency management, incremental builds |
| Observability | Micrometer + Prometheus + Grafana | HTTP metrics, JVM, Kafka lag, rate limit rejections |
| Logging | SLF4J + MDC | Structured logs with tenantId, requestId, userId |
| Containers | Docker Compose + Kubernetes (Kustomize) | Local infra + production-ready k8s overlays |
| CI | GitHub Actions | Build, test, coverage, Docker image validation |

---

## Resilience & Security

### Alert Deduplication — 5 Independent Layers

No single layer failure results in duplicate incidents. Each layer independently catches what the others miss.

| Layer | Mechanism | Protects against |
|---|---|---|
| 1 | Redis SETNX, 5-minute TTL | Burst of duplicate alerts |
| 2 | Redis EXPIRE 7 days on `IncidentOpenedEvent` | Alert flood during active incident |
| 3 | Redis DEL on `IncidentResolvedEvent` | Stale dedup block after resolution |
| 4 | Redis AOF persistence | Dedup state loss on Redis restart |
| 5 | `incident-service` fingerprint check in PostgreSQL | Redis unavailability, race conditions |

### Escalation Chain

When an incident is not acknowledged, escalation follows a structured chain with timeouts calibrated to severity:

```
T+0:    Incident OPEN  → PRIMARY on-call:   Slack DM + Email
T+5m*:  No ACK         → Level 1 SECONDARY: Slack DM + SMS
T+10m*: Still no ACK   → Level 2 MANAGER:   Email + SMS
        (* CRITICAL — HIGH=15m, MEDIUM=30m, LOW=60m)
```

Each escalation level creates an independent `EscalationTask` in PostgreSQL. ACK at any point cancels all pending tasks. ShedLock prevents duplicate job execution across multiple replicas.

### Multi-Layer DDoS Protection

| Layer | Mechanism | Status |
|---|---|---|
| 1 | Cloudflare | TODO — when public domain |
| 2 | Nginx Ingress rate limiting | TODO — when multi-replica Kubernetes |
| 3 | bucket4j per-tenant + per-IP (application layer) | ✅ Implemented |
| 4 | Kafka consumer severity prioritization | ✅ Implemented |
| 5 | Micrometer: `rate_limit.tenant.rejected`, `rate_limit.ip.rejected` | ✅ Implemented |

### Security

- **JWT secret**: No default value — application refuses to start without `JWT_SECRET` set explicitly
- **Service-to-service auth**: `ServiceTokenProvider` generates and caches JWT tokens with `ROLE_SERVICE` — not exposed to end users
- **Dev endpoints**: `DevTokenController` gated with `@Profile("local")` — never available in production
- **Management port isolation**: Prometheus metrics and health endpoints on separate ports (8091–8096) — never co-located with the business API
- **API key security**: Gemini API key passed via `x-goog-api-key` HTTP header — never embedded in URLs where it could appear in access logs
- **Sensitive field redaction**: `GlobalExceptionHandler` redacts `password`, `secret`, `token`, `apiKey` from validation error responses
- **Slack Bot Token**: Minimal OAuth scopes (`chat:write`, `im:write`) — principle of least privilege
- **Request size limits**: `ingestion-service` rejects payloads over 1MB — protection against DoS via oversized alerts

### Concurrency Safety

- **Optimistic locking**: `@Version` on `Incident` entity — concurrent PATCH requests return `HTTP 409 Conflict` instead of silently overwriting
- **Notification idempotency**: `notification-service` checks `notification_log` before sending — Kafka at-least-once delivery never causes duplicate Slack messages or emails
- **Audit event resilience**: `AuditEventPublisher` uses `@Retryable` (3 attempts, exponential backoff) — business flow is never blocked by observability infrastructure

### Multi-Tenant Kafka — Per-Record Isolation

All Kafka topics are multi-tenant. `TenantKafkaProducerInterceptor` adds `X-Tenant-Id` to every outgoing record. Each `@KafkaListener` reads it per-record and clears `TenantContext` in a `finally` block — guaranteeing no tenant leaks between records in the same batch.

---

## Observability

### Metrics — Micrometer + Prometheus + Grafana

All services expose metrics via `/actuator/prometheus` on the management port (8091–8096). Prometheus scrapes every 15 seconds. Grafana dashboards cover:

- HTTP request rate and error rate per service
- JVM heap, non-heap memory, GC activity
- Rate limit rejections per tenant and per IP
- Kafka consumer lag per consumer group

Prometheus and Grafana are included in `docker-compose.yml` — monitoring starts alongside the application with a single command.

### Distributed Tracing Context

Every log line includes MDC context for correlation across services:

```
09:17:32.411 [test-tenant] [req-e87b7a28] [user-11111111] INFO  IncidentCommandService - Incident created: id=3f669983
```

Format: `[tenantId] [requestId] [userId]` — filter all logs for a specific request or tenant across all services in any log aggregation system (ELK, Loki, CloudWatch).

### Management Port Isolation

Health and metrics endpoints run on a dedicated port per service, never mixed with the business API:

| Service | API Port | Management Port |
|---|---|---|
| ingestion-service | 8081 | 8091 |
| incident-service | 8082 | 8092 |
| notification-service | 8083 | 8093 |
| escalation-service | 8084 | 8094 |
| postmortem-service | 8085 | 8095 |
| oncall-service | 8086 | 8096 |

---

## CI/CD

GitHub Actions pipeline runs on every push and pull request to `main`.

### Job 1 — Build, Test & Coverage

Runs on every push and every PR:

```
Checkout → Java 21 setup (Temurin) → Compile → Run tests with JaCoCo → Upload coverage reports
```

- Compiles all 7 modules and runs the full test suite
- JaCoCo coverage reports uploaded as artifacts (retained 14 days)
- On pull requests: JaCoCo report posted as a PR comment with per-file coverage breakdown
- Minimum coverage gate: **60%** overall and per changed file

### Job 2 — Build Docker Images

Runs only on merge to `main`, after Job 1 passes:

```
Build Docker image (matrix: 6 services in parallel) → GitHub Actions cache (layer reuse)
```

- Builds all 6 service images in a matrix strategy (`fail-fast: false` — one failure doesn't cancel others)
- Uses `docker/build-push-action` with GitHub Actions cache for fast layer reuse
- Images are validated but not pushed — no registry configured yet (next step: GitHub Container Registry)

### Pipeline Status

The CI badge at the top of this README reflects the current status of the `main` branch pipeline.

---

## Running Locally

### Prerequisites

- Java 21
- Docker Desktop (minimum 4GB RAM allocated)
- `jq` — command-line JSON formatter (`brew install jq` on macOS)

### Step 1 — Start infrastructure

```bash
docker compose -f docker/docker-compose.yml up -d postgres redis kafka kafka-ui pgadmin
```

Wait until all containers are healthy:

```bash
docker compose -f docker/docker-compose.yml ps
```

Expected — all show `(healthy)` or `Up`:
```
incident-kafka      Up (healthy)
incident-postgres   Up (healthy)
incident-redis      Up (healthy)
```

### Step 2 — Create application-local.yml for each service

Each service requires `src/main/resources/application-local.yml` — this file is excluded from git (contains secrets).

> **Important**: Use only standard ASCII hyphens (`-`) in YAML comments, not em dashes (`—`). Em dashes are multi-byte UTF-8 characters that prevent Spring Boot from loading the file.

**Create this file in all 6 services:**

```yaml
jwt:
  secret: local-development-secret-key-minimum-64-characters-long-absolutely-not-for-production-use-only

logging:
  level:
    com.incidentplatform: DEBUG
```

**incident-service** additionally needs WebSocket allowed origins:

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

> The JWT secret must be at least 64 characters. The value above meets this requirement — copy it exactly.

### Step 3 — Build all modules

```bash
./mvnw clean install -DskipTests
```

### Step 4 — Start all 6 services (separate terminals)

```bash
# Terminal 1 — start first, generates the Alertmanager ingestor token
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

### Infrastructure URLs

| Tool | URL | Credentials |
|---|---|---|
| Kafka UI | http://localhost:8090 | — |
| pgAdmin | http://localhost:5050 | admin@admin.com / admin |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | admin / admin |

---

## Running on Kubernetes

### Prerequisites

- Docker Desktop (minimum 6GB RAM allocated)
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

### Step 2 — Point Docker to Minikube's daemon

```bash
eval $(minikube docker-env)
```

> Run this in every terminal where you build images. It affects only the current shell session.

### Step 3 — Build all Docker images

```bash
for service in ingestion-service incident-service notification-service escalation-service postmortem-service oncall-service; do
  echo "Building $service..."
  docker build -t $service:dev -f $service/Dockerfile .
done
```

First run takes 20–40 minutes — Maven downloads all dependencies. Subsequent builds are fast due to Docker layer cache.

Verify:
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

### Step 5 — Deploy

```bash
kubectl apply -k k8s/overlays/dev
```

### Step 6 — Wait for all pods to be ready

```bash
kubectl get pods -n incident-platform-dev -w
```

Wait until all pods show `1/1 Running`. Init containers wait for PostgreSQL and Kafka — typically 2–5 minutes on first deploy. Press `Ctrl+C` when done.

Expected:
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

### Step 8 — Start Minikube tunnel (keep this terminal open)

```bash
minikube tunnel
```

### Step 9 — Verify the cluster is reachable

```bash
curl -s -o /dev/null -w "%{http_code}" http://incident-platform.local/api/v1/incidents
# Expected: 403  (reachable — authentication required)
```

### Kubernetes Configuration Highlights

The `k8s/` directory uses **Kustomize** with environment overlays:

```
k8s/
├── base/               # Environment-agnostic manifests (Deployments, Services, HPA, Ingress)
└── overlays/
    ├── dev/            # Minikube: 1 replica, 768Mi memory limit, relaxed probe delays
    ├── staging/
    └── prod/
```

Key features of the base configuration:

- **Rolling updates** — `maxUnavailable: 0`, `maxSurge: 1` — zero downtime deployments
- **Init containers** — each service waits for PostgreSQL and Kafka before starting
- **Health probes** — readiness and liveness on the management port (never the API port)
- **HorizontalPodAutoscaler** — CPU 70% and memory 80% targets, 1–3 replicas
- **ShedLock** — prevents duplicate scheduled job execution across replicas

---

## End-to-End Test

These steps work for both local and Kubernetes deployments. Replace the base URL as needed:
- **Local**: `http://localhost:808X`
- **Kubernetes**: `http://incident-platform.local`

### Step 1 — Generate a dev token

**Local:**
```bash
TOKEN=$(curl -s "http://localhost:8082/dev/token?userId=11111111-1111-1111-1111-111111111111&tenantId=test-tenant&email=admin@test.com&roles=ROLE_ADMIN" | jq -r .token)
echo "Token: ${TOKEN:0:50}..."
```

**Kubernetes** — `/dev/token` is intentionally not exposed via Ingress. Use port-forward:
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

Expected:
```json
{
  "received": 1,
  "processed": 1,
  "duplicates": 0,
  "fullySuccessful": true
}
```

### Step 3 — Verify the incident was created

**Local:**
```bash
curl -s http://localhost:8082/api/v1/incidents \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" | jq '.content[]'
```

**Kubernetes** — the Ingress JWT_SECRET differs from the local secret. Generate a token via port-forward to get one signed by the cluster:
```bash
kubectl port-forward svc/incident-service 8082:8082 -n incident-platform-dev &
sleep 2
TOKEN_K8S=$(curl -s "http://localhost:8082/dev/token?userId=11111111-1111-1111-1111-111111111111&tenantId=test-tenant&email=admin@test.com&roles=ROLE_ADMIN" | jq -r .token)

curl -s http://incident-platform.local/api/v1/incidents \
  -H "Authorization: Bearer $TOKEN_K8S" \
  -H "X-Tenant-Id: test-tenant" | jq '.content[]'
```

Expected:
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

curl -s -X PATCH http://localhost:8082/api/v1/incidents/$INCIDENT_ID/status \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" \
  -H "Content-Type: application/json" \
  -d '{"status": "ACKNOWLEDGED"}' | jq .
```

Expected:
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
curl -s -X PATCH http://localhost:8082/api/v1/incidents/$INCIDENT_ID/status \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" \
  -H "Content-Type: application/json" \
  -d '{"status": "RESOLVED"}' | jq .
```

Expected:
```json
{
  "status": "RESOLVED",
  "resolvedAt": "...",
  "mttaMinutes": 0,
  "mttrMinutes": 1,
  "allowedTransitions": ["CLOSED"]
}
```

After resolving, `postmortem-service` automatically calls the Gemini API and generates a draft. Retrieve it with:

```bash
curl -s http://localhost:8085/api/v1/postmortems/incident/$INCIDENT_ID \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" | jq .
```

### Step 6 — Check the audit log

```bash
curl -s http://localhost:8082/api/v1/incidents/$INCIDENT_ID/audit \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: test-tenant" | jq '.[]'
```

Shows the full chronological timeline of every event across all services for this incident — created, acknowledged, resolved, notifications sent, postmortem generated.

### Step 7 — (Optional) Register an on-call schedule

For notifications and escalations to reach the right person:

```bash
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
# All modules
./mvnw test -pl shared,ingestion-service,incident-service,notification-service,escalation-service,postmortem-service,oncall-service

# Single module
./mvnw test -pl incident-service
```

### Test Coverage Highlights

| Test class | What it covers |
|---|---|
| `IncidentFsmTest` | 25 parameterized cases — all allowed and forbidden state transitions |
| `IncidentCommandServiceTest` | Deduplication, severity escalation, optimistic lock, FSM validation |
| `IncidentQueryServiceTest` | Filter routing (Specification vs simple query), tenant scoping |
| `IncidentKafkaConsumerTest` | Per-record tenant isolation, TenantContext cleanup in `finally`, no cross-tenant leaks |
| `NotificationServiceTest` | Orchestration, fault isolation between channels, idempotency |
| `NotificationRouterTest` | Routing for all 5 event types, fallback when oncall-service unavailable |
| `NotificationIncidentEventConsumerTest` | Header-based tenant resolution, TenantContext lifecycle |
| `EscalationServiceTest` | Level 1/2 scheduling, ACK cancellation, idempotency, severity timeouts |
| `EscalationSchedulerTest` | Timer logic, level 2 scheduling after level 1, fault isolation |
| `EscalationIncidentEventConsumerTest` | Per-record tenant isolation, sequential records without leaks |
| `PostmortemServiceTest` | Generation, Gemini failure handling, CRUD, audit event publishing |
| `PostmortemRetrySchedulerTest` | Retry logic for FAILED postmortems, max retry limit |
| `PostmortemIncidentEventConsumerTest` | Header tenant wins over payload tenant, ignored event types |
| `JwtUtilsTest` | Token generation, validation, expiry, secret length validation |
| `TenantContextTest` | ThreadLocal isolation between threads, TenantAwareTaskDecorator propagation |
| `OncallScheduleServiceTest` | Schedule creation, overlap detection, current on-call resolution |

---

## Project Structure

```
incident-platform/
├── shared/                        # Shared library (jar, not a runnable service)
│   └── src/main/java/
│       ├── dto/                   # Shared DTOs: ErrorResponse, PageResponse
│       ├── events/                # Kafka event records: IncidentOpenedEvent, AuditEventMessage, ...
│       ├── exception/             # GlobalExceptionHandler, BusinessException, ResourceNotFoundException
│       └── security/              # JwtUtils, JwtAuthFilter, TenantContext, ServiceTokenProvider
│           └── kafka/             # TenantKafkaProducerInterceptor, TenantKafkaConsumerInterceptor
│
├── ingestion-service/             # port 8081 — alert ingestion
│   └── src/main/java/
│       ├── api/                   # AlertController (Prometheus, Wazuh, Generic endpoints)
│       ├── service/               # AlertIngestionService, DeduplicationService, RateLimitingService
│       ├── normalizer/            # PrometheusNormalizer, WazuhNormalizer, GenericNormalizer
│       └── alertmanager/          # AlertManagerTokenRefresher (generates ingestor JWT on startup)
│
├── incident-service/              # port 8082 — incident lifecycle
│   └── src/main/java/
│       ├── api/                   # IncidentController, IncidentAuditController, DevTokenController
│       ├── service/               # IncidentCommandService, IncidentQueryService, IncidentFsm
│       ├── consumer/              # IncidentKafkaConsumer, AuditEventConsumer
│       └── config/                # WebSocketConfig, WebSocketProperties, SecurityConfig
│
├── notification-service/          # port 8083 — multi-channel notifications
│   └── src/main/java/
│       ├── channel/               # SlackNotificationChannel, EmailNotificationChannel, SmsNotificationChannel
│       ├── router/                # NotificationRouter (maps event types to channels)
│       ├── client/                # OncallClient (queries oncall-service for current on-call)
│       └── consumer/              # NotificationIncidentEventConsumer
│
├── escalation-service/            # port 8084 — auto-escalation
│   └── src/main/java/
│       ├── service/               # EscalationService (task scheduling and cancellation)
│       ├── scheduler/             # EscalationScheduler (ShedLock @Scheduled)
│       └── consumer/              # EscalationIncidentEventConsumer
│
├── postmortem-service/            # port 8085 — AI postmortem generation
│   └── src/main/java/
│       ├── client/                # GeminiClient interface + GeminiRestClient implementation
│       ├── service/               # PostmortemService
│       ├── scheduler/             # PostmortemRetryScheduler (retries FAILED postmortems)
│       └── consumer/              # PostmortemIncidentEventConsumer
│
├── oncall-service/                # port 8086 — on-call schedule management
│   └── src/main/java/
│       ├── api/                   # OncallScheduleController
│       └── service/               # OncallScheduleService (overlap detection, current on-call)
│
├── docker/
│   ├── docker-compose.yml         # PostgreSQL, Redis (AOF), Kafka (KRaft), pgAdmin, Kafka UI, Prometheus, Grafana
│   ├── prometheus.yml             # Scrape config for all 6 management ports
│   └── grafana/
│       └── provisioning/          # Grafana datasource auto-provisioning
│
└── k8s/
    ├── base/                      # Deployments, Services, HPA, Ingress, ConfigMap, Namespace
    │   ├── infrastructure/        # PostgreSQL StatefulSet, Kafka StatefulSet, Redis Deployment
    │   └── {service}/             # deployment.yml + service-hpa.yml per service
    └── overlays/
        ├── dev/                   # Minikube: 1 replica, 768Mi memory, relaxed probe delays, secrets
        ├── staging/
        └── prod/
```

---

## Author

Built by Łukasz Pławiak as a portfolio project demonstrating production-oriented Java/Spring Boot backend development.

Frontend companion: [incident-platform-frontend](https://github.com/lukaszplawiak/incident-platform-frontend) — Angular 21 SPA with real-time WebSocket dashboard.