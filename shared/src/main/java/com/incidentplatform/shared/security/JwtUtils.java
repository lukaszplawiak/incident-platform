package com.incidentplatform.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtUtils {

    private static final Logger log = LoggerFactory.getLogger(JwtUtils.class);

    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_SERVICE_NAME = "serviceName";

    private static final int MIN_SECRET_LENGTH = 64;

    /**
     * Minimum sensible service token lifetime — shorter tokens are
     * effectively useless since ServiceTokenProvider refreshes 5 min before expiry.
     */
    private static final Duration MIN_SERVICE_EXPIRATION = Duration.ofMinutes(10);

    /**
     * Maximum allowed service token lifetime. Tokens valid longer than 24h increase
     * the blast radius of a stolen token.
     */
    private static final Duration MAX_SERVICE_EXPIRATION = Duration.ofHours(24);

    private final SecretKey secretKey;
    private final long expirationMs;
    private final Duration serviceExpiration;

    public JwtUtils(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:86400000}") long expirationMs,
            // ISO-8601 duration string (e.g. PT1H, PT30M).
            // Default: PT1H (1 hour) — limits stolen-token exposure.
            @Value("${jwt.service-expiration:PT1H}") Duration serviceExpiration) {

        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least 64 characters (512 bits) for HS512. " +
                            "Generate with: openssl rand -base64 64"
            );
        }

        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.serviceExpiration = serviceExpiration;
    }

    /**
     * Validates that {@code jwt.service-expiration} is within the sensible
     * range [10min .. 24h]. Fails fast at startup rather than silently using
     * an absurd value (e.g. PT8Y from a copy-paste error or missing time unit).
     */
    @PostConstruct
    void validateServiceExpiration() {
        if (serviceExpiration.compareTo(MIN_SERVICE_EXPIRATION) < 0
                || serviceExpiration.compareTo(MAX_SERVICE_EXPIRATION) > 0) {
            throw new IllegalArgumentException(String.format(
                    "jwt.service-expiration must be between %s and %s, got: %s. " +
                            "Use ISO-8601 format, e.g. PT1H for 1 hour.",
                    MIN_SERVICE_EXPIRATION, MAX_SERVICE_EXPIRATION,
                    serviceExpiration));
        }
        log.info("JwtUtils initialised — user token expiration: {}ms, " +
                "service token expiration: {}", expirationMs, serviceExpiration);
    }

    public String generateToken(UUID userId, String tenantId,
                                String email, List<String> roles) {
        final Instant now        = Instant.now();
        final Instant expiration = now.plusMillis(expirationMs);

        final String token = Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLES, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();

        // DEBUG not INFO — token generation happens on every login;
        // INFO would flood logs in a system with many active users.
        log.debug("JWT token generated for userId={}, tenantId={}, expiresAt={}",
                userId, tenantId, expiration);

        return token;
    }

    public String generateServiceToken(String serviceName) {
        final Instant now        = Instant.now();
        final Instant expiration = now.plus(serviceExpiration);

        final String token = Jwts.builder()
                .subject(serviceName)
                .claim(CLAIM_SERVICE_NAME, serviceName)
                .claim(CLAIM_ROLES, List.of(SecurityRoles.ROLE_SERVICE))
                .claim(CLAIM_TENANT_ID, "system")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();

        log.debug("Service JWT token generated: service={}, expiresAt={}",
                serviceName, expiration);
        return token;
    }

    /**
     * Returns the service token expiration as milliseconds.
     * Used by {@link ServiceTokenProvider} and AlertManagerTokenRefresher
     * so expiration is defined in one place only.
     */
    public long getServiceExpirationMs() {
        return serviceExpiration.toMillis();
    }

    public Optional<Claims> validateAndGetClaims(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.of(claims);

        } catch (ExpiredJwtException e) {
            log.warn("JWT token expired for subject: {}", e.getClaims().getSubject());
            return Optional.empty();

        } catch (JwtException e) {
            log.warn("Invalid JWT token detected: {}", e.getMessage());
            return Optional.empty();

        } catch (Exception e) {
            log.error("Unexpected error during JWT validation", e);
            return Optional.empty();
        }
    }

    public Optional<String> extractTenantId(Claims claims) {
        return Optional.ofNullable(claims.get(CLAIM_TENANT_ID, String.class));
    }

    public Optional<UUID> extractUserId(Claims claims) {
        try {
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid userId format in JWT subject: {}", claims.getSubject());
            return Optional.empty();
        }
    }

    public Optional<String> extractEmail(Claims claims) {
        return Optional.ofNullable(claims.get(CLAIM_EMAIL, String.class));
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(Claims claims) {
        Object rolesObj = claims.get(CLAIM_ROLES);
        if (rolesObj instanceof List<?> rolesList) {
            return rolesList.stream()
                    .filter(r -> r instanceof String)
                    .map(r -> (String) r)
                    .toList();
        }
        return List.of();
    }
}