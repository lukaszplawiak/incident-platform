package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.LoginRequest;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.ratelimit.LoginAttemptService;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid credentials";

    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final AuthTokenService authTokenService;
    private final AuditEventPublisher auditEventPublisher;

    public AuthService(UserRepository userRepository,
                       JwtUtils jwtUtils,
                       LoginAttemptService loginAttemptService,
                       AuthTokenService authTokenService,
                       PasswordEncoder passwordEncoder,
                       AuditEventPublisher auditEventPublisher) {
        this.userRepository      = userRepository;
        this.jwtUtils            = jwtUtils;
        this.loginAttemptService = loginAttemptService;
        this.authTokenService    = authTokenService;
        this.passwordEncoder     = passwordEncoder;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        final String tenantId = TenantContext.get();
        final String email = request.email();

        // ── 1. Check lockout BEFORE any DB query or BCrypt ────────────────
        // Checking first prevents timing attacks: if we checked the password
        // first, an attacker could use timing differences to enumerate valid
        // emails even when locked out.
        if (loginAttemptService.isLocked(email, tenantId)) {
            final Duration remaining =
                    loginAttemptService.getRemainingLockout(email, tenantId);
            log.warn("Login rejected — account locked: email={}, tenant={}, " +
                    "remainingSeconds={}", email, tenantId, remaining.toSeconds());
            throw new BusinessException(
                    ErrorCodes.UNAUTHORIZED,
                    String.format("Account locked. Try again in %d minutes.",
                            remaining.toMinutes() + 1),
                    HttpStatus.UNAUTHORIZED
            );
        }

        // ── 2. Find user ───────────────────────────────────────────────────
        // Same error message for "not found", "inactive", "OAuth2-only" —
        // prevents user enumeration. Failure is recorded in all three cases.
        final User user = userRepository
                .findByEmailAndTenantId(email, tenantId)
                .filter(User::isActive)
                .filter(u -> u.getPasswordHash() != null)
                .orElseGet(() -> {
                    loginAttemptService.recordFailure(email, tenantId);
                    log.warn("Login failed — user not found, inactive, or " +
                            "OAuth2-only: email={}, tenant={}", email, tenantId);
                    return null;
                });

        if (user == null) {
            throw invalidCredentials();
        }

        // ── 3. Verify password ─────────────────────────────────────────────
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptService.recordFailure(email, tenantId);
            log.warn("Login failed — wrong password: email={}, tenant={}",
                    email, tenantId);
            throw invalidCredentials();
        }

        // ── 4. Success — clear failure counter ─────────────────────────────
        loginAttemptService.recordSuccess(email, tenantId);

        final String accessToken = jwtUtils.generateToken(
                user.getId(), tenantId,
                user.getEmail(), user.getRoleNames());

        final Instant accessExpiresAt = Instant.now()
                .plus(jwtUtils.getAccessTokenTtl());

        // Generate refresh token — stored as SHA-256 hash in auth_tokens.
        // Raw token returned once to client; never logged.
        final String rawRefreshToken =
                authTokenService.generateRefreshToken(user, tenantId);

        final Instant refreshExpiresAt = Instant.now()
                .plus(jwtUtils.getRefreshTokenTtl());

        log.info("Login successful: email={}, tenant={}, roles={}",
                user.getEmail(), tenantId, user.getRoleNames());

        auditEventPublisher.publishAuth(
                user.getId(), tenantId,
                AuditEventTypes.USER_LOGIN,
                "auth-service",
                user.getId().toString(),
                "User logged in",
                java.util.Map.of("email", user.getEmail(),
                        "roles", user.getRoleNames()));

        return new LoginResponse(
                accessToken,
                rawRefreshToken,
                user.getId(),
                tenantId,
                user.getEmail(),
                user.getRoleNames(),
                accessExpiresAt,
                refreshExpiresAt
        );
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(
                ErrorCodes.UNAUTHORIZED,
                INVALID_CREDENTIALS_MESSAGE,
                HttpStatus.UNAUTHORIZED
        );
    }
}