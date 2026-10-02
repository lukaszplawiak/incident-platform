-- postmortem-service's audit outbox (backlog #0-84, second step).
--
-- AuditEventPublisher (shared) writes each audit event here, in the same
-- transaction as the action it records when there is one, instead of sending
-- it to Kafka without waiting (the direct send this service used until now,
-- which lost an event whenever Kafka was slow or down); AuditOutboxRelay
-- (shared) sends committed rows and marks them SENT only after Kafka
-- acknowledged them. One table per service (one database for all of them):
-- this one is postmortem-service's, named by audit.outbox.table. Every service's
-- outbox table has this same shape.
--
-- One writer per row after the INSERT: the relay, with state-guarded UPDATEs
-- (CLAUDE.md). SENT rows are purged after audit.outbox.retention (7 days).

CREATE TABLE postmortem_audit_outbox (
    id              UUID         NOT NULL,
    tenant_id       VARCHAR(255) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000),
    created_at      TIMESTAMPTZ  NOT NULL,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    CONSTRAINT pk_postmortem_audit_outbox PRIMARY KEY (id),
    CONSTRAINT chk_postmortem_audit_outbox_status CHECK (status IN ('PENDING', 'SENT'))
);

-- The relay's query: due pending rows, oldest first.
CREATE INDEX idx_postmortem_audit_outbox_due
    ON postmortem_audit_outbox (next_attempt_at, created_at)
    WHERE status = 'PENDING';

-- The backlog gauge's min(created_at) over pending rows (found in review:
-- the due index leads with next_attempt_at, so a long backlog was read in
-- full on every scrape).
CREATE INDEX idx_postmortem_audit_outbox_pending_created
    ON postmortem_audit_outbox (created_at)
    WHERE status = 'PENDING';

-- The purge: sent rows by age.
CREATE INDEX idx_postmortem_audit_outbox_sent
    ON postmortem_audit_outbox (sent_at)
    WHERE status = 'SENT';

COMMENT ON TABLE postmortem_audit_outbox
    IS 'Audit events waiting for, or recently through, the relay to Kafka (backlog #0-84). '
       'id is the event id the consumer deduplicates on; tenant_id is required, as audit_events '
       'requires it (AuditEventPublisher refuses an event without one).';
