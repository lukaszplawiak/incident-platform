-- Operator-assisted MFA recovery of a customer tenant's only admin (backlog #0-90).
--
-- Since #0-88 a password reset never removes a second factor and another admin
-- of the tenant resets it instead. A customer tenant with one admin has nobody
-- to do that: a factor enrolled with a stolen password, or a lost phone and
-- lost backup codes, locked that admin out for good. A platform operator may
-- now ask for the reset, a narrow exception to #0-80's "the platform never acts
-- inside a tenant that has an admin": only for an admin with MFA who is the
-- tenant's only active admin, after verifying the person outside the account's
-- own channels (verification_method / verification_note), and never at once.
-- When its time comes the checks run again, the requesting operator included:
-- one no longer an active operator admin cancels it (OPERATOR_NO_LONGER_ADMIN).
-- The account is emailed (MFA_RECOVERY_REQUESTED, with a link that cancels the
-- request) and the reset happens only once the waiting period has passed since
-- that email was actually sent (notice_sent_at, as mfa_enabled_notice_sent_at
-- in #0-83): a request whose notice never went out never executes and expires.
--
-- Every change of status is a conditional UPDATE ... WHERE status = 'PENDING'
-- with its row count checked, so a cancellation and the scheduler's execution
-- cannot both win. At most one PENDING request per user (partial unique index).
-- requested_by / closed_by are operator user ids, no foreign key, as
-- tenants.created_by (V21). user_id keeps its foreign key: users are archived
-- or anonymized, never deleted.

CREATE TABLE mfa_recovery_requests (
    id                   UUID         NOT NULL,
    tenant_id            VARCHAR(255) NOT NULL,
    user_id              UUID         NOT NULL REFERENCES users (id),
    requested_by         UUID         NOT NULL,
    verification_method  VARCHAR(30)  NOT NULL,
    verification_note    VARCHAR(500) NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    notice_sent_at       TIMESTAMPTZ,
    closed_at            TIMESTAMPTZ,
    close_reason         VARCHAR(40),
    closed_by            UUID,
    CONSTRAINT pk_mfa_recovery_requests PRIMARY KEY (id),
    CONSTRAINT chk_mfa_recovery_requests_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'),
    CONSTRAINT chk_mfa_recovery_requests_method
        CHECK (verification_method IN ('VIDEO_CALL', 'KNOWN_PHONE_CALLBACK', 'DNS_TXT_RECORD',
                                       'SIGNED_DOCUMENT', 'OTHER')),
    CONSTRAINT chk_mfa_recovery_requests_status
        CHECK (status IN ('PENDING', 'EXECUTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_mfa_recovery_requests_close_reason
        CHECK (close_reason IN ('CANCELLED_BY_ACCOUNT', 'CANCELLED_BY_OPERATOR', 'OTHER_ADMIN_EXISTS',
                                'NO_LONGER_APPLICABLE', 'OPERATOR_NO_LONGER_ADMIN', 'NOTICE_NOT_DELIVERED')),
    CONSTRAINT chk_mfa_recovery_requests_closed
        CHECK ((status = 'PENDING') = (closed_at IS NULL)),
    -- A request that ended without a reset says why; an executed one has no reason.
    CONSTRAINT chk_mfa_recovery_requests_close_reason_set
        CHECK (status NOT IN ('CANCELLED', 'EXPIRED') OR close_reason IS NOT NULL),
    CONSTRAINT chk_mfa_recovery_requests_executed_no_reason
        CHECK (status <> 'EXECUTED' OR close_reason IS NULL)
);

CREATE UNIQUE INDEX uq_mfa_recovery_requests_pending_user
    ON mfa_recovery_requests (user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_mfa_recovery_requests_pending
    ON mfa_recovery_requests (notice_sent_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_mfa_recovery_requests_tenant
    ON mfa_recovery_requests (tenant_id, created_at DESC);

COMMENT ON TABLE mfa_recovery_requests
    IS 'An operator''s request to reset the MFA of a customer tenant''s only admin (backlog #0-90); '
       'executed by the scheduler once the waiting period has passed since notice_sent_at, unless '
       'cancelled by the account (emailed link) or an operator.';

-- The notice of a request and the email that ends it. MFA_RECOVERY_REQUESTED
-- carries the cancel token (MFA_RECOVERY_CANCEL below) and names its request,
-- so the scheduler records notice_sent_at on the right one; never superseded.
-- MFA_RECOVERY_COMPLETED carries a password-reset token: the execution also
-- replaces the password. The type constraint is widened as in V23-V25; the
-- rolling-deploy note of V25 applies (an old replica fails a run that loads a
-- new type, the row stays PENDING until a new replica sends it).

ALTER TABLE auth_email_outbox
    ADD COLUMN mfa_recovery_request_id UUID REFERENCES mfa_recovery_requests (id);

CREATE INDEX idx_auth_email_outbox_mfa_recovery_request
    ON auth_email_outbox (mfa_recovery_request_id)
    WHERE mfa_recovery_request_id IS NOT NULL;

ALTER TABLE auth_email_outbox DROP CONSTRAINT chk_auth_email_outbox_type;
ALTER TABLE auth_email_outbox ADD CONSTRAINT chk_auth_email_outbox_type
    CHECK (email_type IN ('INVITE', 'PASSWORD_RESET', 'MFA_ENABLED', 'MFA_DISABLED', 'MFA_RESET',
                          'API_KEY_CREATED', 'MFA_RECOVERY_REQUESTED', 'MFA_RECOVERY_COMPLETED'));

COMMENT ON TABLE auth_email_outbox
    IS 'Intent to send an auth email: invite, password reset, MFA_RECOVERY_REQUESTED and '
       'MFA_RECOVERY_COMPLETED carry a token, created when the email is sent and stored only as a '
       'hash in auth_tokens (backlog #0-52, #0-90); MFA_ENABLED / MFA_DISABLED (backlog #0-83), '
       'MFA_RESET (backlog #0-88) and API_KEY_CREATED (backlog #0-89) notices carry none.';

COMMENT ON COLUMN auth_email_outbox.mfa_recovery_request_id
    IS 'The request an MFA_RECOVERY_REQUESTED notice announces (backlog #0-90); NULL for every other type.';

ALTER TABLE auth_tokens DROP CONSTRAINT chk_auth_token_type;
ALTER TABLE auth_tokens ADD CONSTRAINT chk_auth_token_type
    CHECK (type IN ('INVITE', 'PASSWORD_RESET', 'REFRESH',
                    'MFA_SESSION', 'MFA_SETUP_REQUIRED', 'MFA_RECOVERY_CANCEL'));

COMMENT ON COLUMN auth_tokens.type
    IS 'INVITE: new user onboarding. PASSWORD_RESET: forgot password flow. '
       'REFRESH: session continuity - rotated on each use, 30-day TTL. '
       'MFA_SESSION: second-factor step after a password login, 5-minute TTL. '
       'MFA_SETUP_REQUIRED: MFA setup when the tenant requires it, 10-minute TTL. '
       'MFA_RECOVERY_CANCEL: cancels an operator''s MFA recovery request, lives for its waiting period (backlog #0-90). '
       'Must match AuthToken.Type (checked by AuthRepositoryIntegrationTest).';
