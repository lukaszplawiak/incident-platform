# ADR-0007: Admin MFA reset (#0-88)

- **Status:** Accepted
- **Backlog:** #0-88
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

`POST /api/v1/users/{id}/mfa-reset` (`MfaService.resetMfaByAdmin`), any
ROLE_ADMIN of the tenant on any other user that is not archived, admins included (so a second admin, or
operator admin, can help another), never on oneself. Deactivated users too, on purpose (review): resetting a
stranger's factor before reactivation leaves no window in which it works. Clears factor, pending secret, backup codes and the sessions' MFA
marks, and ends every session and unfinished login; emails its own outbox type `MFA_RESET` (V24; review:
a shared MFA_DISABLED text hid a reset the owner did not ask for) and audits `MFA_RESET_BY_ADMIN` (`shared`,
distinct from self-service `MFA_DISABLED`; written to auth-service's audit outbox in the reset's transaction, #0-84).
- Step-up: the admin's session must pass the platform API's rule (`MfaSessionStatusService.check` ==
  ACCEPTED: MFA within 12 h, factor announced >= 24 h ago). First built without the 12 h / 24 h parts; review
  showed a password thief could enrol a factor and reset the whole tenant at once, and a 30-day refresh chain
  (rotation keeps the original `mfa_verified_at`) could act weeks later. An API key (no session) never passes.
- Rate limit (review): `MfaResetRateLimiter`, per admin and per tenant (`mfa-reset.rate-limit.*`, 10/30 per h),
  fail-closed like the platform API's, own breaker `mfa-reset-ratelimit`, same lazy Redis connection; consumed
  as the last check before the change (after step-up, user lookup and factor check) so refused attempts
  cannot drain a tenant's budget. The service throws
  `RateLimitRefusedException`, `UserController` maps it to 429/503 + Retry-After. Types used by both limiters
  (in auth-service's `ratelimit` package, not `shared`): `RateLimitDecision`, `RedisTokenBuckets`. Alerts
  `AdminMfaResetRateLimited` (critical, content-free) and `AdminMfaResetRateLimitUnavailable` (high).
- A password reset never touches MFA (NIST SP 800-63B; Okta/Entra pattern). Order for a stranger's
  factor: password reset first, then the MFA reset, or the old password's holder could enrol again.
- A single operator admin has no one to reset them: break-glass is a one-off run of auth-service
  (`BreakGlassMfaResetRunner`), chosen only by the subcommand `break-glass-mfa-reset` as the first
  argument (as `manage.py <command>` / `kc.sh <subcommand>`): `BreakGlassCommand.prepare` in `main` turns
  the web server off, drops the subcommand and adds a named property source as a marker; the runner's
  condition and `NotBreakGlassCommand` (scheduling off: no scheduled job takes a ShedLock lock; the one
  lock the command takes is the audit relay's, in its single `relayNow()` after the reset, released when
  it returns, a killed process holding it at most `lockAtMostFor`, #0-84) check only
  that marker, which no environment variable or `--property` can create. Earlier versions started on the
  property `break-glass.mfa-reset.user-email` (args or env); review found a stray variable in a deployment
  would turn the service into the command. The options stay `--break-glass.mfa-reset.*`.
  The runner only reports its code as an `ExitCodeGenerator`, and `AuthServiceApplication.main` exits via
  `SpringApplication.exit`. Admins of `platform-operator` only; actor/reason without control,
  line-separator or formatting characters (U+2028/9, bidi overrides); email trimmed and matched exactly as
  stored (like login). The audit event also records `executedOn` (OS user and host of the process), since
  the actor is whatever name the operator types. The runner sets `TenantContext` to the operator tenant
  (log MDC). A customer tenant's only admin: the operator's MFA recovery (#0-90, below).
  Same code path as the admin reset, so the email goes out; audited as `MFA_RESET_BREAK_GLASS` into
  `auth_audit_outbox` in the reset's transaction (#0-84): no event row, no reset. After the commit the runner
  calls `AuditOutboxRelay.relayNow()` once and logs whether the event reached Kafka; if not, the running
  auth-service's relay sends it. The exit code does not depend on Kafka. Chosen over SQL (no email, no audit)
  and pgAudit/triggers (detection, not the audit trail). Until #0-84 it waited for Kafka's ack inside the
  transaction (`publishAuthConfirmed`, removed with its `audit-timeout` option).
- Audit records' `X-Tenant-Id` (found in this review): every sender builds its records through `TenantRecords`
  (#0-91), which writes the header from the payload's tenant; `TenantKafkaProducerInterceptor` writes nothing
  and only counts a record without a valid header. History: before #0-88 151 of 155 local audit records had
  no header (the interceptor stamped it from `TenantContext`, registered in only some services, and
  logins/jobs/commands have none); #0-88 made the sender set it, #0-91 made `TenantRecords` the one writer.
- V23's comment that a password reset removes a factor within the grace period predates #0-88; V23 stays
  unchanged (checksum), V24's header says so.
