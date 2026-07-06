package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.domain.UserRole;
import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static com.incidentplatform.auth.service.AuthTokenService.INVITE_TTL_HOURS;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final AuthTokenService authTokenService;

    public UserService(UserRepository userRepository,
                       AuthTokenService authTokenService) {
        this.userRepository = userRepository;
        this.authTokenService = authTokenService;
    }

    /**
     * Creates a new user and generates an invite token.
     *
     * <p>The invite token is returned in the response — the admin must
     * securely forward it to the new user so they can call
     * {@code POST /api/v1/auth/accept-invite} to set their password.
     * The token expires after {@link AuthTokenService#INVITE_TTL_HOURS} hours.
     *
     * <p>Once email infrastructure is available, this method will send the
     * token directly by email and remove it from the response.
     *
     * @throws BusinessException 409 if the email already exists in this tenant
     */
    @Transactional
    public CreateUserResponse createUser(CreateUserRequest request) {
        final String tenantId = TenantContext.get();

        // Guard: prevent duplicate email within tenant
        userRepository.findByEmailAndTenantIdAndDeletedAtIsNull(request.email(), tenantId)
                .ifPresent(existing -> {
                    log.warn("User creation failed — email already exists: " +
                            "email={}, tenant={}", request.email(), tenantId);
                    throw new BusinessException(
                            ErrorCodes.EMAIL_ALREADY_EXISTS,
                            String.format(
                                    "A user with email '%s' already exists " +
                                            "in this tenant", request.email()),
                            HttpStatus.CONFLICT
                    );
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

        // Generate invite token — returned in response until email is available
        final String inviteToken =
                authTokenService.generateInviteToken(user, tenantId);

        final Instant inviteExpiresAt =
                Instant.now().plusSeconds(INVITE_TTL_HOURS * 3600L);

        return new CreateUserResponse(
                user.getId(),
                tenantId,
                user.getEmail(),
                user.getRoleNames(),
                user.isActive(),
                user.getCreatedAt(),
                inviteToken,
                inviteExpiresAt
        );
    }
}