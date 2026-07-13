package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * A team is a group of users within a tenant.
 *
 * <h2>Domain model</h2>
 * {@code tenant → teams (1:N) → users (N:M via TeamMember)}
 *
 * <h2>Soft delete</h2>
 * {@code @SQLRestriction("deleted_at IS NULL")} ensures all Hibernate queries
 * automatically exclude soft-deleted teams — consistent with {@link User}.
 * Hard deletes are intentionally not supported — a 30-day restore window
 * is planned (see backlog). When a team is soft-deleted, its
 * {@link TeamMember} rows are retained for the restore window.
 *
 * <h2>Name uniqueness</h2>
 * {@code (name, tenant_id)} is unique among active teams. The constraint is
 * DEFERRABLE in SQL to allow rename + recreate in the same transaction.
 * Soft-deleted teams may share a name with active teams — uniqueness is
 * enforced at the application level for active teams only.
 */
@Entity
@Table(name = "teams")
@SQLRestriction("deleted_at IS NULL")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Team() {}

    /**
     * Production factory — creates a new active team.
     */
    public static Team create(String tenantId, String name, String description) {
        final Team team = new Team();
        team.tenantId   = tenantId;
        team.name       = name;
        team.description = description;
        team.createdAt  = Instant.now();
        return team;
    }

    /**
     * Test fixture factory — builds a fully-formed Team without JPA.
     */
    public static Team forTesting(UUID id, String tenantId, String name) {
        final Team team = new Team();
        team.id        = id;
        team.tenantId  = tenantId;
        team.name      = name;
        team.createdAt = Instant.now();
        return team;
    }

    /**
     * Marks this team as soft-deleted.
     * {@link TeamMember} rows are retained for the 30-day restore window.
     */
    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    public void updateName(String name) {
        this.name = name;
    }

    public void updateDescription(String description) {
        this.description = description;
    }

    public UUID getId()          { return id; }
    public String getTenantId()  { return tenantId; }
    public String getName()      { return name; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public boolean isDeleted()   { return deletedAt != null; }
}