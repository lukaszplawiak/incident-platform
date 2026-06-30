package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

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

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt = Instant.now();

    protected UserRole() {}

    /**
     * Test fixture factory — see {@link User#forTesting} for rationale.
     */
    static UserRole forTesting(User user, String tenantId, String role) {
        final UserRole userRole = new UserRole();
        userRole.user = user;
        userRole.tenantId = tenantId;
        userRole.role = role;
        return userRole;
    }

    public static UserRole grant(User user, String tenantId, String role) {
        return forTesting(user, tenantId, role);
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRole() { return role; }
}