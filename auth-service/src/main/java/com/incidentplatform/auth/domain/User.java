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
import jakarta.persistence.Version;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Authenticated user within a tenant.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 * ACTIVE → archived → ACTIVE (restore)
 *       ↘ anonymized (irreversible, GDPR)
 * </pre>
 *
 * <h2>Soft-delete → Archiving</h2>
 * This entity uses an <em>archiving</em> model rather than hard-delete.
 * {@code archived_at} replaces the former {@code deleted_at} column —
 * the rename reflects the true semantics: the user record is archived
 * (hidden from normal queries) but never physically removed.
 * Archived users can be restored via {@link #restore()}.
 *
 * <h2>GDPR anonymization</h2>
 * Anonymization ({@link #anonymize(UUID)}) replaces personal data
 * (email, password_hash) with non-identifying placeholders and removes
 * all roles and team memberships. The user UUID is preserved so that
 * historical references (audit logs, incident assignments) remain valid.
 * Anonymization is <strong>irreversible</strong>.
 *
 * <h2>Data Vault TODO</h2>
 * The current approach stores PII (email, password_hash) directly on
 * this entity. A cleaner GDPR solution is the Data Vault pattern:
 * <pre>
 *   users:         id, tenant_id, active   (no PII)
 *   personal_data: user_id FK, email, password_hash, deleted_at
 * </pre>
 * Anonymization would then be a single {@code DELETE FROM personal_data}
 * with zero risk of re-identification via residual data. Migration to
 * Data Vault is planned when enterprise GDPR compliance is required.
 *
 * <h2>@SQLRestriction</h2>
 * {@code @SQLRestriction("archived_at IS NULL AND anonymized_at IS NULL")}
 * excludes both archived and anonymized users from all Hibernate queries.
 * Native {@code @Query(nativeQuery = true)} must add these conditions
 * explicitly — native SQL bypasses Hibernate filters.
 */
@Entity
@Table(name = "users")
@SQLRestriction("archived_at IS NULL AND anonymized_at IS NULL")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    // TODO (Data Vault): email and password_hash should live in a separate
    // personal_data table to enable clean GDPR erasure without in-place
    // anonymization. See class Javadoc for details.
    @Column(name = "email", nullable = false)
    private String email;

    /** Nullable: OAuth2 users have no local password. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Optimistic locking version counter — prevents lost updates when two
     * concurrent requests modify the same user.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    /** Archiving timestamp. {@code null} = active. Reversible via restore(). */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /**
     * GDPR anonymization timestamp. {@code null} = not anonymized.
     * Once set, personal data has been replaced — irreversible.
     */
    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    @OneToMany(mappedBy = "user",
            cascade = CascadeType.ALL,
            fetch = FetchType.LAZY,
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
        user.id           = id;
        user.tenantId     = tenantId;
        user.email        = email;
        user.passwordHash = passwordHash;
        user.active       = active;
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
        user.email    = email;
        user.active   = true;
        return user;
    }

    // ── lifecycle mutations ───────────────────────────────────────────────

    /**
     * Archives this user — hides from normal queries.
     * Reversible via {@link #restore()}.
     */
    public void archive() {
        this.archivedAt = Instant.now();
        this.active     = false;
        this.updatedAt  = Instant.now();
    }

    /**
     * Restores this user from archived state.
     *
     * @throws IllegalStateException if the user is anonymized (irreversible)
     */
    public void restore() {
        if (isAnonymized()) {
            throw new IllegalStateException(
                    "Cannot restore anonymized user — personal data has been erased");
        }
        this.archivedAt = null;
        this.active     = true;
        this.updatedAt  = Instant.now();
    }

    /**
     * Anonymizes this user for GDPR compliance.
     *
     * <p>Replaces personal data with non-identifying placeholders:
     * <ul>
     *   <li>email → {@code "anonymized-{uuid}@deleted.invalid"}</li>
     *   <li>passwordHash → {@code null}</li>
     *   <li>roles → cleared</li>
     * </ul>
     *
     * <p><strong>This operation is irreversible.</strong>
     * The user UUID is preserved so that historical references (audit logs,
     * incident assignments) remain valid.
     *
     * <p>Team memberships must be removed by the caller
     * ({@code TeamMemberRepository.deleteByUserId()}) before calling this
     * method, as {@code TeamMember} is not cascaded from User.
     *
     * <h3>Data Vault TODO</h3>
     * With the Data Vault pattern this method would be replaced by a single
     * {@code DELETE FROM personal_data WHERE user_id = ?} — no risk of
     * residual PII, no in-place replacement needed.
     *
     * @param anonymousId UUID used to generate the anonymized email alias
     * @throws IllegalStateException if the user is already anonymized
     */
    public void anonymize(UUID anonymousId) {
        if (isAnonymized()) {
            throw new IllegalStateException("User is already anonymized");
        }
        // Replace PII with non-identifying placeholders
        this.email        = "anonymized-" + anonymousId + "@deleted.invalid";
        this.passwordHash = null;
        this.active       = false;
        this.anonymizedAt = Instant.now();
        this.updatedAt    = Instant.now();
        // Clear roles — anonymized user has no permissions
        this.roles.clear();
    }

    /** @deprecated use {@link #archive()} — renamed for semantic clarity */
    @Deprecated(forRemoval = true)
    public void softDelete() {
        archive();
    }

    // ── field mutations ───────────────────────────────────────────────────

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt    = Instant.now();
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
        this.active    = active;
        this.updatedAt = Instant.now();
    }

    // ── accessors ─────────────────────────────────────────────────────────

    public UUID getId()            { return id; }
    public String getTenantId()    { return tenantId; }
    public String getEmail()       { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isActive()      { return active; }
    public List<UserRole> getRoles() { return roles; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getUpdatedAt()  { return updatedAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public Instant getAnonymizedAt() { return anonymizedAt; }
    public boolean isArchived()    { return archivedAt != null; }
    public boolean isAnonymized()  { return anonymizedAt != null; }

    /** @deprecated use {@link #isArchived()} */
    @Deprecated(forRemoval = true)
    public boolean isDeleted()     { return isArchived(); }

    public List<String> getRoleNames() {
        return roles.stream()
                .map(UserRole::getRole)
                .toList();
    }
}