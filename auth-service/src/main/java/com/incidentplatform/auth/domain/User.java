package com.incidentplatform.auth.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "email", nullable = false)
    private String email;

    // Nullable: OAuth2 users have no local password
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "user",
            cascade = CascadeType.ALL,
            fetch = FetchType.EAGER,
            orphanRemoval = true)
    private List<UserRole> roles = new ArrayList<>();

    protected User() {}

    /**
     * Test fixture factory — builds a fully-formed User without going through
     * JPA persistence. Mirrors the {@code AuditEvent.system()} /
     * {@code AuditEvent.user()} factory pattern used elsewhere in the platform
     * for constructing domain entities in unit tests without reflection.
     *
     * <p>Not used by production code — production users are always created
     * via {@link #register} and persisted through {@code UserRepository}.
     */
    public static User forTesting(UUID id, String tenantId, String email,
                                  String passwordHash, boolean active,
                                  List<String> roleNames) {
        final User user = new User();
        user.id = id;
        user.tenantId = tenantId;
        user.email = email;
        user.passwordHash = passwordHash;
        user.active = active;
        for (String roleName : roleNames) {
            user.roles.add(UserRole.forTesting(user, tenantId, roleName));
        }
        return user;
    }

    /**
     * Production factory for creating a new user (invite-token flow —
     * password set later via accept-invite). passwordHash is null until
     * the user completes registration.
     */
    public static User register(String tenantId, String email) {
        final User user = new User();
        user.tenantId = tenantId;
        user.email = email;
        user.active = true;
        return user;
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getOrganizationId() { return organizationId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isActive() { return active; }
    public List<UserRole> getRoles() { return roles; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /**
     * Sets the BCrypt-hashed password. Called once during accept-invite flow.
     * The raw password must be hashed by the caller before passing here —
     * this method accepts only the hash, never plain text.
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
    }


    /**
     * Replaces all current roles with the provided set.
     * Clears existing UserRole records (orphanRemoval=true handles DB delete)
     * and adds new ones. Called by PATCH /api/v1/users/{id}/roles.
     */
    public void updateRoles(List<String> roleNames, String tenantId) {
        this.roles.clear();
        for (final String role : roleNames) {
            this.roles.add(UserRole.grant(this, tenantId, role));
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Sets the active flag. Called by PATCH /api/v1/users/{id}/status.
     */
    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public List<String> getRoleNames() {
        return roles.stream()
                .map(UserRole::getRole)
                .toList();
    }
}