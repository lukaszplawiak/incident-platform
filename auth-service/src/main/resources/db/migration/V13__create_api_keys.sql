-- API Keys — long-lived credentials for machine-to-machine integrations.
--
-- Two key types (mirrors PagerDuty model):
--
--   TENANT:   Created by ADMIN. Not bound to any specific user — survives
--             user departure. Used for integrations (Grafana, CI/CD, webhooks).
--             owner_user_id IS NULL.
--
--   PERSONAL: Created by any authenticated user. Bound to the owner.
--             Automatically revoked when owner is archived or anonymized.
--             Cannot have broader scopes than the owner's roles.
--             owner_user_id IS NOT NULL.
--
-- Key format: ipl_<8-char-prefix>.<32-char-random>
--   prefix:   First 8 chars of the raw key. Stored in plain text for UI
--             display ("ending in abc12345") without exposing the full secret.
--   secret:   The full raw key. Stored as SHA-256 hash (key_hash). Shown
--             to the user ONCE at creation time, never again.
--
-- Why SHA-256 not Argon2 for key_hash:
--   API keys are already high-entropy (256-bit random) — brute-forcing a
--   leaked hash is computationally infeasible even with fast SHA-256.
--   Argon2's cost (memory + time) would add ~100ms latency to EVERY API
--   request that uses a key. SHA-256 is the industry standard for API key
--   hashing (GitHub, Stripe, Twilio all use it for this reason).
--
-- scopes stored as PostgreSQL TEXT ARRAY:
--   Allows efficient @> (contains) operator for scope checking.
--   Scope values defined in ApiKeyScope enum:
--     incidents:read, incidents:write, alerts:ingest,
--     postmortems:read, postmortems:write,
--     oncall:read, teams:read
--
-- revoked_at vs DELETE:
--   Soft revocation preserves audit trail. A revoked key is immediately
--   rejected by ApiKeyAuthFilter but remains visible in audit history.
--   Hard deletes would erase evidence of key existence.
--
-- last_used_at:
--   Updated on every authenticated request. Best-effort — updated
--   asynchronously to avoid adding DB write to every hot path.

CREATE TABLE api_keys
(
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id     VARCHAR(255) NOT NULL,
    key_type      VARCHAR(20)  NOT NULL,                     -- TENANT | PERSONAL
    name          VARCHAR(255) NOT NULL,                     -- human-readable label
    key_hash      VARCHAR(64)  NOT NULL,                     -- SHA-256 of raw key
    key_prefix    VARCHAR(8)   NOT NULL,                     -- first 8 chars for UI
    scopes        TEXT[]       NOT NULL DEFAULT '{}',        -- granted permissions
    owner_user_id UUID         REFERENCES users (id) ON DELETE SET NULL,
    last_used_at  TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ,                               -- NULL = non-expiring
    revoked_at    TIMESTAMPTZ,                               -- NULL = active
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_api_keys
        PRIMARY KEY (id),

    CONSTRAINT uq_api_keys_hash
        UNIQUE (key_hash),

    CONSTRAINT chk_api_key_type
        CHECK (key_type IN ('TENANT', 'PERSONAL')),

    CONSTRAINT chk_personal_key_has_owner
        CHECK (key_type = 'TENANT' OR owner_user_id IS NOT NULL)
);

-- Fast lookup on every API request
CREATE UNIQUE INDEX idx_api_keys_hash
    ON api_keys (key_hash)
    WHERE revoked_at IS NULL;

-- List keys per tenant (UI)
CREATE INDEX idx_api_keys_tenant
    ON api_keys (tenant_id)
    WHERE revoked_at IS NULL;

-- Cascade revocation when user is archived
CREATE INDEX idx_api_keys_owner
    ON api_keys (owner_user_id)
    WHERE owner_user_id IS NOT NULL AND revoked_at IS NULL;

COMMENT ON TABLE api_keys
    IS 'Long-lived API credentials for machine-to-machine integrations. '
       'Two types: TENANT (org-level, admin-created) and PERSONAL (user-bound).';
COMMENT ON COLUMN api_keys.key_hash
    IS 'SHA-256(raw_key). Raw key shown once at creation, never stored.';
COMMENT ON COLUMN api_keys.key_prefix
    IS 'First 8 chars of raw key — shown in UI for identification without '
       'revealing the secret. Example: "ipl_abc1" for "ipl_abc12345...xyz".';
COMMENT ON COLUMN api_keys.scopes
    IS 'Granted API scopes. Subset of ApiKeyScope enum values. '
       'PERSONAL keys cannot exceed owner role permissions.';