# ADR-0009: Tenant suspension (#0-82, done; offboarding is #0-101)

- **Status:** Accepted
- **Backlog:** #0-82, #0-101, #0-89, #0-85, #0-90, #0-67, #0-21, #0-35, #0-102
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.
- **Updated:** 2026-10-09 — the owner's later edit of this record in `project.md`, carried over verbatim.

## Record

`TenantLifecycleService` (suspend/resume), `TenantAccessService` (status
-> `TenantAccess`, guards for public paths), V30 columns on `tenants`, `TenantStatusFilter` in `shared`. Non-obvious:
- The filter is built inside `buildCommonSecurity` from the context's `TenantStatusProvider`, not declared as a
  bean: a filter bean is also registered as a plain servlet filter outside the chain. A context without a provider
  (a test slice) gets FULL. Services' own chains get it automatically because they all call `buildCommonSecurity`.
- Only a `UserPrincipal` (person or API key) is checked; service tokens pass (their background work is paused in
  the schedulers, step 2b, below; a person's action relayed with one, the Slack ACK, checks itself). Read-only allows GET/HEAD/OPTIONS plus the service's
  `tenant-status.read-only.allowed-writes`: `"METHOD /ant/pattern"` entries (auth-service: logout, password change,
  MFA setup/enable/disable, and an admin's key/integration revoke, revoke-created-by, user status and MFA reset),
  matched on `UrlPathHelper`'s path within the application (decoded, `;params` removed, `//` collapsed) and the
  request's method; a `.`/`..` segment never matches; an entry without a write method fails at startup. Bare
  patterns were rejected in review: they admitted every method on a path, so a write added later under an allowed
  prefix would pass silently. `PATCH /users/{id}/status` also reactivates, which `UserManagementService.updateStatus`
  refuses with `requireCanWrite`. Because those writes include resetting/disabling others' MFA and deactivating
  users, `suspend` refuses `READ_ONLY` with reason `SECURITY` (400; also CHECK
  `chk_tenants_security_suspension_full` in V30): a taken-over tenant must be FULL. A new account-security route must be added there; public paths (reset, invite)
  are checked by `TenantAccessService` instead.
- Other services (step 2a): `AuthServiceTenantStatusProvider` (`shared`), registered when `auth-service.base-url`
  is set (all six; a new service must set it or it silently gets "always FULL"), asks
  `GET /api/v1/internal/tenant-status` (auth's second ROLE_SERVICE endpoint, answers `TenantAccess` and, since step 2b, `since` = `suspended_at`) with a
  service token for the tenant. Cache 10 s, one call per tenant at a time (the others get the expired entry
  meanwhile, or wait for the call, at most `FOLLOWER_WAIT` 5 s, then FULL). On failure the last known answer
  HOWEVER OLD (static stability; a time limit on it was rejected: it abandons a known suspension, and with a
  rejected token for good); FULL only for a tenant never answered for; either way kept another TTL (a per-tenant
  breaker, no resilience4j in shared). A full cache (10 000 tenants; purged at most once per TTL, not on every
  request) purges expired entries but never a known suspension: in an outage every entry is expired, and purging
  one would turn the suspension into FULL. Background refreshes are claimed per tenant (a set), one at a time. A leader re-reads the
  cache after winning the in-flight slot; a leader that dies with an Error fails its followers rather than leave
  them waiting. `knownAccessOf` (a default method on `TenantStatusProvider`) never waits: cached status, FULL if
  none, and a refresh on a virtual thread when missing or expired; STOMP CONNECT (message-channel pool) and the
  WebSocket sweep use it, so an outage cannot tie up those threads. Alert `TenantStatusCacheFull` on
  `cache.puts.skipped`. Counted in
  `service_client_fallback_total{client="tenant-status"}`: alert `TenantStatusLookupFailing` (outage, high,
  reason!="auth") and `TenantStatusLookupRejected` (reason="auth", critical, no `for`: a misconfigured token does
  not heal). CI `check-tenant-status-config.sh` fails a service without `auth-service.base-url`. Durations are read
  with Boot's `Binder`, not `@Value`/`getProperty`: those do not convert "PT10S" outside a Boot context (found by
  the context-runner test). In a `@WebMvcTest` slice that loads `application.yml` the provider is created too:
  mock `TenantStatusProvider` there, and stub it (a Mockito default `null` would NPE the filter's switch).
- STOMP (incident-service): `StompAuthChannelInterceptor` refuses CONNECT for a known NONE (`knownAccessOf`: a tenant
  the instance has not cached yet connects, and the sweep closes it within two sweeps) and binds session -> tenant in
  `TenantWebSocketSessions`, a `WebSocketHandlerDecoratorFactory` that tracks sockets and sweeps every 10 s, closing
  NONE tenants' sessions with 1008; a session is unbound only after its close succeeded (a failed close is retried
  next sweep), and a lookup that throws skips that tenant only. Deliberately no ShedLock: sessions are per replica.
  The only `@MessageMapping` (`/incidents/refresh`) reads, so a READ_ONLY session cannot write through STOMP.
  Binding relies on STOMP's session id being the `WebSocketSession` id (true for the plain `/ws` endpoint, no
  SockJS); a CONNECT whose socket is untracked is WARNed and counted (`websocket.sessions.unbound`).
- API keys: one choke point, `ApiKeyIntrospectionService.resolve`. FULL -> no key in auth-service; for ingestion
  a valid TENANT key answers `{"active":false,"suspended":true}` (step 2a; after the key's own checks, like paused)
  -> `ApiKeyIntrospection.Suspended` -> shared `ApiKeyLookupResult.Suspended` -> 403 `TENANT_SUSPENDED`, NOT
  counted by the IP limiter (a suspended tenant's retrying sender would throttle a shared NAT); READ_ONLY ->
  keys still read in auth-service, and ingestion's introspection answers `{"active":false,"paused":true}` for a
  valid TENANT key (only after every other check, so "paused" never vouches for a bad key). ingestion parses it into
  `ApiKeyIntrospection.Paused` (a sealed result, not `Optional`), cached 5 s with the negative answers (step 2a;
  it used to be never cached), not a failure for the IP limiter ->
  shared `ApiKeyLookupResult.Paused` -> 503 + `Retry-After: 300` + `TENANT_READ_ONLY`, so Alertmanager retries.
  Deliberately a 200 answer, not a 5xx from auth-service: a 5xx would trip ingestion's circuit breaker and
  fallback metric for every tenant. A key cached as active (60 s) before a suspension is caught by the chain's
  `TenantStatusFilter` within 10 s; ingestion sets `tenant-status.read-only.retry-after: PT5M`, so the filter's
  read-only refusal there is 503 + Retry-After too, never a 4xx that Alertmanager would drop.
- Status transitions lock the tenant row (`TenantRepository.findByIdForUpdate`, `FOR NO KEY UPDATE`, waits) so two
  operators cannot both read the same previous mode. `requireCanSignIn`/`requireCanWrite` read the status `FOR SHARE`
  (`findStatusForSignIn`, `MANDATORY` transaction), which conflicts with it: a login racing a FULL suspension
  commits its refresh token before the session cleanup or reads SUSPENDED (without it the token would revive on
  resume). Lock ORDER matters: tenant row first, token rows second, as the suspension does; refresh and MFA check the
  tenant (via `peekToken`) before `consumeToken`, which row-locks the token. The opposite order deadlocked (40P01,
  reproduced by mutation). A sign-in waits at most 3 s (`setLocalLockTimeout`, i.e. `SET LOCAL lock_timeout`), then
  `TenantStatusBusyException` -> 503 + Retry-After 5 (`TenantStatusBusyHandler`, highest-precedence advice): auth's
  pool is 5 connections. Suspend/resume wait at most 5 s (`TenantLifecycleService.LOCK_TIMEOUT`, not reset after
  the lookup: the token cleanup's waits are bounded too), and since step 2a any `PessimisticLockingFailureException`
  reaching the web layer (lock timeout, a deadlock's loser) is 503 + Retry-After `RESOURCE_BUSY`, not 500
  (callers that map a busy row themselves, like `ApiKeyCreationLimit`'s 429, catch it first). A sign-in refused for
  suspension throws `TenantSuspendedSignInException` (`requireCanSignIn` / `requireCanJoin` for the invite, with
  user and `SignInFlow`); `SignInRefusalHandler` (web layer, so after the rollback) has `SignInRefusals` write
  `USER_SIGN_IN_REFUSED_TENANT_SUSPENDED` and count `auth.signin.refused{flow,access,outcome}`. Bounded per user (the
  rollback gives an invite/reset token back, so a refusal can be replayed without end): each refusal
  `recordFailure`s `BruteForceProtectionService.Scope.SUSPENDED_SIGN_IN` (user id), and over the limit the answer is
  429 + Retry-After, no event (#0-89's rule: bound the action, never the audit). Not atomic (parallel refusals can
  overshoot a little) and, like every Redis limiter here, fail-open without Redis (accepted, README gap). Counting it
  against the LOGIN lockout instead was rejected: it bounds only login and locks a resumed tenant's users out. Each
  caller passes its own `SignInFlow`, `issueTokens` included. It looks the recorder up
  lazily (`ObjectProvider`) so `@WebMvcTest` slices still start. `requireCanWrite` stays for non-sign-in writes
  (reactivation), not audited as a sign-in. Invite/reset hash the password before taking the lock. Two-thread Testcontainers tests
  (login, refresh, backup code, lock timeout, two operators) pin all of it; they wait on `pg_stat_activity`
  `wait_event_type = 'Lock'`, not a sleep. The per-request filter lookup is unlocked.
- Every auth-service table with a tenant's data has a foreign key to `tenants` (V31 adds them `NOT VALID` after
  recording any id without a row, V32 validates; `ON DELETE RESTRICT`, a tenants row is never deleted). Not on
  `auth_email_outbox` / `auth_audit_outbox`: queues whose events must never be refused for their tenant. Not in
  the other services either: a cross-service FK would tie their schemas to auth-service's (#0-85). Test fixtures
  must record their tenant first (`AuthRepositoryIntegrationTest.recordTenant`). A tenant without a row can now
  only come from a token minted outside auth-service (`/dev/token`): still FULL access, counted
  (`platform.tenant.status.missing`, alert `PlatformTenantStatusRowMissing`), WARN once per tenant, as a tripwire;
  its writes fail on the foreign key, answered 403 by `UnrecordedTenantHandler` (only V31's nine constraint names,
  checked against the schema in `AuthRepositoryIntegrationTest`; any other integrity error keeps the shared 500).
  A new tenant-data table needs its constraint name added there too. V31 logs each adopted id as a WARNING
  (Flyway's "DB:" line) and has a 5 s `lock_timeout`; V32's header is the runbook if validation ever fails.
  The operator tenant's bootstrap stops its run (FAILED, gauge unchanged) when it cannot record the row.
- FULL ends sessions (`AuthTokenRepository.invalidateSessionsOfTenant`: REFRESH + MFA continuations), keeps invite
  and reset links (refused while suspended, valid again after resume). The MFA-recovery cancel link is not checked:
  it works throughout, like the rest of #0-90. Nothing revoked, so resume is complete.
- The auth email outbox defers (no attempt counted) INVITE in either mode and PASSWORD_RESET in FULL; security
  notices and MFA recovery emails go out. MFA recovery (#0-90) is NOT paused: it is the operator's own action, the
  takeover flow is suspend FULL -> recover -> resume.
- Customer-side audit events use a name-based UUID of the tenant id as resource and `platform-operator` as actor;
  the operator-side event has the operator's id and the note.
- `rotateRefreshToken` now refuses a deactivated user, `updateStatus(false)` ends the user's sessions, and a
  personal key of a deactivated owner does not resolve: user deactivation used to be login-only.
- Background work (step 2b, `shared` `pause/`): notification-, escalation- and postmortem-service set
  `tenant-pause.table` (`<service>_paused_tenants`), written only by `PausedTenantsSync` (ShedLock
  `paused-tenants-sync-<table>`, 10 s). Non-obvious decisions:
  - A table, not a set in memory (lost on restart, different per replica) nor a status on every work row (new
    states the consumers' cancel paths would have to know). Excluded in each picking query (`NOT EXISTS`, native:
    the table has no entity), never skipped in Java after the `LIMIT`, where the paused tenant's oldest rows
    would starve everyone else. The queries name the table through a constant (`EscalationTaskRepository
    .PAUSED_TABLE` etc.; plain string concatenation, as a text block strips the space before `+`), and
    `PausedTenantsConfiguration` fails startup unless `PausableWork.pausedTable()` equals the property.
  - Only auth-service's own answer changes the table (`TenantStatusProvider.confirmedStateOf`: the answer or
    the last one kept through an outage, empty for a tenant never answered for), so a restart during an
    auth-service outage resumes no one and pauses no one (fail-open, as the filter).
  - The pause is measured from the suspension, `paused_at` = auth-service's `suspended_at` (sent as `since`,
    kept across a mode change; `now()` if an older auth-service sends none), never from when the sync saw it: a
    fixed allowance for the sync's delay was outrun by a late sync. One database, one clock (revisit with
    per-service databases, #0-67). The end is when the sync sees the resumption, which only lengthens a timer.
  - The per-row `TenantWorkGuard` uses `accessOf`, not `knownAccessOf`: the cache-only call answers FULL for an
    uncached tenant, so after a restart a suspended tenant's oldest rows went out before the first sync. As it
    may wait ~3 s per uncached tenant in an outage, every pausing scheduler has a processing budget validated
    against its ShedLock at startup, and calls `TenantWorkGuard.prefetch` with its batch's tenants first (8 at a
    time on virtual threads, waits at most 10 s, never fails the run), so the per-row check reads the cache. The
    budget's clock starts before the prefetch (its 10 s must not eat the 30 s margin below the lock). A prefetch
    is good for one status TTL (10 s): a longer loop asks row by row again, accepted, as by then the sync has
    paused a suspended tenant and the next query leaves it out.
  - Candidates (paused plus with waiting work) are asked in tenant id order, each run continuing after the last
    tenant a used-up budget reached (per-JVM cursor): paused-first ordering let enough paused tenants starve
    new suspensions.
  - Resume hooks run in the transaction that deletes the paused row: escalation moves PENDING timers by
    `now() - GREATEST(suspended_at, incident_opened_at)`, never negative (the timer's start; tasks already
    due at the suspension untouched; `version` bumped); notification clears `first_lookup_failure_at` (a second writer
    of queue rows, safe because the scheduler reads no paused row); postmortem none.
  - Consumers and the incident/audit outbox relays are never paused. The Slack ACK path (no button since #0-21,
    back with #0-35) refuses a suspended tenant itself, as service tokens pass the filter.
  - Alerts `TenantPauseSyncFailing` and `TenantPauseSyncStalled` (no completed run in 5 min,
    `tenant.pause.sync.runs` registered at zero). Scale limits: #0-102.
