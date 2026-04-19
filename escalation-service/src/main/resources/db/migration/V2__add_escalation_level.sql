ALTER TABLE escalation_tasks
    ADD COLUMN escalation_level INT NOT NULL DEFAULT 1
        CONSTRAINT chk_escalation_level CHECK (escalation_level IN (1, 2));

ALTER TABLE escalation_tasks
DROP CONSTRAINT uq_escalation_tasks_incident_id;

ALTER TABLE escalation_tasks
    ADD CONSTRAINT uq_escalation_tasks_incident_level
        UNIQUE (incident_id, escalation_level);

ALTER TABLE escalation_tasks
DROP CONSTRAINT chk_escalation_status;

ALTER TABLE escalation_tasks
    ADD CONSTRAINT chk_escalation_status
        CHECK (status IN ('PENDING', 'ESCALATED', 'CANCELLED'));

COMMENT ON COLUMN escalation_tasks.escalation_level
    IS '1=SECONDARY powiadamiany, 2=MANAGER powiadamiany';