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
 * <h2>Archiving</h2>
 * {@code @SQLRestriction("archived_at IS NULL")} ensures all Hibernate queries
 * automatically exclude archived teams — consistent with {@link User}.
 *
 * <p>Archived teams can be restored via {@link #restore()} as long as no
 * active team with the same name exists in the tenant. When a team is
 * archived, its {@link TeamMember} rows are preserved — a full restore
 * brings back both the team and its membership.
 *
 * <h2>Column rename: deleted_at → archived_at (V11 migration)</h2>
 * "Archived" correctly describes the semantics: the team is hidden from
 * normal queries but never physically removed. "Deleted" was misleading
 * because the record survives indefinitely.
 *
 * <h2>Name uniqueness</h2>
 * {@code (name, tenant_id)} is unique among active teams. Archived teams
 * may share a name with active teams — uniqueness checked at application
 * level for active teams only. On restore, a name-conflict guard prevents
 * restoring a team when another active team already holds the same name.
 */
@Entity
@Table(name = "teams")
@SQLRestriction("archived_at IS NULL")
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

    /** Archiving timestamp. {@code null} = active. Reversible via {@link #restore()}. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Team() {}

    /**
     * Production factory — creates a new active team.
     */
    public static Team create(String tenantId, String name, String description) {
        final Team team = new Team();
        team.tenantId    = tenantId;
        team.name        = name;
        team.description = description;
        team.createdAt   = Instant.now();
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

    // ── lifecycle mutations ───────────────────────────────────────────────

    /**
     * Archives this team — hides from normal queries.
     * {@link TeamMember} rows are preserved for restore.
     * Reversible via {@link #restore()}.
     */
    public void archive() {
        this.archivedAt = Instant.now();
    }

    /**
     * Restores this team from archived state.
     * The caller must verify no active team with the same name exists
     * in this tenant before calling this method.
     */
    public void restore() {
        this.archivedAt = null;
    }

    /**
     * @deprecated use {@link #archive()} — renamed for semantic clarity
     */
    @Deprecated(forRemoval = true)
    public void softDelete() {
        archive();
    }

    // ── field mutations ───────────────────────────────────────────────────

    public void updateName(String name) {
        this.name = name;
    }

    public void updateDescription(String description) {
        this.description = description;
    }

    // ── accessors ─────────────────────────────────────────────────────────

    public UUID getId()            { return id; }
    public String getTenantId()    { return tenantId; }
    public String getName()        { return name; }
    public String getDescription() { return description; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public boolean isArchived()    { return archivedAt != null; }

    /** @deprecated use {@link #isArchived()} */
    @Deprecated(forRemoval = true)
    public boolean isDeleted()     { return isArchived(); }
}