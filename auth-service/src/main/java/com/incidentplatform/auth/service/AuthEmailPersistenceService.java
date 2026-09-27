package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.AuthTokenService.GeneratedToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The short, independent database transactions around each auth email send,
 * so none is open while the scheduler waits on SMTP.
 *
 * <h2>Fixed: one transaction spanning an entire batch of SMTP sends</h2>
 * {@code AuthEmailScheduler} once wrapped its whole loop — every blocking
 * {@code JavaMailSender.send()} in the batch — in a single
 * {@code @Transactional}, holding a database connection for all of them. Same
 * fix as {@code postmortem-service}'s {@code PostmortemPersistenceService}:
 * each method here is its own short transaction.
 *
 * <h2>One attempt (backlog #0-52)</h2>
 * {@link #prepareAttempt} decides whether the entry is still worth sending and,
 * if so, creates the token the email will carry — invalidating the user's
 * earlier tokens of that type first, so only the newest link works — and
 * commits it before the send (the link must work the moment the email lands).
 * The raw token exists only in memory and in the email. Then the scheduler
 * sends, and records the outcome with {@link #recordSent}, {@link #recordFailed}
 * or {@link #recordGivenUp}; a failed send also invalidates the token it did
 * not deliver.
 *
 * <p>Every change to the outbox row is a conditional UPDATE that applies only
 * while the row is PENDING or FAILED. The scheduler is its only writer; the
 * guard covers a run that outlived its ShedLock, and each method reports
 * whether it changed the row so the caller never logs an outcome it did not
 * record.
 */
@Service
public class AuthEmailPersistenceService {

    private final AuthEmailOutboxRepository outboxRepository;
    private final AuthTokenRepository tokenRepository;
    private final AuthTokenService tokenService;
    private final UserRepository userRepository;

    public AuthEmailPersistenceService(AuthEmailOutboxRepository outboxRepository,
                                       AuthTokenRepository tokenRepository,
                                       AuthTokenService tokenService,
                                       UserRepository userRepository) {
        this.outboxRepository = outboxRepository;
        this.tokenRepository  = tokenRepository;
        this.tokenService     = tokenService;
        this.userRepository   = userRepository;
    }

    /** What {@link #prepareAttempt} decided. */
    public sealed interface Attempt {
        /** Send the email with this token. */
        record Send(String rawToken, UUID tokenId) implements Attempt {}
        /** The entry was closed without an attempt. */
        record Closed(AuthEmailStatus status, String reason) implements Attempt {}
        /** The entry was no longer PENDING or FAILED; nothing was done. */
        record AlreadyClosed() implements Attempt {}
    }

    /**
     * Closes the entry if it is no longer worth sending, otherwise creates the
     * token for this attempt.
     *
     * <ul>
     *   <li>SUPERSEDED — the user is gone (deleted or archived), an invite was
     *       already accepted, or a newer request of this type exists.</li>
     *   <li>PERMANENTLY_FAILED — the deadline, plus {@code deadlineTolerance}
     *       for the scheduler's own latency, has passed. The tolerance lets
     *       the last attempt {@code AuthEmailRetryPolicy} schedules at the
     *       deadline itself still go out when the run picking it up starts a
     *       little later.</li>
     * </ul>
     */
    @Transactional
    public Attempt prepareAttempt(AuthEmailOutbox entry, Instant now, Duration deadlineTolerance) {
        final Optional<User> user =
                userRepository.findByIdAndTenantId(entry.getUserId(), entry.getTenantId());
        final String supersededBecause = user.isEmpty()
                ? "user no longer exists"
                : entry.getEmailType() == AuthEmailType.INVITE && user.get().getPasswordHash() != null
                ? "invite already accepted"
                : outboxRepository.existsByUserIdAndEmailTypeAndCreatedAtAfter(
                        entry.getUserId(), entry.getEmailType(), entry.getCreatedAt())
                ? "replaced by a newer request"
                : null;
        if (supersededBecause != null) {
            return close(entry, AuthEmailStatus.SUPERSEDED, supersededBecause);
        }
        if (now.isAfter(entry.getDeadline().plus(deadlineTolerance))) {
            return close(entry, AuthEmailStatus.PERMANENTLY_FAILED,
                    "deadline " + entry.getDeadline() + " passed before the email could be sent");
        }

        tokenRepository.invalidateValidTokens(
                entry.getUserId(), entry.getEmailType().tokenType(), now);
        final GeneratedToken token = switch (entry.getEmailType()) {
            case INVITE -> tokenService.generateInviteTokenWithEntity(user.get(), entry.getTenantId());
            case PASSWORD_RESET ->
                    tokenService.generatePasswordResetTokenWithEntity(user.get(), entry.getTenantId());
        };
        return new Attempt.Send(token.rawToken(), token.token().getId());
    }

    private Attempt close(AuthEmailOutbox entry, AuthEmailStatus status, String reason) {
        return outboxRepository.close(entry.getId(), status, reason) == 1
                ? new Attempt.Closed(status, reason)
                : new Attempt.AlreadyClosed();
    }

    /** @return whether the entry was still open and is now SENT */
    @Transactional
    public boolean recordSent(UUID entryId, Instant now) {
        return outboxRepository.markSent(entryId, now) == 1;
    }

    /**
     * FAILED, to be tried again at {@code nextAttemptAt}; the token of the
     * failed attempt is invalidated.
     *
     * @return whether the entry was still open and is now FAILED
     */
    @Transactional
    public boolean recordFailed(UUID entryId, UUID tokenId, String error,
                                Instant nextAttemptAt, Instant now) {
        tokenRepository.markUsedIfUnused(tokenId, now);
        return outboxRepository.markFailed(entryId, error, nextAttemptAt) == 1;
    }

    /**
     * PERMANENTLY_FAILED after a failed send with no time left for another;
     * the token of the failed attempt is invalidated.
     *
     * @return whether the entry was still open and is now PERMANENTLY_FAILED
     */
    @Transactional
    public boolean recordGivenUp(UUID entryId, UUID tokenId, String error, Instant now) {
        tokenRepository.markUsedIfUnused(tokenId, now);
        return outboxRepository.markGivenUpAfterAttempt(entryId, error) == 1;
    }
}
