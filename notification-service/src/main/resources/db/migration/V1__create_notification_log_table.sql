CREATE TABLE notification_log
(
    id           UUID         NOT NULL,

    incident_id  UUID         NOT NULL,
    tenant_id    VARCHAR(255) NOT NULL,

    event_type   VARCHAR(100) NOT NULL,

    channel      VARCHAR(50)  NOT NULL
        CONSTRAINT chk_notification_channel
            CHECK (channel IN ('EMAIL', 'SLACK', 'SMS')),

    recipient    VARCHAR(500) NOT NULL,

    subject      VARCHAR(500),
    message      TEXT,

    status       VARCHAR(50)  NOT NULL
        CONSTRAINT chk_notification_status
            CHECK (status IN ('SENT', 'FAILED', 'SKIPPED')),

    error_message TEXT,

    sent_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notification_log PRIMARY KEY (id)
);

CREATE INDEX idx_notification_log_incident_id
    ON notification_log (incident_id);

CREATE INDEX idx_notification_log_tenant_id
    ON notification_log (tenant_id);

CREATE INDEX idx_notification_log_sent_at
    ON notification_log (sent_at DESC);

COMMENT ON TABLE notification_log IS 'Historia wysłanych powiadomień — append-only';
COMMENT ON COLUMN notification_log.status IS 'SENT=wysłano, FAILED=błąd, SKIPPED=celowo pominięto';