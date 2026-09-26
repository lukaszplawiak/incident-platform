package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
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
 * Auth email outbox (backlog #0-52: one row = the intent to send one email).
 *
 * <h2>Reads for the scheduler</h2>
 * Flat rows, no joins: email and tenant are on the row, and the token is
 * created at send time. Each lane is capped by a {@link Pageable} and ordered,
 * so every tick takes the oldest due entries (backlog #54). Both lanes are
 * served by {@code idx_auth_email_outbox_due}.
 *
 * <h2>Writes after the INSERT</h2>
 * Only {@code AuthEmailScheduler}, through the conditional UPDATEs below: each
 * changes a row only while it is still PENDING or FAILED and returns the number
 * of rows changed, so a caller learns that someone else already closed the
 * entry instead of overwriting it. No {@code clearAutomatically}: the scheduler
 * never reads the changed rows back in the same persistence context, and
 * clearing would detach entities an enclosing transaction still uses (the
 * backlog #0-50 bug).
 */
@Repository
public interface AuthEmailOutboxRepository
        extends JpaRepository<AuthEmailOutbox, UUID> {

    /** New requests that are due — the lane of {@code AuthEmailScheduler.processPending()}. */
    @Query("SELECT e FROM AuthEmailOutbox e " +
            "WHERE e.status = 'PENDING' AND e.nextAttemptAt <= :now " +
            "ORDER BY e.nextAttemptAt ASC, e.createdAt ASC")
    List<AuthEmailOutbox> findDuePending(@Param("now") Instant now, Pageable pageable);

    /**
     * Failed entries whose next attempt has come — the lane of
     * {@code AuthEmailScheduler.retryFailed()}. A separate lane, so a backlog
     * of retries after an SMTP outage never delays new emails.
     */
    @Query("SELECT e FROM AuthEmailOutbox e " +
            "WHERE e.status = 'FAILED' AND e.nextAttemptAt <= :now " +
            "ORDER BY e.nextAttemptAt ASC, e.createdAt ASC")
    List<AuthEmailOutbox> findDueFailed(@Param("now") Instant now, Pageable pageable);

    /**
     * Finds the most recent outbox entry for a user and email type.
     * Used by resend-invite, forgot-password and the operator admin
     * reconciler to check current status before creating a new entry.
     *
     * <h2>Fixed (backlog #0-53): {@code findFirst}, not an unlimited query</h2>
     * This was a JPQL {@code ORDER BY e.createdAt DESC} query returning
     * {@code Optional} with no row limit. Spring Data runs such a query as a
     * single-result query, so as soon as a user had two entries of a type
     * (the second resend of an invite, a repeated password reset) it threw
     * {@code IncorrectResultSizeDataAccessException} instead of returning the
     * newest one. The derived {@code findFirst...OrderBy...} form limits the
     * query to one row.
     */
    Optional<AuthEmailOutbox> findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(
            UUID userId, AuthEmailType emailType);

    /** Whether a newer request of this type exists for the user — then the older one is superseded. */
    boolean existsByUserIdAndEmailTypeAndCreatedAtAfter(
            UUID userId, AuthEmailType emailType, Instant createdAt);

    /** SENT after the SMTP server accepted the email. */
    @Modifying
    @Query("UPDATE AuthEmailOutbox e SET e.status = 'SENT', e.attempts = e.attempts + 1, " +
            "e.sentAt = :now, e.nextAttemptAt = NULL, e.errorMessage = NULL " +
            "WHERE e.id = :id AND e.status IN ('PENDING', 'FAILED')")
    int markSent(@Param("id") UUID id, @Param("now") Instant now);

    /** FAILED after a failed send, to be tried again at {@code nextAttemptAt}. */
    @Modifying
    @Query("UPDATE AuthEmailOutbox e SET e.status = 'FAILED', e.attempts = e.attempts + 1, " +
            "e.errorMessage = :error, e.nextAttemptAt = :nextAttemptAt " +
            "WHERE e.id = :id AND e.status IN ('PENDING', 'FAILED')")
    int markFailed(@Param("id") UUID id, @Param("error") String error,
                   @Param("nextAttemptAt") Instant nextAttemptAt);

    /** PERMANENTLY_FAILED after a failed send that leaves no time for another. */
    @Modifying
    @Query("UPDATE AuthEmailOutbox e SET e.status = 'PERMANENTLY_FAILED', " +
            "e.attempts = e.attempts + 1, e.errorMessage = :error, e.nextAttemptAt = NULL " +
            "WHERE e.id = :id AND e.status IN ('PENDING', 'FAILED')")
    int markGivenUpAfterAttempt(@Param("id") UUID id, @Param("error") String error);

    /**
     * Closes an entry without an attempt: SUPERSEDED, or PERMANENTLY_FAILED
     * when its deadline passed before it could be tried.
     */
    @Modifying
    @Query("UPDATE AuthEmailOutbox e SET e.status = :status, e.errorMessage = :reason, " +
            "e.nextAttemptAt = NULL " +
            "WHERE e.id = :id AND e.status IN ('PENDING', 'FAILED')")
    int close(@Param("id") UUID id, @Param("status") AuthEmailStatus status,
              @Param("reason") String reason);

    /** Retention: deletes terminal entries created before {@code cutoff}. */
    @Modifying
    @Query("DELETE FROM AuthEmailOutbox e " +
            "WHERE e.status IN ('SENT', 'PERMANENTLY_FAILED', 'SUPERSEDED') " +
            "AND e.createdAt < :cutoff")
    int deleteTerminalCreatedBefore(@Param("cutoff") Instant cutoff);
}
