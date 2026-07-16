package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.auth.repository.TeamMemberRepository;
import com.incidentplatform.shared.security.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.HexFormat;

/**
 * Generates and validates single-use auth tokens (invite and password reset).
 *
 * <h2>Token design</h2>
 * Raw token: 32 bytes from {@link SecureRandom}, Base64URL-encoded (43 chars).
 * Stored value: SHA-256 hash of the raw token (64 hex chars).
 *
 * <p>Only the hash is persisted — the raw token is returned once in the API
 * response or sent by email. If the database is compromised, the attacker
 * cannot use the hashes directly (pre-image resistance of SHA-256).
 *
 * <h2>Expiry</h2>
 * <ul>
 *   <li>INVITE: {@value #INVITE_TTL_HOURS} hours (7 days) — matches industry
 *       standard (PagerDuty, GitHub) to accommodate users who may not
 *       check their inbox over a weekend or short holiday.</li>
 *   <li>PASSWORD_RESET: {@value #RESET_TTL_MINUTES} minutes — short window
 *       minimises exposure if the email is intercepted.</li>
 * </ul>
 */
@Service
public class AuthTokenService {

    private static final Logger log =
            LoggerFactory.getLogger(AuthTokenService.class);

    static final int INVITE_TTL_HOURS   = 168;
    static final int RESET_TTL_MINUTES  = 15;
    static final int MFA_SESSION_MINUTES = 5;

    private static final int TOKEN_BYTES = 32;

    private final AuthTokenRepository tokenRepository;
    private final JwtUtils jwtUtils;
    private final TeamMemberRepository teamMemberRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthTokenService(AuthTokenRepository tokenRepository,
                            JwtUtils jwtUtils,
                            TeamMemberRepository teamMemberRepository) {
        this.tokenRepository       = tokenRepository;
        this.jwtUtils              = jwtUtils;
        this.teamMemberRepository  = teamMemberRepository;
    }

    /**
     * Generates an invite token for a new user.
     *
     * @return the raw (unhashed) token — returned once, never stored
     */
    @Transactional
    public String generateInviteToken(User user, String tenantId) {
        return generate(user, tenantId, AuthToken.Type.INVITE,
                Duration.ofHours(INVITE_TTL_HOURS));
    }

    /**
     * Generates a password reset token.
     *
     * @return the raw (unhashed) token — sent by email, never stored
     */
    @Transactional
    public String generatePasswordResetToken(User user, String tenantId) {
        return generate(user, tenantId, AuthToken.Type.PASSWORD_RESET,
                Duration.ofMinutes(RESET_TTL_MINUTES));
    }

    /**
     * Validates a token and marks it as used atomically.
     *
     * @throws BusinessException 401 if the token is invalid, expired, or used
     */
    @Transactional
    /**
     * Generates a short-lived MFA session token (5 minutes).
     *
     * <p>Issued after successful password verification when user has MFA
     * enabled. The client must exchange it for a real access token via
     * POST /auth/mfa/verify within 5 minutes.
     *
     * <p>Like all tokens, stored as SHA-256 hash — raw token sent to client
     * once and never persisted in plain text.
     */
    public String generateMfaSessionToken(User user, String tenantId) {
        return generate(user, tenantId, AuthToken.Type.MFA_SESSION,
                Duration.ofMinutes(MFA_SESSION_MINUTES));
    }

    public AuthToken consumeToken(String rawToken, AuthToken.Type expectedType) {
        final String hash = hash(rawToken);

        final AuthToken token = tokenRepository
                .findValidByHashAndType(hash, expectedType, Instant.now())
                .orElseThrow(() -> {
                    log.warn("Invalid or expired {} token attempted",
                            expectedType);
                    return new BusinessException(
                            ErrorCodes.UNAUTHORIZED,
                            "Token is invalid, expired, or already used",
                            HttpStatus.UNAUTHORIZED);
                });

        token.markUsed();
        tokenRepository.save(token);

        log.info("Token consumed: type={}, userId={}, tenant={}",
                expectedType, token.getUser().getId(), token.getTenantId());

        return token;
    }


    /**
     * Generates an invite token and returns both the raw token and the
     * persisted {@link AuthToken} entity.
     *
     * <p>Used by {@code UserService} to write the outbox entry — the outbox
     * needs the {@link AuthToken} entity (for the FK) AND the raw token
     * (to include in the email link). The raw token is stored temporarily
     * in {@code invite_email_outbox.raw_token} and NULLed after dispatch.
     *
     * @return a record containing the raw token and the saved AuthToken entity
     */
    @Transactional
    public InviteTokenResult generateInviteTokenWithEntity(User user,
                                                           String tenantId) {
        final byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        final String rawToken = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);

        final AuthToken token = AuthToken.create(
                user, tenantId, hash(rawToken), AuthToken.Type.INVITE,
                Instant.now().plus(Duration.ofHours(INVITE_TTL_HOURS)));

        tokenRepository.save(token);

        log.info("Auth token generated: type=INVITE, userId={}, tenant={}, " +
                "expiresAt={}", user.getId(), tenantId, token.getExpiresAt());

