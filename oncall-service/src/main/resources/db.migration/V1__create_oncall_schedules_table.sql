CREATE TABLE oncall_schedules
(
    id           UUID         NOT NULL,
    tenant_id    VARCHAR(255) NOT NULL,

    user_id      VARCHAR(255) NOT NULL,
    user_name    VARCHAR(255) NOT NULL,
    email        VARCHAR(500) NOT NULL,
    phone        VARCHAR(50),
    slack_user_id  VARCHAR(50),
    role           VARCHAR(20)  NOT NULL DEFAULT 'PRIMARY'
    CONSTRAINT chk_oncall_role CHECK (role IN ('PRIMARY', 'SECONDARY','MANAGER')),

    starts_at    TIMESTAMPTZ  NOT NULL,
    ends_at      TIMESTAMPTZ  NOT NULL,

    notes        TEXT,

    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_oncall_schedules PRIMARY KEY (id),

    CONSTRAINT chk_oncall_schedule_dates
        CHECK (ends_at > starts_at)
);

CREATE INDEX idx_oncall_schedules_tenant_time
    ON oncall_schedules (tenant_id, starts_at, ends_at);

-- Indeks dla zapytań per user
CREATE INDEX idx_oncall_schedules_user
    ON oncall_schedules (tenant_id, user_id);

COMMENT ON TABLE oncall_schedules IS 'Harmonogram dyżurów on-call per tenant';
COMMENT ON COLUMN oncall_schedules.phone IS 'Numer telefonu do SMS — opcjonalny';