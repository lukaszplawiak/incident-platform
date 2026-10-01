package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
}
