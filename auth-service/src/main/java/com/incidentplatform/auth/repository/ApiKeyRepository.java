package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.ApiKeyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /**
     * Looks up an active key by its SHA-256 hash.
     *
     * <p>Called on every API request that uses key authentication.
     * The unique partial index {@code idx_api_keys_hash WHERE revoked_at IS NULL}
     * makes this a single index scan — O(1) regardless of total key count.
     *
     * <p>Returns empty if:
     * <ul>
     *   <li>Hash not found (wrong/tampered key)</li>
     *   <li>Key is revoked ({@code revoked_at IS NOT NULL})</li>
     *   <li>Key is expired (checked in service layer after fetch)</li>
     * </ul>
     */
    @Query("SELECT k FROM ApiKey k " +
            "LEFT JOIN FETCH k.ownerUser " +
            "WHERE k.keyHash = :hash AND k.revokedAt IS NULL")
    Optional<ApiKey> findActiveByHash(@Param("hash") String hash);

    /**
     * Lists all active keys for a tenant (for management UI).
     * Excludes revoked keys — they are preserved in DB for audit only.
     */
    @Query("SELECT k FROM ApiKey k " +
            "WHERE k.tenantId = :tenantId AND k.revokedAt IS NULL " +
            "ORDER BY k.createdAt DESC")
    List<ApiKey> findActiveByTenantId(@Param("tenantId") String tenantId);

    /**
     * Lists active keys owned by a specific user (for Personal key management).
     */
    @Query("SELECT k FROM ApiKey k " +
            "WHERE k.ownerUser.id = :userId AND k.revokedAt IS NULL " +
            "ORDER BY k.createdAt DESC")
    List<ApiKey> findActiveByOwnerId(@Param("userId") UUID userId);

    /**
     * Bulk-revokes all PERSONAL keys belonging to a user.
     * Called when a user is archived or anonymized.
     */
    @Modifying
    @Query("UPDATE ApiKey k SET k.revokedAt = :now " +
            "WHERE k.ownerUser.id = :userId AND k.revokedAt IS NULL")
    void revokeAllPersonalKeysForUser(
            @Param("userId") UUID userId,
            @Param("now") Instant now);

    Optional<ApiKey> findByIdAndTenantId(UUID id, String tenantId);
}