package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthEmailOutboxRepository
        extends JpaRepository<AuthEmailOutbox, UUID> {

    /**
     * Finds PENDING entries older than {@code pendingThreshold}.
     * Optionally filtered by {@code emailType} — pass {@code null} for all types.
     */
    @Query("SELECT e FROM AuthEmailOutbox e " +
            "WHERE e.status = 'PENDING' " +
            "AND e.createdAt < :pendingThreshold " +
            "AND (:emailType IS NULL OR e.emailType = :emailType)")
    List<AuthEmailOutbox> findPendingOlderThan(
            @Param("pendingThreshold") Instant pendingThreshold,
            @Param("emailType") AuthEmailType emailType);

    /**
     * Finds FAILED entries that still have remaining retry budget.
     * Optionally filtered by {@code emailType}.
     */
    @Query("SELECT e FROM AuthEmailOutbox e " +
            "WHERE e.status = 'FAILED' " +
            "AND e.retryCount < :maxRetries " +
            "AND (:emailType IS NULL OR e.emailType = :emailType)")
    List<AuthEmailOutbox> findFailedWithRemainingRetries(
            @Param("maxRetries") int maxRetries,
            @Param("emailType") AuthEmailType emailType);

    /**
     * Finds the most recent outbox entry for a user and email type.
     * Used by resend-invite and forgot-password flows to check
     * current status before creating a new entry.
     */
    @Query("SELECT e FROM AuthEmailOutbox e " +
            "WHERE e.user.id = :userId " +
            "AND e.emailType = :emailType " +
            "ORDER BY e.createdAt DESC")
    Optional<AuthEmailOutbox> findLatestByUserIdAndType(
            @Param("userId") UUID userId,
            @Param("emailType") AuthEmailType emailType);
}