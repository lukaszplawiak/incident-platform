package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    /**
     * Finds a valid (non-expired, non-used) token by its hash and type.
     * Used by accept-invite and reset-password endpoints.
     */
    @Query("""
            SELECT t FROM AuthToken t
            WHERE t.tokenHash = :hash
              AND t.type = :type
              AND t.usedAt IS NULL
              AND t.expiresAt > :now
            """)
    Optional<AuthToken> findValidByHashAndType(
            @Param("hash") String hash,
            @Param("type") AuthToken.Type type,
            @Param("now") Instant now);

    /**
     * Deletes all expired or used tokens — intended for scheduled cleanup.
     * Keeps the table lean without touching active tokens.
     */
    @Modifying
    @Query("""
            DELETE FROM AuthToken t
            WHERE t.usedAt IS NOT NULL
               OR t.expiresAt < :threshold
            """)
    int deleteExpiredAndUsed(@Param("threshold") Instant threshold);
}