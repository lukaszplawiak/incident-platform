package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.ApiKeyType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fixed (found in the review of backlog #0-83): every bulk UPDATE here that
 * clears the persistence context also flushes it first
 * ({@code flushAutomatically}). Hibernate flushes before a JPQL bulk statement
 * only the pending changes of the tables that statement touches, so a change
 * to another table made earlier in the same transaction (an outbox INSERT, a
 * user update) was silently dropped by the clear. A password reset lost its
 * MFA_DISABLED notification this way.
 */
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
     * Active keys of the tenant that a user created at or after {@code since}
     * (backlog #0-89), of every type. A key with no recorded creator (made
     * before V26) never matches.
     */
    @Query("SELECT k FROM ApiKey k " +
            "WHERE k.tenantId = :tenantId AND k.createdByUserId = :userId " +
            "AND k.revokedAt IS NULL AND k.createdAt >= :since " +
            "ORDER BY k.createdAt DESC")
    List<ApiKey> findActiveCreatedBy(@Param("tenantId") String tenantId,
                                     @Param("userId") UUID userId,
                                     @Param("since") Instant since);

    /**
     * Keys of every state, revoked ones included, that a user created at or
     * after {@code since} (backlog #0-89): the creation limit, which a
     * create-and-revoke loop must not escape. Newest first, as many as the
     * page asks for (the limit), so the load stays bounded (review).
     */
    @Query("SELECT k.createdAt FROM ApiKey k " +
            "WHERE k.tenantId = :tenantId AND k.createdByUserId = :userId AND k.createdAt >= :since " +
            "ORDER BY k.createdAt DESC")
    List<Instant> findCreationTimesSince(@Param("tenantId") String tenantId,
                                         @Param("userId") UUID userId,
                                         @Param("since") Instant since,
                                         Pageable page);

    /**
     * How many active keys without an owner (tenant and integration keys) a
     * user created (backlog #0-89): told to the user in the MFA reset email,
     * as they survive the reset and need a review.
     */
    @Query("SELECT count(k) FROM ApiKey k " +
            "WHERE k.tenantId = :tenantId AND k.createdByUserId = :userId " +
            "AND k.ownerUser IS NULL AND k.revokedAt IS NULL")
    long countActiveUnownedCreatedBy(@Param("tenantId") String tenantId, @Param("userId") UUID userId);

    /**
     * Bulk-revokes all PERSONAL keys belonging to a user.
     * Called when a user is archived or anonymized.
     *
     * <h2>Fixed (backlog #56): {@code clearAutomatically = true}</h2>
     * {@code @Modifying} UPDATE/DELETE queries execute directly at the
     * SQL level, bypassing Hibernate's persistence context entirely — an
     * {@code ApiKey} entity already loaded/saved earlier in the same
     * transaction stays in that context, unaware the underlying row was
     * just updated by this statement. Without {@code clearAutomatically},
     * a subsequent read of that same entity within the same transaction
     * would return the stale, pre-revocation in-memory object instead of
     * correctly reflecting {@code revokedAt} now being set — Hibernate
     * checks the persistence context before ever re-querying the
     * database. Same class of issue already found and fixed for
     * {@code AuthTokenRepository.deleteExpiredAndUsed} (backlog #34),
     * confirmed reproducible there by a real-Postgres Testcontainers
     * test — a mocked-repository test cannot catch this, since Mockito
     * has no persistence context to get out of sync in the first place.
     *
     * <p>Currently benign in production — no caller (archive, anonymize,
     * and since backlog #0-89 the password reset, the password change on
     * request and the MFA resets) reads an {@code ApiKey} afterwards in the
     * same transaction — but {@code clearAutomatically = true} is the
     * standard, defensive default for this exact class of query regardless,
     * protecting any future code added to that same transactional scope.
     * The callers run it last, as it also detaches the {@code User} they hold.
     *
     * @return the number of keys revoked by this call
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ApiKey k SET k.revokedAt = :now " +
            "WHERE k.ownerUser.id = :userId AND k.revokedAt IS NULL")
    int revokeAllPersonalKeysForUser(
            @Param("userId") UUID userId,
            @Param("now") Instant now);

    Optional<ApiKey> findByIdAndTenantId(UUID id, String tenantId);

    /**
     * Sets {@code last_used_at} to {@code now} unless it was already set at or
     * after {@code threshold} (backlog #0-16). The condition lives in SQL so
     * that concurrent requests and several auth-service replicas together
     * write at most once per interval, without reading the row first.
     *
     * @return number of rows updated (0 when the key was used recently)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ApiKey k SET k.lastUsedAt = :now " +
            "WHERE k.id = :id AND (k.lastUsedAt IS NULL OR k.lastUsedAt < :threshold)")
    int touchLastUsedAt(@Param("id") UUID id,
                        @Param("now") Instant now,
                        @Param("threshold") Instant threshold);
}
