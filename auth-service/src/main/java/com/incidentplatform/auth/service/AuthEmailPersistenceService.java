package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.MfaRecoveryRequestRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.AuthTokenService.GeneratedToken;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AuthEmailPersistenceService.class);

    private final AuthEmailOutboxRepository outboxRepository;
    private final AuthTokenRepository tokenRepository;
    private final AuthTokenService tokenService;
    private final UserRepository userRepository;
    private final MfaRecoveryRequestRepository recoveryRequestRepository;
    private final Counter mfaNoticeNotRecorded;

    public AuthEmailPersistenceService(AuthEmailOutboxRepository outboxRepository,
                                       AuthTokenRepository tokenRepository,
                                       AuthTokenService tokenService,
                                       UserRepository userRepository,
                                       MfaRecoveryRequestRepository recoveryRequestRepository,
                                       MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.tokenRepository  = tokenRepository;
        this.tokenService     = tokenService;
        this.userRepository   = userRepository;
        this.recoveryRequestRepository = recoveryRequestRepository;
        this.mfaNoticeNotRecorded = Counter.builder("auth.mfa.notice.unrecorded")
                .description("MFA_ENABLED notices sent that did not mark the user's current factor "
                        + "(backlog #0-83)")
                .register(meterRegistry);
    }

    /** What {@link #prepareAttempt} decided. */
    public sealed interface Attempt {
        /**
         * Send the email with this token; both null for a notification
         * ({@link AuthEmailType#carriesToken()} false, backlog #0-83).
         */
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
     *       already accepted, or a newer request of this type exists (except
     *       for API_KEY_CREATED, one per key: {@link AuthEmailType#supersededByNewer}).</li>
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
                // Backlog #0-90: a cancel link for a request that already ended is no use.
                : entry.getEmailType() == AuthEmailType.MFA_RECOVERY_REQUESTED
                        && !recoveryRequestRepository.isPending(entry.getMfaRecoveryRequestId())
                ? "MFA recovery request no longer pending"
                : entry.getEmailType().supersededByNewer()
                        && outboxRepository.existsByUserIdAndEmailTypeAndCreatedAtAfter(
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

        if (!entry.getEmailType().carriesToken()) {
            return new Attempt.Send(null, null);
        }
        tokenRepository.invalidateValidTokens(
                entry.getUserId(), entry.getEmailType().tokenType(), now);
        final GeneratedToken token = switch (entry.getEmailType()) {
            case INVITE -> tokenService.generateInviteTokenWithEntity(user.get(), entry.getTenantId());
            case PASSWORD_RESET, MFA_RECOVERY_COMPLETED ->
                    tokenService.generatePasswordResetTokenWithEntity(user.get(), entry.getTenantId());
            case MFA_RECOVERY_REQUESTED ->
                    tokenService.generateMfaRecoveryCancelTokenWithEntity(user.get(), entry.getTenantId());
            case MFA_ENABLED, MFA_DISABLED, MFA_RESET, API_KEY_CREATED -> throw new IllegalStateException("unreachable: no token");
        };
        return new Attempt.Send(token.rawToken(), token.token().getId());
    }

    private Attempt close(AuthEmailOutbox entry, AuthEmailStatus status, String reason) {
        return outboxRepository.close(entry.getId(), status, reason) == 1
                ? new Attempt.Closed(status, reason)
                : new Attempt.AlreadyClosed();
    }

    /**
     * Marks the entry SENT. For an MFA recovery notice it records on the
     * request that the notice went out (backlog #0-90). For an MFA_ENABLED notice it also records, on the
     * user, that the current factor's notice went out (backlog #0-83): the
     * platform API's grace period counts from there, and the fact must outlive
     * the outbox purge. Same transaction, so the two never disagree.
     *
     * <p>A notice that marks no factor is logged and counted (found in
     * review): usually the user disabled or re-enrolled MFA before it went
     * out, which is harmless, but otherwise the factor would stay "notice not
     * delivered" and the platform API would refuse its owner with no trace
     * outside the 403. Either way the owner disables and enables MFA again to
     * get a new notice.
     *
     * <p>Such a notice is still sent, not closed unsent, on purpose (found in
     * review): it states when MFA was enabled, which stays true, and holding
     * it back would let someone with the password hide an enrolment from the
     * owner by enabling and quickly disabling MFA.
     *
     * @return whether the entry was still open and is now SENT
     */
    @Transactional
    public boolean recordSent(AuthEmailOutbox entry, Instant now) {
        if (outboxRepository.markSent(entry.getId(), now) != 1) {
            return false;
        }
        if (entry.getEmailType() == AuthEmailType.MFA_ENABLED
                && userRepository.recordMfaEnabledNoticeSent(
                        entry.getUserId(), entry.getTenantId(), entry.getCreatedAt(), now) != 1) {
            mfaNoticeNotRecorded.increment();
            log.warn("MFA_ENABLED notice sent but it marks no current factor (MFA disabled or re-enrolled "
                            + "since, or already recorded): entry={}, user={}, tenant={}, requestedAt={}",
                    entry.getId(), entry.getUserId(), entry.getTenantId(), entry.getCreatedAt());
        }
        // Backlog #0-90: the request's waiting period counts from here. A
        // request cancelled while its notice was being sent records nothing,
        // which is harmless: it is over.
        if (entry.getEmailType() == AuthEmailType.MFA_RECOVERY_REQUESTED
                && recoveryRequestRepository.recordNoticeSent(entry.getMfaRecoveryRequestId(), now) != 1) {
            log.info("MFA recovery notice sent for a request no longer pending: entry={}, request={}, tenant={}",
                    entry.getId(), entry.getMfaRecoveryRequestId(), entry.getTenantId());
        }
        return true;
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
        invalidateUndelivered(tokenId, now);
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
        invalidateUndelivered(tokenId, now);
        return outboxRepository.markGivenUpAfterAttempt(entryId, error) == 1;
    }

    /** A notification has no token ({@code tokenId} null), so nothing to invalidate. */
    private void invalidateUndelivered(UUID tokenId, Instant now) {
        if (tokenId != null) {
            tokenRepository.markUsedIfUnused(tokenId, now);
        }
    }
}
