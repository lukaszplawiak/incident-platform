package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Per-tenant configuration managed by ADMIN users.
 *
 * <h2>MFA enforcement</h2>
 * When {@link #mfaRequired} is true, users without MFA configured are blocked
 * at login with {@code MFA_SETUP_REQUIRED} error. They must set up TOTP via
 * {@code POST /auth/mfa/setup} before accessing the system.
 *
 * <p>This mirrors the PagerDuty model: account-level MFA policy set by
 * the account owner, enforced at login for all users in the account.
 */
@Entity
@Table(name = "tenant_settings")
public class TenantSettings {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "mfa_required", nullable = false)
    private boolean mfaRequired = false;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected TenantSettings() {}

    public static TenantSettings defaults(String tenantId) {
        final TenantSettings settings = new TenantSettings();
        settings.tenantId = tenantId;
        return settings;
    }

    public void setMfaRequired(boolean mfaRequired) {
        this.mfaRequired   = mfaRequired;
        this.updatedAt     = Instant.now();
    }

    public String getTenantId()    { return tenantId; }
    public boolean isMfaRequired() { return mfaRequired; }
    public Instant getUpdatedAt()  { return updatedAt; }
}