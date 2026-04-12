CREATE TABLE escalation_tasks
(
    id                     UUID         NOT NULL,

    incident_id            UUID         NOT NULL,
    tenant_id              VARCHAR(255) NOT NULL,

    incident_opened_at     TIMESTAMPTZ  NOT NULL,

    scheduled_escalation_at TIMESTAMPTZ NOT NULL,

    status                 VARCHAR(50)  NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_escalation_status
            CHECK (status IN ('PENDING', 'ESCALATED', 'CANCELLED')),

    severity               VARCHAR(50)  NOT NULL,

    title                  VARCHAR(500) NOT NULL,

    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_escalation_tasks PRIMARY KEY (id),

    CONSTRAINT uq_escalation_tasks_incident_id
        UNIQUE (incident_id)
);

CREATE INDEX idx_escalation_tasks_pending
    ON escalation_tasks (scheduled_escalation_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_escalation_tasks_tenant_id
    ON escalation_tasks (tenant_id);

COMMENT ON TABLE escalation_tasks IS 'Zadania eskalacji dla incydentów bez ACK';
COMMENT ON COLUMN escalation_tasks.status IS 'PENDING=czeka, ESCALATED=eskalowano, CANCELLED=anulowano (ACK)';