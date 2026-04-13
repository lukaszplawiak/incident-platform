CREATE TABLE postmortems
(
    id                   UUID         NOT NULL,
    incident_id          UUID         NOT NULL,
    tenant_id            VARCHAR(255) NOT NULL,
    incident_title       VARCHAR(500) NOT NULL,
    incident_severity    VARCHAR(50)  NOT NULL,
    incident_opened_at   TIMESTAMPTZ  NOT NULL,
    incident_resolved_at TIMESTAMPTZ  NOT NULL,
    duration_minutes     INTEGER      NOT NULL,
    status               VARCHAR(50)  NOT NULL DEFAULT 'GENERATING'
        CONSTRAINT chk_postmortem_status
            CHECK (status IN ('GENERATING', 'DRAFT', 'FAILED', 'REVIEWED')),
    content              TEXT,
    error_message        TEXT,
    prompt_used          TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_postmortems PRIMARY KEY (id),
    CONSTRAINT uq_postmortems_incident_id UNIQUE (incident_id)
);

CREATE INDEX idx_postmortems_tenant_id   ON postmortems (tenant_id);
CREATE INDEX idx_postmortems_incident_id ON postmortems (incident_id);
CREATE INDEX idx_postmortems_created_at  ON postmortems (created_at DESC);

COMMENT ON TABLE postmortems IS 'Drafts generates by Gemini AI';
COMMENT ON COLUMN postmortems.status IS 'GENERATING=in progress, DRAFT=ready, FAILED=failed, REVIEWED=approved';
COMMENT ON COLUMN postmortems.prompt_used IS 'Prompt send to Gemini — for debug';