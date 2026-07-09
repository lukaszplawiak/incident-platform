package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.InviteEmailOutbox;
import com.incidentplatform.auth.domain.InviteEmailStatus;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.InviteEmailOutboxRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.AuthTokenService.InviteTokenResult;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Handles the resend-invite flow — regenerates an invite token and
 * schedules a new invite email for a user who has not yet accepted
 * their invitation.
 *
 * <h2>When resend is needed</h2>
 * <ul>
 *   <li>The original invite email was never delivered (PERMANENTLY_FAILED
 *       after 3 SMTP failures)</li>
 *   <li>The original invite link expired (7-day TTL)</li>
 *   <li>The user lost the email and needs a fresh link</li>
 * </ul>
 *
 * <h2>What resend does</h2>
 * <ol>
 *   <li>Validates that the user exists in this tenant and has not yet
 *       set a password (invite still pending)</li>
 *   <li>Invalidates all existing valid INVITE tokens for this user
 *       — only one invite link should be active at a time</li>
 *   <li>Generates a fresh INVITE token (new 7-day TTL)</li>
 *   <li>Creates a new PENDING outbox entry — {@code InviteEmailScheduler}
 *       sends the email within 30 seconds</li>
 * </ol>
 *
 * <h2>Idempotency guard</h2>
 * If the user already has a PENDING outbox entry (email not yet dispatched),
 * resend is rejected with 409 — there is no point creating a duplicate.
 * The admin should wait for the scheduler to process the existing entry.
 */
@Service
public class ResendInviteService {

    private static final Logger log =
            LoggerFactory.getLogger(ResendInviteService.class);

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final InviteEmailOutboxRepository outboxRepository;
    private final AuthTokenService authTokenService;

    public ResendInviteService(UserRepository userRepository,
                               AuthTokenRepository authTokenRepository,
                               InviteEmailOutboxRepository outboxRepository,
                               AuthTokenService authTokenService) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.outboxRepository = outboxRepository;
        this.authTokenService = authTokenService;
    }

    /**
     * Resends an invite email to the specified user.
     *
     * @param userId the UUID of the user to re-invite
     * @throws ResourceNotFoundException 404 if user not found in this tenant
     * @throws BusinessException         409 if invite already accepted
     *                                   (user already has a password)
     * @throws BusinessException         409 if a PENDING outbox entry already
     *                                   exists (email dispatch in progress)
     */
    @Transactional
    public void resendInvite(UUID userId) {
        final String tenantId = TenantContext.get();

        final User user = userRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User", userId.toString()));

        // Guard: cannot resend to a user who already accepted the invite
        if (user.getPasswordHash() != null) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Cannot resend invite — user has already accepted " +
                            "the original invitation and set a password",
                    HttpStatus.CONFLICT);
        }

        // Guard: no point resending if there's already a PENDING entry
        // (scheduler will send it within 30 seconds)
        outboxRepository.findLatestByUserId(userId).ifPresent(latest -> {
            if (latest.getStatus() == InviteEmailStatus.PENDING) {
                throw new BusinessException(
                        ErrorCodes.BUSINESS_RULE_VIOLATION,
                        "An invite email is already queued for dispatch — " +
                                "please wait up to 30 seconds before retrying",
                        HttpStatus.CONFLICT);
            }
        });

        // Invalidate all existing valid INVITE tokens so only one invite
        // link is active at a time. Prevents confusion if the user
        // receives both the old and new email.
        final List<AuthToken> existingTokens =
                authTokenRepository.findValidByUserIdAndType(
                        userId, AuthToken.Type.INVITE, Instant.now());

        for (final AuthToken token : existingTokens) {
            token.markUsed();
            authTokenRepository.save(token);
        }

        if (!existingTokens.isEmpty()) {
            log.info("Invalidated {} existing INVITE token(s) before resend: " +
                            "userId={}, tenant={}",
                    existingTokens.size(), userId, tenantId);
        }

        // Generate fresh token with new 7-day TTL
        final InviteTokenResult tokenResult =
                authTokenService.generateInviteTokenWithEntity(user, tenantId);

        // Write new PENDING outbox entry — scheduler sends within 30s
        final InviteEmailOutbox outboxEntry = InviteEmailOutbox.pending(
                user, tokenResult.token(), tokenResult.rawToken());
        outboxRepository.save(outboxEntry);

        log.info("Invite resend queued: userId={}, email={}, tenant={}",
                userId, user.getEmail(), tenantId);
    }
}