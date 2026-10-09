# ADR-0012: API keys in auth-service: deny by default (#0-89)

- **Status:** Accepted
- **Backlog:** #0-89
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

A key gets its owner's roles
(`ApiKeyLookupServiceImpl`), so without a scope rule an admin's personal key could create footholds no reset
takes back (invite an admin, tenant keys, integrations). Rules: a key reaches only routes `SecurityConfig`
lists with `ApiKeyAccess.scopeOrElse` (today GET/HEAD `/api/v1/teams…` with `teams:read`, other team routes
with `teams:write`), and the method's role check still applies (scope AND role); `anyRequest` refuses keys.
`ApiKeyDenyByDefaultTest` walks every handler mapping with an all-scope admin key, so a new route is closed
to keys until listed. Recovery (password reset, admin/break-glass MFA reset) and archive revoke personal keys
through `ApiKeyService.revokeAllPersonalKeysForUser`: a bulk update that clears the persistence context, so
callers run it last; the count goes into the action's own audit event (`personalApiKeysRevoked`). A password
change revokes only with `revokePersonalApiKeys: true` (ASVS 3.3.3: an option, not forced). Creating a key
(`ApiKeyService`, `IntegrationService`) queues `API_KEY_CREATED` (V25) to the owner or creating admin. No
step-up to create a key: little gain once a key cannot create anything.
- Tenant/integration keys survive their creator's reset on purpose (integrations must not stop). For a
  taken-over admin: `api_keys.created_by_user_id` / `created_in_session_id` (V26, set via
  `ApiKey.recordCreator`; a personal key's creator is its owner), `GET /api-keys?createdBy=`,
  `POST /api-keys/revoke-created-by {userId, since}` (`ApiKeyService.revokeKeysCreatedBy`: loads the keys,
  revokes an integration with its key, one `API_KEY_REVOKED` event), and `revokeKeysCreatedSince` on the admin
  MFA reset. Same rights as `DELETE /api-keys/{id}` (ADMIN from a login, no step-up, no limit; user's choice:
  an admin can already revoke one by one, and tenants without MFA must be able to clean up). The MFA_RESET
  email counts the account's still-active unowned keys, at send time (`AuthEmailScheduler`); an archive
  records the same count (`unownedApiKeysKept`); both count only keys with a recorded creator (V26 on), so
  "0" says nothing about older tenant keys. `API_KEY_CREATED` is one email per key, never merged and never
  superseded (`AuthEmailType.supersededByNewer`; the outbox row names the key, `api_key_id` in V25, and the
  email shows its id, not its prefix, which is part of the secret): a merged notice would let a key made
  right after another go unannounced. The number of emails is bounded by `ApiKeyCreationLimit` instead:
  20 keys per user per hour, revoked and integration keys included, counted in Postgres on V26's index
  (no Redis) after the creator's row is locked (`UserRepository.findByIdAndTenantIdForUpdate`, native
  `FOR NO KEY UPDATE NOWAIT`, so foreign-key `KEY SHARE` locks of a login or reset in flight do not
  conflict; through `ApiKeyCreationLimit.lockingCreator`: a second parallel request of the same user gets
  429 at once instead of waiting on a pooled connection; the active-key caps rely on the lock too). The
  creation and revocation audit events are written to `auth_audit_outbox` in the same transaction (#0-84;
  they first went after commit on a bounded executor, `AfterCommit`, removed with the outbox); a bulk
  revoke that revoked nothing publishes nothing. 429 + Retry-After
  (`RateLimitResponses`). `revokeCreatedBy` audits each integration as `INTEGRATION_REVOKED`, so the endpoint
  and the MFA reset (which also lists `keyIds` / `integrationIds`) leave the same trace.
