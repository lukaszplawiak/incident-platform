package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single role assignment for a {@link User} within a tenant.
 *
 * <h2>Role storage</h2>
 * The {@link Role} enum is persisted as a {@code VARCHAR} using
 * {@code @Enumerated(EnumType.STRING)}. This means the column stores
 * {@code "ROLE_ADMIN"} / {@code "ROLE_RESPONDER"} — human-readable and
 * stable across refactors (unlike {@code EnumType.ORDINAL} which breaks
 * if enum values are reordered).
 *
 * <h2>No CHECK constraint in schema</h2>
 * Validation is enforced at the Java level by the {@link Role} enum.
 * The database CHECK constraint was removed in V8 migration — adding a
 * new role no longer requires DDL changes.
 */
@Entity
@Table(name = "user_roles")
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 50)
    private Role role;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt = Instant.now();

    protected UserRole() {}

    /**
     * Test fixture factory — see {@link User#forTesting} for rationale.
     */
    static UserRole forTesting(User user, String tenantId, String roleName) {
        final UserRole userRole = new UserRole();
        userRole.user     = user;
        userRole.tenantId = tenantId;
        userRole.role     = Role.valueOf(roleName);
        return userRole;
    }

    public static UserRole grant(User user, String tenantId, String roleName) {
        return forTesting(user, tenantId, roleName);
    }

    public UUID getId()      { return id; }
    public String getTenantId() { return tenantId; }

    /**
     * Returns the role name as a String — used by {@link User#getRoleNames()}
     * to build JWT claims and Spring Security authority strings.
     */
    public String getRole()  { return role.name(); }

    /**
     * Returns the typed {@link Role} enum value.
     */
    public Role getRoleEnum() { return role; }
}