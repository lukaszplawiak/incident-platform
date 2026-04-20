CREATE TABLE audit_events
(
    id          UUID         NOT NULL,
    incident_id UUID         NOT NULL,
    tenant_id   VARCHAR(255) NOT NULL,

    event_type  VARCHAR(100) NOT NULL,

    actor       VARCHAR(255),
    actor_type  VARCHAR(50)  NOT NULL
        CONSTRAINT chk_audit_actor_type
            CHECK (actor_type IN ('USER', 'SYSTEM')),

    detail      TEXT,
    metadata    JSONB,

    source_service VARCHAR(100) NOT NULL,

    occurred_at TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_audit_events PRIMARY KEY (id)
);

CREATE INDEX idx_audit_events_incident
    ON audit_events (tenant_id, incident_id, occurred_at DESC);

CREATE INDEX idx_audit_events_tenant
    ON audit_events (tenant_id, occurred_at DESC);

CREATE INDEX idx_audit_events_type
    ON audit_events (tenant_id, event_type, occurred_at DESC);

COMMENT ON TABLE audit_events
    IS 'Central audit of system event log - collected from all services';
COMMENT ON COLUMN audit_events.actor
    IS 'userId for user actions, service name for system actions';
COMMENT ON COLUMN audit_events.metadata
    IS 'Additional event data in JSON format (e.g. notification channel, escalation level)';