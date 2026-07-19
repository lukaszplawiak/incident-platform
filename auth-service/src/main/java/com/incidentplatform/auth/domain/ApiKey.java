package com.incidentplatform.auth.domain;

import com.incidentplatform.auth.converter.ScopesConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.List;
import java.util.UUID;

/**
 * Long-lived API credential for machine-to-machine integrations.
 *
 * <h2>Key format</h2>
 * Raw key: {@code ipl_<prefix8>.<random32>} — 43 characters total.
 * <ul>
 *   <li>{@code ipl_} — platform prefix, distinguishes from other secrets</li>
 *   <li>{@code <prefix8>} — first 8 chars stored in {@link #keyPrefix} for
 *       UI display ("...ending in abc12345") without revealing the secret</li>
 *   <li>{@code <random32>} — cryptographically random, 192-bit entropy</li>
 * </ul>
 * Only the SHA-256 hash ({@link #keyHash}) is stored. Raw key shown once.
 *
 * <h2>Two types</h2>
 * <ul>
 *   <li>{@link ApiKeyType#TENANT} — org-level, {@link #ownerUser} is null,
 *       survives user departure</li>
 *   <li>{@link ApiKeyType#PERSONAL} — user-bound, revoked on owner archive</li>
 * </ul>
 *
 * <h2>Scopes</h2>
 * Each key grants a subset of {@link ApiKeyScope} values. Checked by
 * {@code ApiKeyAuthFilter} on every request via {@link #hasScope}.
 *
 * <h2>Revocation</h2>
 * Soft-revoked via {@link #revoke()} — sets {@link #revokedAt}.
 * Hard deletes would erase audit evidence of key existence.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_type", nullable = false, updatable = false, length = 20)
    private ApiKeyType keyType;

    @Column(name = "name", nullable = false)
    private String name;

    /** SHA-256 of the raw key. Never returned in API responses. */
    @Column(name = "key_hash", nullable = false, unique = true, updatable = false)
    private String keyHash;

    /** First 8 chars of the raw key — for UI display only. */
    @Column(name = "key_prefix", nullable = false, updatable = false, length = 8)
    private String keyPrefix;

    /**
     * Granted scopes stored as comma-separated VARCHAR.
     * Example: "incidents:read,incidents:write,alerts:ingest"
     * Mapped via {@link ScopesConverter} — standard JPA AttributeConverter.
     */
    @Convert(converter = ScopesConverter.class)
    @Column(name = "scopes", columnDefinition = "TEXT", nullable = false)
    private List<String> scopes;

    /**
     * Owner for PERSONAL keys. Null for TENANT keys.
     * ON DELETE SET NULL — if owner is hard-deleted (shouldn't happen
     * in normal flow, archiving used instead), key is orphaned rather
     * than cascade-deleted, preserving audit trail.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private User ownerUser;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** Null = non-expiring. When set, key is rejected after this instant. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Null = active. Non-null = revoked. Soft-delete for audit trail. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Set when this key belongs to an {@link Integration}.
     * Null for manually-created API keys.
     * Used by {@code ApiKeyLookupServiceImpl} to resolve teamId in a single JOIN.
     */
    @Column(name = "integration_id")
    private UUID integrationId;

    protected ApiKey() {}

    public static ApiKey createTenant(String tenantId, String name,
                                      String keyHash, String keyPrefix,
                                      List<String> scopes, Instant expiresAt) {
        final ApiKey key = new ApiKey();
        key.tenantId  = tenantId;
        key.keyType   = ApiKeyType.TENANT;
        key.name      = name;
        key.keyHash   = keyHash;
        key.keyPrefix = keyPrefix;
        key.scopes    = List.copyOf(scopes);
        key.expiresAt = expiresAt;
        key.createdAt = Instant.now();
        return key;
    }

    public static ApiKey createPersonal(String tenantId, String name,
                                        String keyHash, String keyPrefix,
                                        List<String> scopes, Instant expiresAt,
                                        User ownerUser) {
        final ApiKey key = new ApiKey();
        key.tenantId  = tenantId;
        key.keyType   = ApiKeyType.PERSONAL;
        key.name      = name;
        key.keyHash   = keyHash;
        key.keyPrefix = keyPrefix;
        key.scopes    = List.copyOf(scopes);
        key.expiresAt = expiresAt;
        key.ownerUser = ownerUser;
        key.createdAt = Instant.now();
        return key;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    /**
     * Records that this key was used. Called asynchronously to avoid
     * adding a synchronous DB write to every authenticated request.
     */
    public void recordUsage() {
        this.lastUsedAt = Instant.now();
    }

    // ── validation ────────────────────────────────────────────────────────

    public boolean isActive() {
        return revokedAt == null
                && (expiresAt == null || Instant.now().isBefore(expiresAt));
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Returns true if this key grants the requested scope.
     * Called on every authenticated API request — must be fast (no DB).
     */
    public boolean hasScope(ApiKeyScope scope) {
        return scopes.contains(scope.getScopeName());
    }

    // ── accessors ─────────────────────────────────────────────────────────

    public UUID getId()            { return id; }
    public String getTenantId()    { return tenantId; }
    public ApiKeyType getKeyType() { return keyType; }
    public String getName()        { return name; }
    public String getKeyHash()     { return keyHash; }
    public String getKeyPrefix()   { return keyPrefix; }
    public List<String> getScopes() { return scopes; }
    public User getOwnerUser()     { return ownerUser; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getExpiresAt()  { return expiresAt; }
    public Instant getRevokedAt()  { return revokedAt; }
    public Instant getCreatedAt()  { return createdAt; }
    public boolean isTenant()      { return keyType == ApiKeyType.TENANT; }
    public boolean isPersonal()    { return keyType == ApiKeyType.PERSONAL; }
    public UUID getIntegrationId() { return integrationId; }

    /** Called by {@code IntegrationService} after integration is persisted. */
    public void setIntegrationId(UUID integrationId) {
        this.integrationId = integrationId;
    }
}