        return new InviteTokenResult(rawToken, token);
    }

    /**
     * Result of {@link #generateInviteTokenWithEntity} — carries both the
     * raw token (for the email link) and the saved entity (for the outbox FK).
     */
    public record InviteTokenResult(String rawToken, AuthToken token) {}



    /**
     * Generates a password reset token and returns both the raw token and
     * the persisted {@link AuthToken} entity.
     *
     * <p>Analogous to {@link #generateInviteTokenWithEntity} — the outbox
     * needs both the entity (for the FK) and the raw token (for the email link).
     *
     * @return a record containing the raw token and the saved AuthToken entity
     */
    @Transactional
    public InviteTokenResult generatePasswordResetTokenWithEntity(User user,
                                                                  String tenantId) {
        final byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        final String rawToken = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);

        final AuthToken token = AuthToken.create(
                user, tenantId, hash(rawToken), AuthToken.Type.PASSWORD_RESET,
                Instant.now().plus(Duration.ofMinutes(RESET_TTL_MINUTES)));

        tokenRepository.save(token);

        log.info("Auth token generated: type=PASSWORD_RESET, userId={}, tenant={}, " +
                "expiresAt={}", user.getId(), tenantId, token.getExpiresAt());

        return new InviteTokenResult(rawToken, token);
    }

    /**
     * Generates a refresh token for a newly authenticated user.
     *
     * <p>Called by {@code AuthService.login()} alongside
     * {@link com.incidentplatform.shared.security.JwtUtils#generateToken}.
     * The raw token is returned once and must be stored securely by the client
     * (httpOnly cookie or SecureStorage on mobile). Only the SHA-256 hash is
     * persisted in the database.
     *
     * @return the raw (unhashed) refresh token
     */
    @Transactional
    public String generateRefreshToken(User user, String tenantId) {
        return generate(user, tenantId, AuthToken.Type.REFRESH,
                jwtUtils.getRefreshTokenTtl());
    }

    /**
     * Validates a refresh token, invalidates it, and issues a new pair
     * (access token + refresh token).
     *
     * <h2>Rotation</h2>
     * Each call consumes the provided refresh token (sets {@code used_at})
     * and generates a new refresh token with a fresh TTL. This limits the
     * blast radius of a stolen refresh token — using it once alerts the
     * legitimate user on their next refresh attempt (their token is now
     * invalid).
     *
     * @param rawRefreshToken the raw refresh token received from the client
     * @return a {@link RotationResult} containing the new access token,
     *         new raw refresh token, and their expiry times
     * @throws com.incidentplatform.shared.exception.BusinessException
     *         401 if the token is invalid, expired, or already used
     */
    @Transactional
    public RotationResult rotateRefreshToken(String rawRefreshToken) {
        // Consume old token — throws 401 if invalid/expired/used
        final AuthToken oldToken = consumeToken(
                rawRefreshToken, AuthToken.Type.REFRESH);

        final User user        = oldToken.getUser();
        final String tenantId  = oldToken.getTenantId();

        // Load team memberships — must be fresh at rotation time
        final java.util.List<java.util.UUID> teamIds =
                teamMemberRepository.findTeamIdsByUserIdAndTenantId(
                        user.getId(), tenantId);

        // Generate new access token
        final String newAccessToken = jwtUtils.generateToken(
                user.getId(), tenantId,
                user.getEmail(), user.getRoleNames(), teamIds);

        final Instant accessExpiresAt = Instant.now()
                .plus(jwtUtils.getAccessTokenTtl());

        // Generate new refresh token (rotation)
        final String newRawRefreshToken = generate(
                user, tenantId, AuthToken.Type.REFRESH,
                jwtUtils.getRefreshTokenTtl());

        final Instant refreshExpiresAt = Instant.now()
                .plus(jwtUtils.getRefreshTokenTtl());

        log.info("Refresh token rotated: userId={}, tenant={}",
                user.getId(), tenantId);

        return new RotationResult(
                newAccessToken, accessExpiresAt,
                newRawRefreshToken, refreshExpiresAt,
                user);
    }

    /**
     * Invalidates all active refresh tokens for a user.
     * Called by {@code LogoutService} to terminate all sessions.
     */
    @Transactional
    public void invalidateAllRefreshTokens(UUID userId) {
        final List<AuthToken> activeTokens =
                tokenRepository.findValidByUserIdAndType(
                        userId, AuthToken.Type.REFRESH, Instant.now());

        for (final AuthToken token : activeTokens) {
            token.markUsed();
            tokenRepository.save(token);
        }

        if (!activeTokens.isEmpty()) {
            log.info("Invalidated {} refresh token(s) for userId={}",
                    activeTokens.size(), userId);
        }
    }

    /**
     * Result of {@link #rotateRefreshToken} — new access token, new refresh
     * token, and their expiry times, plus the authenticated user context.
     */
    public record RotationResult(
            String accessToken,
            Instant accessExpiresAt,
            String rawRefreshToken,
            Instant refreshExpiresAt,
            User user
    ) {}

    // ── private ───────────────────────────────────────────────────────────

    private String generate(User user, String tenantId,
                            AuthToken.Type type, Duration ttl) {
        final byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        final String rawToken = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);

        final AuthToken token = AuthToken.create(
                user, tenantId, hash(rawToken), type,
                Instant.now().plus(ttl));

        tokenRepository.save(token);

        log.info("Auth token generated: type={}, userId={}, tenant={}, " +
                "expiresAt={}", type, user.getId(), tenantId, token.getExpiresAt());

        return rawToken;
    }

    private String hash(String rawToken) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hashBytes = digest.digest(rawToken.getBytes());
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all Java implementations
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}