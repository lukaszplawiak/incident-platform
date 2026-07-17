-- Integrations — named connections between external monitoring systems and Teams.
--
-- Design: Integration-based routing (PagerDuty model).
--
-- Each Integration represents a single monitoring system webhook:
--   "Prometheus Payment API" → Team "backend-team"
--   "Wazuh Security Scanner" → Team "security-team"
--   "Generic CI/CD Alerts"   → Team "ops-team"
--
-- How routing works:
--   1. Admin creates an Integration via POST /api/v1/integrations.
--   2. System generates an API Key (api_keys table, TENANT type, alerts:ingest scope).
--   3. Admin configures Alertmanager/Prometheus with:
--        Authorization: ApiKey ipl_<key>
--        URL: POST /api/v1/alerts/{source}
--   4. Alert arrives → ApiKeyAuthFilter → lookup api_key → integration_id → team_id.
--   5. UnifiedAlertDto.teamId = integration.teamId → Incident.team_id set.
--   6. EscalationScheduler → oncall-service: who is PRIMARY for this team now?
--
-- Relationship to api_keys:
--   Each Integration owns exactly one API Key (TENANT type, alerts:ingest scope).
--   Revoking the Integration also revokes its API Key.
--   The api_keys.integration_id FK allows ApiKeyLookupServiceImpl to fetch
--   the teamId in a single JOIN instead of two separate queries.
--
-- source:
--   Mirrors AlertNormalizer.getSourceName() — "prometheus", "wazuh", "generic".
--   Stored for auditing and future per-integration normalizer selection.
--
-- team_id FK:
--   References auth-service teams table. Nullable — an Integration without
--   a team is valid but escalation will skip on-call routing (fail loudly).

CREATE TABLE integrations
(
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(255) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    source      VARCHAR(50)  NOT NULL,      -- "prometheus" | "wazuh" | "generic"
    team_id     UUID         REFERENCES teams (id) ON DELETE SET NULL,
    api_key_id  UUID         REFERENCES api_keys (id) ON DELETE CASCADE,
    description VARCHAR(1000),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_at  TIMESTAMPTZ,               -- soft delete

    CONSTRAINT pk_integrations
        PRIMARY KEY (id),

    CONSTRAINT uq_integrations_name_tenant
        UNIQUE (tenant_id, name),

    CONSTRAINT chk_integrations_source
        CHECK (source IN ('prometheus', 'wazuh', 'generic'))
);

CREATE INDEX idx_integrations_tenant
    ON integrations (tenant_id)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_integrations_team
    ON integrations (team_id)
    WHERE team_id IS NOT NULL AND revoked_at IS NULL;

-- api_keys: add integration_id for fast JOIN in ApiKeyLookupServiceImpl
ALTER TABLE api_keys
    ADD COLUMN integration_id UUID REFERENCES integrations (id) ON DELETE SET NULL;

CREATE INDEX idx_api_keys_integration
    ON api_keys (integration_id)
    WHERE integration_id IS NOT NULL;

COMMENT ON TABLE integrations
    IS 'Named connections between external monitoring systems and Teams. '
       'Each integration owns one API Key used by the external system for auth. '
       'Alert routing: api_key → integration → team → on-call schedule.';

COMMENT ON COLUMN integrations.source
    IS 'Alert source identifier matching AlertNormalizer.getSourceName(). '
       'Determines which normalizer processes the incoming payload.';

COMMENT ON COLUMN integrations.team_id
    IS 'Team responsible for alerts from this integration. '
       'NULL = integration created without team assignment. '
       'Escalation will log warning and skip on-call routing when NULL.';

COMMENT ON COLUMN api_keys.integration_id
    IS 'Set when this API key was created for an Integration. '
       'NULL for manually-created API keys. '
       'Used by ApiKeyLookupServiceImpl to resolve teamId in a single JOIN.';