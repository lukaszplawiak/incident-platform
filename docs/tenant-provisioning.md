# Tenant provisioning

How a customer tenant comes into existence, and how its first admin gets in. Background: backlog
#0-80 (fixed); suspending and offboarding a tenant is backlog #0-82 (open).

## The model

- A tenant is a row in auth-service's `tenants` table (V21): id, display name, the address its first
  admin invite went to, when and by which operator it was created. The other services keep treating
  `tenant_id` as a plain string on their own rows.
- Only a **platform operator** creates one: an admin of the reserved `platform-operator` tenant,
  logged in with a JWT, **in a session that completed MFA recently, with a factor the account has
  had for a while** (backlog #0-83):
  - the login finished with a TOTP or backup code, at most `platform.mfa.max-session-age` ago
    (default 12 h, `PLATFORM_MFA_MAX_SESSION_AGE`; refreshing the token does not renew it);
  - the email announcing the account's factor ("Two-factor authentication was enabled") was sent at
    least `platform.mfa.enrolment-grace` ago (default 24 h, `PLATFORM_MFA_ENROLMENT_GRACE`). Enabling
    MFA needs only a password, so without the grace period someone who has only an operator's password
    could enrol a factor of their own. Every enable and disable emails the account's address, the grace
    period gives its owner that long to react (counted from when the email went out, so an SMTP delay
    does not shorten it): reset the password, then have another operator admin reset the factor (below).
    In a customer tenant another admin of that tenant resets it; a customer tenant's only admin has the
    operator's recovery ([below](#a-customer-tenants-only-admin-is-locked-out-of-mfa)).
    Disabling and re-enabling MFA (e.g. a new phone) starts the grace period again; so does it to send a
    lost notice again. A password reset never touches the factor (backlog #0-88).

  The platform API, `/api/v1/platform/tenants`, refuses everyone else with `403`: admins of
  customer tenants, other roles of the operator tenant, an operator admin whose session does not
  meet the conditions above (the `403` names the failed condition; a factor that is too new and one
  whose email was never sent share one message), API keys (also an operator admin's own personal
  key), service tokens and purpose tokens. The rule is enforced in auth-service's filter chain and
  again on every controller method (`PlatformAccess`). The MFA part is checked on the server per
  request, from the session the access token names, so logging out or disabling MFA ends access at
  once. In Kubernetes the Ingress routes
  `/api/v1/platform` like every other auth-service path, so the API is reachable from wherever the
  Ingress is; that rule is its only protection.
- **Writes are limited per operator and in total**: creating a tenant and reissuing an invite share
  a budget of `platform.rate-limit.operations-per-hour` per operator (default 20,
  `PLATFORM_RATE_LIMIT_OPERATIONS_PER_HOUR`) and of
  `platform.rate-limit.global-operations-per-hour` for all operators together (default 50,
  `PLATFORM_RATE_LIMIT_GLOBAL_OPERATIONS_PER_HOUR`, at least the per-operator value), so several
  taken-over operator accounts cannot multiply the number of invite emails. Both kept in Redis. Over
  either: `429` with `Retry-After`. While Redis cannot be checked: `503` with `Retry-After`
  (fail-closed: onboarding waits rather than running unlimited). Reads are not limited.
- **Unusual activity alerts by email** (critical, so outside the platform): more than 10 tenants
  created in an hour (`PlatformTenantProvisioningSpike`), or either limit being reached
  (`PlatformApiRateLimited`). `PlatformApiRateLimitUnavailable` (high) reports the Redis case.
- The tenant's **first admin is invited**, never given a password. Creating the tenant writes the
  tenant row, the admin user (no password, `ROLE_ADMIN`) and the invite email, in one transaction:
  if any of it fails, nothing is created. The admin sets their password by accepting the invite, as
  any invited user does. No password is in a migration, in configuration or in the API.
- Once the tenant has an admin who has accepted, the operator has nothing more to do in it and,
  apart from the delayed, announced MFA recovery of a tenant's only admin
  ([below](#a-customer-tenants-only-admin-is-locked-out-of-mfa), backlog #0-90), cannot act in it:
  the platform API creates tenants, reissues the first invite while nobody in the tenant can log in
  as an admin, and shows and lists tenants' metadata (never their data). Everything else is the
  tenant admin's (users, teams, integrations, Slack, MFA policy).
- Every action is audited twice: in the operator tenant (`TENANT_PROVISIONED`,
  `TENANT_ADMIN_REINVITED`: which operator did what to which tenant) and in the new tenant
  (`USER_CREATED`, `USER_INVITE_*`). The operator-tenant events carry the admin's email address, so
  an erasure request for that person covers them too (anonymizing a user does not touch the audit
  log; backlog #0-48).

This is the platform's one cross-tenant capability. It reverses backlog #0-16's "no cross-tenant
create tenant endpoint" narrowly: a multi-tenant platform has to onboard tenants at runtime, and
the alternatives (a seeded admin with a known password, as `V1_1` used to create; a configuration
entry and a restart per tenant) do not scale and put credentials or tenant lists into deployment
config.

**Protect the operator accounts accordingly.** Keep the number of operator admins small. The
platform API already requires MFA of the caller's session; also turn on `mfaRequired` for the
operator tenant (`POST /api/v1/tenants/settings` with `{"mfaRequired": true}`, as its admin), so
every operator account, not only those that use the platform API, has a second factor.

## Prerequisite: an operator admin

The operator tenant bootstraps itself: with `OPERATOR_ADMIN_EMAIL` set
(`platform.operator.bootstrap.admin-email`), auth-service invites that address as the operator
tenant's first admin (`OperatorTenantBootstrap`, backlog #0-16, #0-49). Accept the invite and log
in as README "Step 5" shows (steps 2 and 3).

An operator admin who already had MFA before backlog #0-83 was deployed does too: that migration
(V23) takes over older factors as established for every other tenant, but not for
`platform-operator`, since such a factor may have been enrolled with the password alone. Disable
MFA and enable it again once, as below, and wait out the grace period.

That login has no second factor yet, so the platform API answers `403`. Enable MFA once, with that
password-only token, from a real login (setup and enable need a live login session; a token whose
session was ended by logout or a password reset gets `401`). The account's address then gets a
"Two-factor authentication was enabled" email, and the platform API accepts the new factor **24 hours
after that email went out** (the grace period above); plan the first operator's setup a day ahead.

```bash
# 1. Get a TOTP secret: add the response's qrUrl (an otpauth:// URI) to an authenticator app,
#    or enter its secret by hand
curl -s -X POST http://localhost:8087/api/v1/auth/mfa/setup -H "Authorization: Bearer $OP_TOKEN"
# 2. Confirm with a current code; store the backup codes the response returns
curl -s -X POST http://localhost:8087/api/v1/auth/mfa/enable \
  -H "Authorization: Bearer $OP_TOKEN" -H "Content-Type: application/json" -d '{"totpCode": "123456"}'
```

From then on, every command below uses a login that completed MFA:

```bash
MFA_TOKEN=$(curl -s -X POST http://localhost:8087/api/v1/auth/login \
  -H "X-Tenant-Id: platform-operator" -H "Content-Type: application/json" \
  -d '{"email": "ops@incident-platform.local", "password": "<password>"}' | jq -r .mfaToken)
OP_TOKEN=$(curl -s -X POST http://localhost:8087/api/v1/auth/mfa/verify \
  -H "Content-Type: application/json" \
  -d "{\"mfaToken\": \"$MFA_TOKEN\", \"totpCode\": \"<current code>\"}" | jq -r .accessToken)
```

The access token lives 15 minutes; `POST /api/v1/auth/refresh` keeps the session, MFA included, but
not beyond 12 hours after the MFA login: then log in again with a code. Logging out, or disabling
MFA, ends the session's access to the platform API at once.

**An unexpected "Two-factor authentication was enabled" email** means someone used the account's
password and enrolled a factor of their own. In this order:

1. Reset the password with "Forgot password". It ends every session and every unfinished login and
   revokes the account's personal API keys (backlog #0-89), so whoever had the old password is out. It does not remove the factor (backlog #0-88): a mailbox alone
   must not undo MFA.
2. Another admin of `platform-operator` resets the account's MFA, from a session that meets the
   same conditions as the platform API (MFA within 12 h, a factor announced at least 24 h ago):

   ```bash
   curl -s -X POST http://localhost:8087/api/v1/users/<user-id>/mfa-reset \
     -H "Authorization: Bearer $OP_TOKEN" -o /dev/null -w '%{http_code}\n'   # 204
   ```

   It removes the factor, the backup codes and every session of the account, revokes its personal
   API keys (backlog #0-89), emails it ("An administrator reset two-factor authentication on your
   Incident Platform account"), and is audited as `MFA_RESET_BY_ADMIN` (the metadata's `personalApiKeysRevoked`
   says how many keys went). Not on your own account (`403`); a password-only session, a factor enrolled
   less than the grace period ago or an API key also gets `403`, naming the condition. Resets are
   limited: `mfa-reset.rate-limit.per-admin-per-hour` (default 10,
   `MFA_RESET_RATE_LIMIT_PER_ADMIN_PER_HOUR`) and `mfa-reset.rate-limit.per-tenant-per-hour` for
   all admins of a tenant together (default 30, `MFA_RESET_RATE_LIMIT_PER_TENANT_PER_HOUR`, at
   least the per-admin value, checked at startup). Only a reset about to happen counts (not a
   `403`, `404` or `409`). Over either: `429`; while the limit cannot be checked in Redis: `503`
   (fail-closed); both with `Retry-After`. Reaching the limit alerts the operator by email
   (`AdminMfaResetRateLimited`); Redis unavailable raises `AdminMfaResetRateLimitUnavailable`.
   To also revoke the tenant and integration keys the account created since the suspected
   compromise (the reset keeps them, as integrations must not stop with their creator; the email
   counts all of them still active, whatever their age, but only those with a recorded creator), send
   `{"revokeKeysCreatedSince": "<instant>"}` as the body; the reset's audit event then lists the revoked
   `keyIds` and `integrationIds`, and each integration is audited as `INTEGRATION_REVOKED`. Later,
   any admin can do it on its own: list them with `GET /api/v1/api-keys?createdBy=<userId>`, revoke
   with `POST /api/v1/api-keys/revoke-created-by` and `{"userId": "...", "since": "<instant>"}`
   (backlog #0-89; tenant and integration keys created before V26 have no recorded creator, revoke
   those one by one).
3. Log in with the new password, enable MFA with your own authenticator, and check the operator
   tenant's audit log (`MFA_ENABLED`, `MFA_DISABLED`, `MFA_RESET_BY_ADMIN`, `API_KEY_*`, `INTEGRATION_REVOKED`, `TENANT_*`). The new factor
   waits out the grace period like any other before the platform API accepts it.

Resetting the factor before the password lets the holder of the old password log in and enrol again.
The same reset helps an operator who lost their phone and their backup codes. An unexpected "disabled"
email also means the password is known: reset the password. An unexpected "An administrator reset
two-factor authentication" email means someone used an admin account: tell the other admins, reset
your password, then set up MFA again.

**A deployment with a single operator admin** has nobody to call the reset. Then, and only after
confirming with the account's owner by a channel other than its email, and after the password reset
of step 1, whoever operates the deployment runs auth-service once as a break-glass command:

```bash
cd docker
docker compose run --rm auth-service break-glass-mfa-reset \
  --break-glass.mfa-reset.user-email=ops@incident-platform.local \
  --break-glass.mfa-reset.actor="<your name>" \
  --break-glass.mfa-reset.reason="<why, and how the owner was confirmed>"
echo "exit code: $?"
```

It does what the endpoint does, through the same code: factor, backup codes, every session and the
personal API keys of the account removed (`personalApiKeysRevoked` in the audit metadata), the
account emailed (by the running auth-service, within a minute), and the
action audited in the operator tenant as `MFA_RESET_BREAK_GLASS`, with `break-glass:<your name>` as
the actor, the reason and where it ran (`executedOn`: the OS user and host of the process, which
you do not type) in the metadata (so no secrets in the reason). Only admins of
`platform-operator`; another operator admin resets other operator users. Only the subcommand
`break-glass-mfa-reset` as the first argument starts it: the `--break-glass.mfa-reset.*` options
alone, as arguments or environment variables, do nothing, so a variable left in a deployment by
mistake cannot turn the service into the command.

The command keeps the tenant and integration keys the account created (it has no
`revokeKeysCreatedSince`; the email counts those still active). Once logged in again, list and revoke
the ones made during the compromise with the two calls in step 2 above (backlog #0-89).

- Exit code `0`: done. `1`: refused or failed, nothing changed; the log says why: no
  `user-email` given; no operator user
  (archived ones excluded) with exactly this email (as stored, case-sensitive; spaces around it are
  trimmed); the user is not an admin or has no MFA (each a `409` in the log); actor or reason
  missing, too long, or containing control characters, Unicode line separators or formatting
  characters; or the audit event could not be written: it goes to auth-service's audit outbox
  (`auth_audit_outbox`) in the reset's transaction, so the reset is rolled back rather than done
  unaudited (backlog #0-84). After the reset the command sends it to Kafka itself when Kafka is
  reachable (it logs whether it did); otherwise the running auth-service's relay sends it, and
  `AuditOutboxBacklog` alerts if it waits over 10 minutes. The exit code does not depend on Kafka. That one event covers the reset and the count of personal API keys it
  revoked (`personalApiKeysRevoked`); the command audits nothing else. `2`: a safeguard that should never show, the command found a web server running.
- The one-off process runs no scheduled jobs and serves no requests (the subcommand starts it
  without a web server). It needs the database; Kafka only to send its audit event at once (one
  attempt of at most a few seconds, then the event is left for the running service).
- Never put the subcommand into the args of the auth-service Deployment: its pods would run the
  command, exit and restart in a loop instead of serving (each later run refused with `409`, the
  factor being gone already). It belongs only in a one-off run.
- In Kubernetes, run the same image with the subcommand and options as the container's `args` of
  a one-off Job with the auth-service
  Deployment's environment and secrets (not tested here: no cluster). Do not `kubectl exec` a
  second JVM into a running pod: it shares the pod's memory limit.

Who can run it is whoever can run the service with its credentials, the same trust as database
access; that it is audited is what it adds over editing the database. With a second operator admin,
use the endpoint instead; backlog #0-87 records stronger options for operator enrolment.

**The 403 says the factor is not accepted yet.** Either the grace period since the notice is still
running, or the notice was never sent (SMTP failing past its deadline, 24 h or the grace period if
longer, raises
`AuthEmailPermanentlyFailed`). If the email never arrived, disable and enable MFA again to send a new
one. Factors enabled before this check existed were taken over as established (V23), so their owners
need do nothing.

## Create a tenant

```bash
curl -s -X POST http://localhost:8087/api/v1/platform/tenants \
  -H "Authorization: Bearer $OP_TOKEN" -H "Content-Type: application/json" \
  -d '{"tenantId": "acme", "displayName": "Acme Corp", "adminEmail": "admin@acme.example"}'
```

- `201` with the tenant and the admin's user id; the invite email goes out within seconds (Mailpit
  locally, http://localhost:8025).
- `tenantId`: 3-63 lowercase letters, digits and hyphens, starting and ending with a letter or digit.
  It ends up in tokens, headers, logs and every service's rows, and cannot be changed later.
  `platform-operator` and `system` are reserved (`400`).
- `409` if a tenant with that id exists (also one that existed before V21: those were backfilled
  from their users).
- **Check the id is unused first.** auth-service knows only tenants that have, or had, a user. An id
  that only other services hold rows for (incidents or on-call data written under it without a
  user, e.g. through the dev profile's `/dev/token`, whose default tenant is `test-tenant` and which
  the README's end-to-end test uses) is accepted, and the new admin then sees that data. An id whose
  users all were archived is refused (`409`). On a database with any such history, look for the id in the other services' tables before
  creating it (backlog #0-85).

The new admin accepts the invite (`POST /api/v1/auth/accept-invite` with the token and a password)
and logs in with `X-Tenant-Id: acme`. From there they invite their own users.

## The invite was lost or expired

An invite is valid for 7 days, and its email is retried until its deadline. If the email
permanently failed or the token expired before the admin accepted:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  http://localhost:8087/api/v1/platform/tenants/acme/admin-invite \
  -H "Authorization: Bearer $OP_TOKEN"
```

- `202`: a new invite was queued; the old link stops working.
- `409` with one of:
  - *already has an active admin*: the tenant's admins invite and re-invite users themselves
    (`POST /api/v1/users/{id}/resend-invite`).
  - *still being sent or still valid*: the admin has a working invite; ask them to check their mail
    (and spam folder). Nothing is resent, so a slow mailbox cannot be flooded.
  - *not provisioned through this API*: a tenant backfilled by V21 has no recorded first admin. See
    [Tenants that existed before V21](#tenants-that-existed-before-v21).
  - *archived or removed*: the first admin's account was archived or anonymized. The reissue never
    creates a user, so it does not invite anyone back into a tenant that was wound down; reopening one
    needs a tenant status (backlog #0-82).
  - *users need a person to fix them*: see below.
  - *reserved by the platform*: `platform-operator` and `system` are not managed through this API;
    the operator tenant invites its own admin (`OPERATOR_ADMIN_EMAIL`, README "Step 5").
- `404`: no such tenant.

## When the tenant's users need a person

The reissue never creates a user and never removes one. When the tenant has no admin who can log in,
it refuses (`409`) in two cases, and nobody in the tenant can fix either through the API:

- **The first admin exists but cannot log in as an active admin**: deactivated or without
  `ROLE_ADMIN`, whether or not they had accepted (an ERROR in auth-service's log names the tenant).
- **The first admin is gone**: archived or anonymized (`409` "archived or removed").

Look at the tenant's users as the application role (never as the database admin:
[database-roles.md](database-roles.md), "Working as the admin"):

```sql
-- psql as incident_app
SELECT id, email, active, password_hash IS NOT NULL AS accepted, archived_at, anonymized_at
FROM users WHERE tenant_id = 'acme';
SELECT user_id, role FROM user_roles WHERE tenant_id = 'acme';
```

Then decide with the customer:

- **Deactivated or demoted first admin**: restore what is missing, then reissue if they never
  accepted (once they have a password they can log in and need no invite). Only after the customer
  has confirmed who should hold the account:

  ```sql
  -- psql as incident_app
  UPDATE users SET active = TRUE, updated_at = now(), version = version + 1
  WHERE tenant_id = 'acme' AND email = '<first admin address>';
  INSERT INTO user_roles (id, user_id, tenant_id, role)
  SELECT gen_random_uuid(), id, tenant_id, 'ROLE_ADMIN' FROM users
  WHERE tenant_id = 'acme' AND email = '<first admin address>'
    AND NOT EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = users.id AND r.role = 'ROLE_ADMIN');
  ```

- **First admin archived or anonymized**: the tenant was wound down on purpose, by its admins or
  by an erasure request. The API does not reopen it; reopening a tenant needs a tenant status
  (backlog #0-82). Until then, provision the customer under a new id.

Record every such change and its reason in your ticketing system: a database edit publishes no audit
event.

## A typo in the admin's address

The tenant id is taken once created, and the reissue always uses the recorded address, so a mistyped
`adminEmail` is not fixed through the API. If the invite was never accepted (it cannot be, at a wrong
address), fix both records as the application role and then reissue:

```sql
-- psql as incident_app, in one transaction
BEGIN;
UPDATE users   SET email = '<correct address>', updated_at = now(), version = version + 1
WHERE tenant_id = 'acme' AND email = '<typo>' AND password_hash IS NULL;
UPDATE tenants SET first_admin_email = '<correct address>' WHERE tenant_id = 'acme';
COMMIT;
```

Check both updates report one row. Record the change in your ticketing system (a database edit
publishes no audit event); the operator-tenant audit event of the provisioning keeps the old address.

## Tenants that existed before V21

V21 recorded every tenant that already had users, with its id as the display name and no first
admin. For such a tenant the platform API lists it, but cannot reissue an invite (`409`): it never
sent one. If one of them has no admin who can log in, handle it as in the previous section.

The old seeded account `admin@incidentplatform.com` / `changeme` in tenant `default` is archived by
V20 wherever its password was never changed (its tokens and API keys too), so `default` may be such
a tenant. V20 looks for the account under `ADMIN_EMAIL` / `ADMIN_TENANT_ID` as set when it runs; if
`V1_1` once ran with other values, V20 logs a WARN and the account must be checked by hand. To list
every active admin who has a password, so you can confirm with each tenant that the account is
wanted (the password itself cannot be checked in SQL):

```sql
-- psql as incident_app
SELECT u.tenant_id, u.email, u.created_at FROM users u
JOIN user_roles r ON r.user_id = u.id AND r.role = 'ROLE_ADMIN'
WHERE u.active AND u.archived_at IS NULL AND u.password_hash IS NOT NULL
ORDER BY u.created_at;
```

An access token issued to the seeded account before V20 ran stays valid until it expires (up to
15 minutes); refresh tokens and API keys stop working at once. For `default` itself, either provision the customer under a new id, or
give `default` an admin by hand as above.

## List tenants

```bash
curl -s "http://localhost:8087/api/v1/platform/tenants?page=0&size=20" \
  -H "Authorization: Bearer $OP_TOKEN" | jq
```

One tenant: `GET /api/v1/platform/tenants/{tenantId}` (the `Location` of a create). Lists are newest
first, at most 100 per page. `adminActive` is true once the tenant has an active admin who has
set a password; a tenant that stays `false` for days needs a reissued invite or a call to the
customer. On a new database `platform-operator` appears once its bootstrap has run (about 30 s after
auth-service starts, with `OPERATOR_ADMIN_EMAIL` set).

## `429` and `503` on a write

- `429` + `Retry-After`: you, or all operators together, used the hour's budget of writes (each
  attempt counts, also one that then fails, e.g. with `409`). The auth-service WARN line says which
  limit refused, and so does the `limit` tag (`operator` / `global`) of
  `platform_ratelimit_rejected_total`. Wait, or raise `PLATFORM_RATE_LIMIT_OPERATIONS_PER_HOUR` (and
  `PLATFORM_RATE_LIMIT_GLOBAL_OPERATIONS_PER_HOUR`, if needed) for a planned bulk onboarding. A
  bucket keeps the limit it was created with until its Redis key expires (5 minutes after it would
  be full again), so a raised limit applies at once only to an operator who has not written for
  about an hour; otherwise delete the `ratelimit:platform:*` keys in Redis after the restart. Each
  `429` also raises the critical `PlatformApiRateLimited` alert.
- `503` + `Retry-After`: auth-service cannot reach Redis to check the limit, so it refuses rather
  than runs without one. Reads still work. Fix Redis; `PlatformApiRateLimitUnavailable` tracks it.

## A customer tenant's only admin is locked out of MFA

Inside a customer tenant an admin resets another user's MFA (`POST /api/v1/users/{id}/mfa-reset`). A
tenant with a single admin has nobody to do that for the admin: a lost phone together with lost backup
codes, or a factor someone else enrolled with the admin's password (the "MFA enabled" email warns
them), locks the tenant's administration out. Then, and only then, an operator asks the platform to
recover the account (backlog #0-90). It is the one thing the platform does inside a tenant that has an
admin, so it is slow on purpose.

**1. Verify the person outside the account.** The account's password and mailbox are what may have
been taken, so neither proves anything. Use a channel known from before the request: a video call
against an identity document or a face you know, a call back to a phone number from the contract or an
earlier ticket (never one given in the request), a DNS TXT record you chose on the customer's own
domain, a signed letter from the organisation. Write down what you checked: who, when, which number or
record. The platform stores this; it does not check it (backlog #0-98).

**2. Ask for the recovery.** Same rules as every platform call (operator admin, recent MFA login,
factor older than 24 h, the write limits):

```bash
curl -s -X POST "http://localhost:8087/api/v1/platform/tenants/acme/mfa-recovery" \
  -H "Authorization: Bearer $OP_TOKEN" -H "Content-Type: application/json" \
  -d '{"userId":"<admin user id>","verificationMethod":"KNOWN_PHONE_CALLBACK",
       "verificationNote":"Called J. Doe on the number in contract #123, 2026-10-04 10:15 UTC"}'
```

`verificationMethod` is one of `VIDEO_CALL`, `KNOWN_PHONE_CALLBACK`, `DNS_TXT_RECORD`,
`SIGNED_DOCUMENT`, `OTHER`; the note is one line of at most 500 characters. Refused with `409` when
the user is not an active admin with MFA and an accepted invite, when the tenant has another active
admin (who resets the factor instead), or when a request for the user is already open; `400` for a
reserved tenant (an operator admin has break-glass, above). The answer (`202`) is the request.

**3. Wait.** Nothing has changed yet. The account is emailed "Account recovery requested" with a link
that cancels it; once that email has been sent, the reset runs no earlier than 72 h later
(`platform.mfa-recovery.waiting-period`, env `PLATFORM_MFA_RECOVERY_WAITING_PERIOD`, between 24 h
and 7 days), within the scheduler's interval (5 minutes, `PLATFORM_MFA_RECOVERY_SCHEDULER_INTERVAL_MS`;
`PLATFORM_MFA_RECOVERY_BATCH_SIZE` requests a run, 20). Every request alerts the operator (`PlatformMfaRecoveryRequested`, critical): with
several operators, someone else sees it. Follow it with

```bash
curl -s "http://localhost:8087/api/v1/platform/tenants/acme/mfa-recovery" -H "Authorization: Bearer $OP_TOKEN"
```

(`noticeSentAt`, `executeNotBefore`, `status`, `closeReason`). A notice that cannot be sent within the
security-notice deadline (24 h, or the MFA grace period if longer) expires the request
(`EXPIRED`, `NOTICE_NOT_DELIVERED`, alert `PlatformMfaRecoveryExpired`): a reset the account was not
told about does not happen. Fix the address or SMTP and ask again.

**4. The reset.** Before it runs, the checks are made again: if the tenant has gained another active
admin, the user is no longer an admin with MFA, or the operator who asked is no longer an active
operator admin, the request is cancelled (`OTHER_ADMIN_EXISTS`, `NO_LONGER_APPLICABLE`,
`OPERATOR_NO_LONGER_ADMIN`) and the operator alerted (`PlatformMfaRecoveryCancelledOnRecheck`,
critical): a second admin that appeared during the wait may be the attacker's, so ask the customer,
through the channel of step 1, whether they know it. A request the scheduler fails on stays open and
is retried every run (`PlatformMfaRecoveryJobFailing`; the ERROR log "MFA recovery: could not" names
it). Otherwise the factor, backup codes, every session, the personal API keys and
the password go (the password is replaced with one nobody knows, as whoever enrolled a stranger's
factor may still know the old one). The account gets "Your account was recovered: set a new password"
with a 15-minute reset link (afterwards "Forgot password"), logs in with the new password and sets up
MFA again. Tenant and integration keys stay; the admin can review them (`GET /api/v1/api-keys?createdBy=`).

**Cancelling.** The account's link cancels it (single use, `POST /api/v1/auth/mfa-recovery/cancel`),
and so can any operator:

```bash
curl -s -X POST "http://localhost:8087/api/v1/platform/mfa-recovery/<request id>/cancel" \
  -H "Authorization: Bearer $OP_TOKEN"
```

A cancellation by the account alerts the operator (`PlatformMfaRecoveryCancelledByAccount`,
critical): either its owner did not ask for it, and the verification of step 1 was fooled or the
operator account misused, or the owner got back another way. Find out which before asking again.

**What the platform cannot decide for you.** Whoever holds the account's mailbox can cancel every
request, and whoever holds the admin's session can add a second admin, after which the platform keeps
out (`OTHER_ADMIN_EXISTS`). That is the attacker of the case this exists for (a stranger's factor
enrolled with a stolen password, the mailbox perhaps too). Both page the operator. From there it is a
person's decision, through the channel of step 1: with the customer confirmed, the second admin
(if the customer's own) resets the factor (`POST /api/v1/users/{id}/mfa-reset`); a second admin the
customer does not know, or a mailbox that keeps cancelling, means the account is taken over, which
the platform API cannot settle today (suspending a tenant is backlog #0-82).

**An operator account found compromised.** Deactivating it (or removing its admin role) is enough
for its open requests: each is cancelled when its time comes (`OPERATOR_NO_LONGER_ADMIN`). Cancel
them at once anyway, so their accounts stop waiting: the list is per tenant, so first find the
tenants from that operator's `MFA_RECOVERY_REQUESTED` events in the operator tenant (their metadata
names `tenantId`), list each one's requests with the `GET` above, and cancel those still `PENDING`.

Each step is audited in both tenants (`MFA_RECOVERY_REQUESTED`, `_CANCELLED`, `_EXECUTED`,
`_EXPIRED`); the operator's note is only in the operator tenant's event. Afterwards, invite a second
admin to the tenant: tenant settings (`GET /api/v1/tenants/settings`) show `singleAdmin: true` until
there is one.

## Not here yet

Suspending a tenant (refusing its logins, API keys and tokens) and offboarding it (exporting and
deleting its data in all seven services) are backlog #0-82.
