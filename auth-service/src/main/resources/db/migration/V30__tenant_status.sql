-- A tenant's life-cycle status (backlog #0-82).
--
-- V21 created tenants without a status on purpose and left it to #0-82: a
-- platform operator could create a tenant (#0-80) but nothing ended one. A
-- tenant is now ACTIVE, SUSPENDED (reversible), OFFBOARDING (its data being
-- exported and removed) or OFFBOARDED (a tombstone: the id stays taken, the
-- data is gone). This first step uses ACTIVE and SUSPENDED; offboarding is its
-- own backlog item and the two states are reserved for it.
--
-- A suspension has a mode and a reason:
--   FULL       nothing works for the tenant's users and keys (a taken-over
--              tenant, a terms breach);
--   READ_ONLY  reads go on, writes are refused except account security (a
--              billing hold).
-- Reasons: SECURITY, BILLING, TERMS, OTHER; the operator's note says the rest.
-- Every change of status is a conditional UPDATE guarded by the current status
-- (TenantRepository), the pattern of the auth email outbox and the MFA
-- recovery requests.
--
-- Existing rows become ACTIVE (the default), which is what they are. The
-- columns are added with defaults and checks in one statement each; tenants is
-- a small table (one row per customer).

ALTER TABLE tenants
    ADD COLUMN status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN suspension_mode   VARCHAR(20),
    ADD COLUMN suspension_reason VARCHAR(20),
    ADD COLUMN suspension_note   VARCHAR(500),
    ADD COLUMN suspended_at      TIMESTAMPTZ,
    ADD COLUMN suspended_by      UUID,
    ADD COLUMN status_changed_at TIMESTAMPTZ;

ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'OFFBOARDING', 'OFFBOARDED')),
    ADD CONSTRAINT chk_tenants_suspension_mode
        CHECK (suspension_mode IN ('FULL', 'READ_ONLY')),
    ADD CONSTRAINT chk_tenants_suspension_reason
        CHECK (suspension_reason IN ('SECURITY', 'BILLING', 'TERMS', 'OTHER')),
    -- A suspended tenant says how, why, when and by whom; any other has none of it.
    ADD CONSTRAINT chk_tenants_suspension_complete
        CHECK ((status = 'SUSPENDED') = (suspension_mode IS NOT NULL
                                         AND suspension_reason IS NOT NULL
                                         AND suspension_note IS NOT NULL
                                         AND suspended_at IS NOT NULL
                                         AND suspended_by IS NOT NULL)),
    ADD CONSTRAINT chk_tenants_suspension_cleared
        CHECK (status = 'SUSPENDED' OR (suspension_mode IS NULL AND suspension_reason IS NULL
                                        AND suspension_note IS NULL AND suspended_at IS NULL
                                        AND suspended_by IS NULL)),
    -- A SECURITY suspension is FULL (TenantLifecycleService refuses READ_ONLY with
    -- it): read-only keeps the sessions of whoever took the tenant over. Here too,
    -- so no other path or hand-run fix can store the combination.
    ADD CONSTRAINT chk_tenants_security_suspension_full
        CHECK (suspension_reason IS DISTINCT FROM 'SECURITY' OR suspension_mode = 'FULL');

COMMENT ON COLUMN tenants.status
    IS 'ACTIVE, SUSPENDED (reversible), OFFBOARDING, OFFBOARDED (backlog #0-82); changed only by a '
       'platform operator, through conditional UPDATEs guarded by the current status.';
COMMENT ON COLUMN tenants.suspension_mode
    IS 'FULL (nothing works) or READ_ONLY (reads go on, writes refused but account security); '
       'set only while SUSPENDED.';
COMMENT ON COLUMN tenants.suspension_reason
    IS 'SECURITY, BILLING, TERMS or OTHER; set only while SUSPENDED. Shown in the customer tenant''s '
       'audit trail, unlike the note.';
COMMENT ON COLUMN tenants.suspension_note
    IS 'The operator''s own words on why (at most 500 characters, one line); set only while SUSPENDED, '
       'and kept afterwards only in the operator tenant''s audit trail, never in the customer''s.';
COMMENT ON COLUMN tenants.suspended_at
    IS 'When the current suspension began; kept when only its mode or reason changes, cleared on resume.';
COMMENT ON COLUMN tenants.suspended_by
    IS 'Platform operator (users.id in platform-operator) who last set the suspension; no foreign key, '
       'as tenants.created_by.';
COMMENT ON COLUMN tenants.status_changed_at
    IS 'Last suspend, change of suspension or resume; NULL for a tenant whose status never changed.';
