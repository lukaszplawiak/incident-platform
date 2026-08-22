package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.MfaBackupCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MfaBackupCodeRepository extends JpaRepository<MfaBackupCode, UUID> {

    /** Returns all unused backup codes for a user. */
    @Query("SELECT c FROM MfaBackupCode c WHERE c.user.id = :userId AND c.usedAt IS NULL")
    List<MfaBackupCode> findUnusedByUserId(@Param("userId") UUID userId);

    /** Counts unused backup codes — for display purposes only, never reveals codes. */
    @Query("SELECT COUNT(c) FROM MfaBackupCode c WHERE c.user.id = :userId AND c.usedAt IS NULL")
    long countUnusedByUserId(@Param("userId") UUID userId);

    /**
     * Deletes all backup codes for a user — called on MFA disable or
     * regeneration.
     *
     * <h2>Fixed (backlog #57): {@code clearAutomatically = true}</h2>
     * Same class of issue as {@code AuthTokenRepository.deleteExpiredAndUsed}
     * (backlog #34) and {@code ApiKeyRepository.revokeAllPersonalKeysForUser}
     * (backlog #56) — see either's Javadoc for the full account. This
     * {@code @Modifying} DELETE bypasses the persistence context, so
     * without {@code clearAutomatically}, an {@code MfaBackupCode}
     * entity already loaded earlier in the same transaction would stay
     * stale (appearing to still exist) if read again afterward.
     *
     * <p>Currently benign — the only caller ({@code MfaService.disableMfa})
     * does not subsequently read an {@code MfaBackupCode} in the same
     * transaction — but this is the standard, defensive default for this
     * class of query regardless.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM MfaBackupCode c WHERE c.user.id = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);
}