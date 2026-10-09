# ADR-0003: Service-to-service auth: per-tenant service tokens (backlog #0-11)

- **Status:** Accepted
- **Backlog:** #0-11
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

Service tokens (`JwtUtils.generateServiceToken(serviceName, tenantId)`) used to be rejected by
`JwtAuthFilter`, which required a UUID `sub`, an `email` and a tenant that a service token did not
have. Every service-to-service HTTP call was a 401, hidden because the clients fail open. The
filter now recognises a token by its `serviceName` claim and builds a `ServicePrincipal`
(`ROLE_SERVICE`, tenant from the signed claim), but only if the token's `aud` claim names the
service that received it (`ServiceNames`; a filter built without a service name accepts none).
auth-service accepted none until backlog #0-21/#0-30; it now accepts `aud=auth-service` for two
`ROLE_SERVICE` endpoints, `GET /api/v1/internal/slack-workspace` and, since #0-82,
`GET /api/v1/internal/tenant-status` (read by every other service). Decided in #0-30: another service
that needs auth-service-owned tenant data pulls it over this kind of narrow HTTP call and caches it
briefly — not Kafka replication of that data. Without the audience check a token minted to call oncall-service
would authenticate on every service, and any endpoint that is only `authenticated()` would accept it. No HTTP filter reads `X-Tenant-Id`; the header the
clients still send is informational only. `ServiceTokenProvider.getToken(tenantId)` caches one token
per (tenant, audience) (bounded; the tenant id comes from Kafka payloads and is validated: since #0-92 a
`TenantIds` slug, 3-63 `[a-z0-9-]`; it was 1-100 characters without whitespace or control characters).

Known limitation (decided, not overlooked): all services share one HMAC secret, so any service can
mint a token for any tenant and audience. Per-tenant, per-audience tokens stop a forged header and
a token replayed against the wrong service, not a compromised service. The structural fix (asymmetric keys, or mTLS / a token issuer) is backlog #0-13;
the README "Design Decisions" section records why RS256/Keycloak was rejected for now.

Fail-open is kept, but made visible: clients call `ClientFallbackMetrics.record(...)` from their
fallbacks (`service_client_fallback_total{client,target,reason}`). `reason="auth"` is a 401/403.
Exception: where an empty result changes who is notified, the client records the metric and then
throws (`OncallLookupUnavailableException`, backlog #0-19), so "unavailable" is never read as
"nobody there".
