# Tenant provisioning

How a customer tenant comes into existence, and how its first admin gets in. Background: backlog
#0-80 (fixed); suspending and offboarding a tenant is backlog #0-82 (open).

## The model

- A tenant is a row in auth-service's `tenants` table (V21): id, display name, the address its first
  admin invite went to, when and by which operator it was created. The other services keep treating
  `tenant_id` as a plain string on their own rows.
- Only a **platform operator** creates one: an admin of the reserved `platform-operator` tenant,
  logged in with a JWT. The platform API, `/api/v1/platform/tenants`, refuses everyone else with
  `403`: admins of customer tenants, other roles of the operator tenant, API keys (also an operator
  admin's own personal key), service tokens and purpose tokens. The rule is enforced in auth-service's
  filter chain and again on every controller method (`PlatformAccess`). In Kubernetes the Ingress
  routes `/api/v1/platform` like every other auth-service path, so the API is reachable from
  wherever the Ingress is; that rule is its only protection.
- The tenant's **first admin is invited**, never given a password. Creating the tenant writes the
  tenant row, the admin user (no password, `ROLE_ADMIN`) and the invite email, in one transaction:
  if any of it fails, nothing is created. The admin sets their password by accepting the invite, as
  any invited user does. No password is in a migration, in configuration or in the API.
- Once the tenant has an admin who has accepted, the operator has nothing more to do in it, and
  cannot act in it: the platform API creates tenants, reissues the first invite while nobody in
  the tenant can log in as an admin, and shows and lists tenants' metadata (never their data). Everything else
  is the tenant admin's (users, teams, integrations, Slack, MFA policy).
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

**Protect the operator accounts accordingly.** Turn on MFA for the operator tenant
(`POST /api/v1/tenants/settings` with `{"mfaRequired": true}`, as its admin), and keep the number of
operator admins small. The platform API does not check this yet: backlog #0-83.

## Prerequisite: an operator admin

The operator tenant bootstraps itself: with `OPERATOR_ADMIN_EMAIL` set
(`platform.operator.bootstrap.admin-email`), auth-service invites that address as the operator
tenant's first admin (`OperatorTenantBootstrap`, backlog #0-16, #0-49). Accept the invite and log
in as README "Step 5" shows (steps 2 and 3). Every command below uses that login:

```bash
OP_TOKEN=$(curl -s -X POST http://localhost:8087/api/v1/auth/login \
  -H "X-Tenant-Id: platform-operator" -H "Content-Type: application/json" \
  -d '{"email": "ops@incident-platform.local", "password": "<password>"}' | jq -r .accessToken)
```

(The access token lives 15 minutes. With MFA on, the login returns an `mfaToken` instead of an
access token; exchange it with `POST /api/v1/auth/mfa/verify`.)

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

## Not here yet

Suspending a tenant (refusing its logins, API keys and tokens) and offboarding it (exporting and
deleting its data in all seven services) are backlog #0-82.
