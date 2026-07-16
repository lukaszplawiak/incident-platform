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

    /** Deletes all backup codes for a user — called on MFA disable or regeneration. */
    @Modifying
    @Query("DELETE FROM MfaBackupCode c WHERE c.user.id = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);
}