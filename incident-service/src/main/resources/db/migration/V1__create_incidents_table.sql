CREATE TABLE incidents
(
    id                UUID        NOT NULL,

    tenant_id         VARCHAR(255) NOT NULL,

    status            VARCHAR(50) NOT NULL
        CONSTRAINT chk_incident_status
            CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'ESCALATED', 'RESOLVED', 'CLOSED')),

    title             VARCHAR(255) NOT NULL,
    description       TEXT,

    severity          VARCHAR(50) NOT NULL
        CONSTRAINT chk_incident_severity
            CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),

    source_type       VARCHAR(50) NOT NULL
        CONSTRAINT chk_incident_source_type
            CHECK (source_type IN ('OPS', 'SECURITY')),

    source            VARCHAR(100) NOT NULL,

    alert_fingerprint VARCHAR(500) NOT NULL,

    alert_id          UUID,

    assigned_to       UUID,

    alert_fired_at    TIMESTAMPTZ,
    acknowledged_at   TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    version           BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_incidents PRIMARY KEY (id)
);

CREATE INDEX idx_incidents_tenant_status
    ON incidents (tenant_id, status);

CREATE INDEX idx_incidents_tenant_fingerprint
    ON incidents (tenant_id, alert_fingerprint);

CREATE INDEX idx_incidents_fingerprint
    ON incidents (alert_fingerprint);

CREATE INDEX idx_incidents_created_at
    ON incidents (created_at DESC);

COMMENT ON TABLE incidents IS 'Główna tabela incydentów — każdy rekord to jeden incydent';
COMMENT ON COLUMN incidents.alert_fingerprint IS 'Stabilny identyfikator alertu: {source}:{alertname}:{instance}. Używany do deduplication i auto-resolve';
COMMENT ON COLUMN incidents.version IS 'Optimistic locking — inkrementowany przez Hibernate przy każdym UPDATE';