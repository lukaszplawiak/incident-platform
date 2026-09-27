package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the intent to send an invite or password-reset email (backlog #0-52).
 *
 * <p>The only way request paths ({@code UserService}, {@code ResendInviteService},
 * {@code ForgotPasswordService}) put anything into the auth email outbox, and
 * all they do is INSERT: no token is created here and no existing row is
 * touched. {@code AuthEmailScheduler} creates the token when it sends the
 * email, and marks an older request SUPERSEDED when a newer one for the same
 * user and type exists. A request therefore never contends with the scheduler
 * over a row — the cause of the 409 / 500 answers resend-invite and
 * forgot-password could give while a FAILED entry was being retried.
 */
@Service
public class AuthEmailRequestService {

    private final AuthEmailOutboxRepository outboxRepository;

    public AuthEmailRequestService(AuthEmailOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /** Queues an invite email; part of the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthEmailOutbox requestInvite(User user) {
        return request(user, AuthEmailType.INVITE);
    }

    /** Queues a password-reset email; part of the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthEmailOutbox requestPasswordReset(User user) {
        return request(user, AuthEmailType.PASSWORD_RESET);
    }

    private AuthEmailOutbox request(User user, AuthEmailType type) {
        return outboxRepository.save(AuthEmailOutbox.request(
                user, type, AuthTokenService.emailTokenLifetime(type.tokenType())));
    }
}
