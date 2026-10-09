# ADR-0004: Alert sources authenticate with Integration API keys; platform alerts go out of band (backlog #0-16)

- **Status:** Accepted
- **Backlog:** #0-16
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

Decided 2026-09 (A1 + B3). Before: the platform's Alertmanager posted with a 30-day `ROLE_SERVICE`
JWT for tenant `system` (no `jti`, not revocable, minted by ingestion-service itself), ingest accepted
any `ROLE_SERVICE` token, and Integration keys did not work in ingestion-service at all (its forked
chain never added `ApiKeyAuthFilter`; the lookup there was a no-op).

- **A1, introspection**: every external alert source, the platform's own Alertmanager included,
  sends an Integration API key (`ApiKey ipl_…` or `Bearer ipl_…`). ingestion-service hashes it and
  asks auth-service (`POST /api/v1/internal/api-keys/introspect`, body = SHA-256 only, answer
  `{active, tenantId, teamId, scopes, keyId, expiresAt}` or `{active:false}` with no reason — RFC 7662
  semantics). Only TENANT keys (Integration keys, ADMIN-created tenant keys) introspect as active: a
  PERSONAL key would let a RESPONDER ingest, which their JWT cannot (the scope-vs-role gap is #0-46), and it
  has no team. Same shape as #0-30: `ApiKeyIntrospectionClientImpl` behind Retry + CircuitBreaker,
  wrapped by `CachingApiKeyIntrospectionClient` (active ≤ 60 s and ≤ `expiresAt`, inactive 5 s,
  failures never). The positive TTL **is** the revocation latency. Rejected: A2, replicating key hashes
  over Kafka (contradicts #0-30; produce rights on the topic would become part of the auth boundary;
  lag stalls revocation), and A3, a long-lived JWT per source (with the shared HMAC secret, #0-13, any
  service can forge one, and revocation still needs a lookup per request).
- **Purpose token**: ingestion-service cannot know the tenant before introspection, so it calls with
  a tenant-less token (`purpose=api-key-introspection`, `aud=auth-service`, with `jti`) that becomes an
  `IntrospectionPrincipal`. auth-service accepts that purpose only on the introspection route; every
  other route in every chain denies it (`authenticatedExceptPurposeTokens()`, deny by default — the
  #0-14 concern). Rejected: a reserved "system" tenant in the claim (a pseudo-tenant valid everywhere
  unless every endpoint guards it).
- **Response contract** (Alertmanager's webhook retries network errors and 5xx, drops every 4xx):
  `401` + `WWW-Authenticate` = definite "no" (unknown, revoked, expired); `503` + `Retry-After` =
  auth-service could not answer — never 401 for "don't know", or an outage silently loses pages.
  Authentication fails closed; only throttling fails open. Failed attempts are limited per IP before
  the lookup (`AuthFailureRateLimiter`, bucket4j/Redis, fail-open, 429); a cached valid key bypasses
  it. The rate limiter's own 429 after authentication is still lost by Alertmanager: backlog #0-37.
- **B3, out of band**: a system must not be the only channel reporting its own failure. Alertmanager
  sends Watchdog to a dead man's switch URL, critical `scope: platform` alerts by email to the operator
  (`continue: true`), and everything to ingestion-service as incidents of the reserved
  `platform-operator` tenant (history, postmortems). Rejected: dogfooding only (fails exactly when the
  pipeline it would report on fails).
- **Reserved tenants** (`ReservedTenants`): `platform-operator` and the legacy `system` are refused
  wherever a tenant id is chosen (tenant provisioning, dev token). The operator tenant's first admin is
  created by invite (`OperatorTenantBootstrap`, `OPERATOR_ADMIN_EMAIL`). No migration seeds a user any
  more: `V1_1__seed_admin_user` created `admin@incidentplatform.com` / `changeme` on every new database,
  so its body is now a no-op (the class stays, or Flyway cannot resolve version 1.1 in existing
  histories; a Java migration has no checksum), and `V20` archives that account where the password is
  still `changeme`.
- Follow-ups: #0-37 (429), #0-38 (key format/checksum), #0-43 (introspection amplification from many IPs, accepted residual risk), #0-40 (DLT alerting to the operator route),
  #0-17 (fallback alert, now unblocked).
