package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.domain.UserRole;
import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.AuthTokenService.InviteTokenResult;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final AuthTokenService authTokenService;
    private final AuthEmailOutboxRepository outboxRepository;

    public UserService(UserRepository userRepository,
                       AuthTokenService authTokenService,
                       AuthEmailOutboxRepository outboxRepository) {
        this.userRepository = userRepository;
        this.authTokenService = authTokenService;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Creates a new user and schedules an invite email via the Outbox Pattern.
     *
     * <h2>What happens in this transaction</h2>
     * <ol>
     *   <li>Duplicate email guard — 409 if email already exists in tenant</li>
     *   <li>INSERT user (no password — set later via accept-invite)</li>
     *   <li>Generate invite token — raw token + SHA-256 hash saved to auth_tokens</li>
     *   <li>INSERT invite_email_outbox (PENDING) — raw token stored temporarily</li>
     *   <li>COMMIT — all three records written atomically</li>
     * </ol>
     *
     * <h2>What does NOT happen here</h2>
     * No email is sent in this method. {@code InviteEmailScheduler} picks up
     * the PENDING outbox entry (typically within 30 seconds) and sends the
     * email with the invite link. The raw token is NULLed from the outbox
     * after successful dispatch.
     *
     * <h2>Response</h2>
     * The response no longer contains {@code inviteToken} — the token goes
     * directly to the user's inbox, never through the admin's HTTP client.
     *
     * @throws BusinessException 409 if the email already exists in this tenant
     */
    @Transactional
    public CreateUserResponse createUser(CreateUserRequest request) {
        final String tenantId = TenantContext.get();

        // Guard: prevent duplicate email within tenant
        userRepository.findByEmailAndTenantIdAndDeletedAtIsNull(
                        request.email(), tenantId)
                .ifPresent(existing -> {
                    log.warn("User creation failed — email already exists: " +
                            "email={}, tenant={}", request.email(), tenantId);
                    throw new BusinessException(
                            ErrorCodes.EMAIL_ALREADY_EXISTS,
                            String.format(
                                    "A user with email '%s' already exists " +
                                            "in this tenant", request.email()),
                            HttpStatus.CONFLICT);
                });

        // Create user — no password yet (set via accept-invite)
        final User user = User.register(tenantId, request.email());

        // Assign roles
        for (final String role : request.roles()) {
            user.getRoles().add(UserRole.grant(user, tenantId, role));
        }

        userRepository.save(user);

        log.info("User created: userId={}, email={}, tenant={}, roles={}",
                user.getId(), user.getEmail(), tenantId, request.roles());

        // Generate invite token — returns both rawToken and the saved entity.
        // We need the entity for the outbox FK and the rawToken for the email link.
        final InviteTokenResult tokenResult =
                authTokenService.generateInviteTokenWithEntity(user, tenantId);

        // Write outbox entry — rawToken stored temporarily until scheduler sends email.
        // InviteEmailScheduler will null raw_token after successful dispatch.
        final AuthEmailOutbox outboxEntry = AuthEmailOutbox.invitePending(
                user, tokenResult.token(), tokenResult.rawToken());
        outboxRepository.save(outboxEntry);

        log.info("Invite email queued: userId={}, email={}, tenant={}",
                user.getId(), user.getEmail(), tenantId);

        // Return response WITHOUT the token — it goes directly to user's inbox.
        return new CreateUserResponse(
                user.getId(),
                tenantId,
                user.getEmail(),
                user.getRoleNames(),
                user.isActive(),
                user.getCreatedAt()
        );
    }
}