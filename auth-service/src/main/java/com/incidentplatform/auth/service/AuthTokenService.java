package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
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
 *   <li>INVITE: {@value #INVITE_TTL_HOURS} hours — enough time for the
 *       invited user to check their inbox and complete registration.</li>
 *   <li>PASSWORD_RESET: {@value #RESET_TTL_MINUTES} minutes — short window
 *       minimises exposure if the email is intercepted.</li>
 * </ul>
 */
@Service
public class AuthTokenService {

    private static final Logger log =
            LoggerFactory.getLogger(AuthTokenService.class);

    static final int INVITE_TTL_HOURS = 72;
    static final int RESET_TTL_MINUTES = 15;

    private static final int TOKEN_BYTES = 32;

    private final AuthTokenRepository tokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthTokenService(AuthTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
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