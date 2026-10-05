package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant as a record of its own (backlog #0-80, table {@code tenants}, V21).
 *
 * <p>Read-only on purpose ({@link Immutable}). The id is assigned, not
 * generated, so Spring Data's {@code save()} would treat a new instance as an
 * existing row and {@code merge} it, which overwrites a tenant that already has
 * that id (the trap of backlog #0-47). Rows are inserted only by
 * {@code TenantRepository.insertIfAbsent}, a single {@code INSERT ... ON
 * CONFLICT DO NOTHING}: atomic, and a taken id comes back as 0 rows instead of
 * an overwrite.
 *
 * <p>Its life-cycle status (backlog #0-82, V30) changes only through
 * {@code TenantRepository}'s conditional UPDATEs, each guarded by the current
 * status, so two operators acting at once cannot both win.
 */
@Entity
@Immutable
@Table(name = "tenants")
public class Tenant {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "display_name", nullable = false, updatable = false)
    private String displayName;

    @Column(name = "first_admin_email", updatable = false)
    private String firstAdminEmail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, insertable = false, length = 20)
    private TenantStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "suspension_mode", updatable = false, insertable = false, length = 20)
    private SuspensionMode suspensionMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "suspension_reason", updatable = false, insertable = false, length = 20)
    private SuspensionReason suspensionReason;

    @Column(name = "suspension_note", updatable = false, insertable = false, length = 500)
    private String suspensionNote;

    @Column(name = "suspended_at", updatable = false, insertable = false)
    private Instant suspendedAt;

    @Column(name = "suspended_by", updatable = false, insertable = false)
    private UUID suspendedBy;

    @Column(name = "status_changed_at", updatable = false, insertable = false)
    private Instant statusChangedAt;

    protected Tenant() {
        // JPA
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Null for tenants that existed before the table (backfilled by V21). */
    public String getFirstAdminEmail() {
        return firstAdminEmail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** The operator who provisioned it; null for backfilled tenants. */
    public UUID getCreatedBy() {
        return createdBy;
    }

    public TenantStatus getStatus() {
        return status;
    }

    /** Set only while {@link TenantStatus#SUSPENDED}. */
    public SuspensionMode getSuspensionMode() {
        return suspensionMode;
    }

    public SuspensionReason getSuspensionReason() {
        return suspensionReason;
    }

    public String getSuspensionNote() {
        return suspensionNote;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public UUID getSuspendedBy() {
        return suspendedBy;
    }

    /** When the status last changed; null if it never has. */
    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }
}
