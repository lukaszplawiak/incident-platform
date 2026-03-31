CREATE TABLE incident_history
(
    id          UUID        NOT NULL,

    incident_id UUID        NOT NULL,

    tenant_id   VARCHAR(255) NOT NULL,

    from_status VARCHAR(50)
        CONSTRAINT chk_history_from_status
            CHECK (from_status IS NULL OR
                   from_status IN ('OPEN', 'ACKNOWLEDGED', 'ESCALATED', 'RESOLVED', 'CLOSED')),

    to_status   VARCHAR(50) NOT NULL
        CONSTRAINT chk_history_to_status
            CHECK (to_status IN ('OPEN', 'ACKNOWLEDGED', 'ESCALATED', 'RESOLVED', 'CLOSED')),

    changed_by  UUID,

    change_source VARCHAR(50) NOT NULL,

    comment     TEXT,

    changed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_incident_history PRIMARY KEY (id)
);

CREATE INDEX idx_incident_history_incident_id
    ON incident_history (incident_id);

CREATE INDEX idx_incident_history_tenant_id
    ON incident_history (tenant_id);

CREATE INDEX idx_incident_history_changed_at
    ON incident_history (changed_at DESC);

COMMENT ON TABLE incident_history IS 'Audit log zmian statusu incydentów — append-only, nigdy nie modyfikuj rekordów';
COMMENT ON COLUMN incident_history.changed_by IS 'UUID użytkownika który zmienił status. NULL dla zmian automatycznych (Kafka consumer, auto-resolve, eskalacja)';
COMMENT ON COLUMN incident_history.change_source IS 'Źródło zmiany: REST_API | KAFKA_CONSUMER | AUTO_RESOLVE | ESCALATION';