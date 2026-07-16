package com.incidentplatform.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * JWT utility — generates and validates JSON Web Tokens for both human
 * operators (access tokens) and inter-service calls (service tokens).
 *
 * <h2>Configuration</h2>
 * All configuration is sourced from {@link JwtProperties} which is bound
 * via {@code @ConfigurationProperties(prefix = "jwt")} and validated at
 * startup. No {@code @Value} annotations remain in this class.
 *
 * <h2>Token types</h2>
 * <ul>
 *   <li><b>Access token</b> — issued on login, carried by human operators in
 *       the {@code Authorization: Bearer} header. Short TTL
 *       ({@code jwt.access-token-ttl}, default PT15M).</li>
 *   <li><b>Service token</b> — issued internally by {@link ServiceTokenProvider}
 *       for inter-service calls. Longer TTL
 *       ({@code jwt.service-token-ttl}, default PT1H).</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <ul>
 *   <li>HMAC-SHA512 signing — requires a secret of at least 64 bytes (512 bits)</li>
 *   <li>Each token contains a unique {@code jti} (JWT ID) used for revocation</li>
 *   <li>Claims: {@code sub} (userId or serviceName), {@code tenantId},
 *       {@code email}, {@code roles}, {@code iat}, {@code exp}, {@code jti}</li>
 * </ul>
 */
@Component
public class JwtUtils {

    private static final Logger log = LoggerFactory.getLogger(JwtUtils.class);

    public static final String CLAIM_TENANT_ID   = "tenantId";
    public static final String CLAIM_ROLES        = "roles";
    public static final String CLAIM_EMAIL        = "email";
    public static final String CLAIM_SERVICE_NAME = "serviceName";
    public static final String CLAIM_TEAM_IDS     = "teamIds";

    private static final int MIN_SECRET_BYTES = 64;

    private static final Duration MIN_ACCESS_TOKEN_TTL  = Duration.ofMinutes(1);
    private static final Duration MAX_ACCESS_TOKEN_TTL  = Duration.ofHours(24);
    private static final Duration MIN_SERVICE_TOKEN_TTL  = Duration.ofMinutes(10);
    private static final Duration MAX_SERVICE_TOKEN_TTL  = Duration.ofHours(24);
    private static final Duration MIN_REFRESH_TOKEN_TTL  = Duration.ofDays(1);
    private static final Duration MAX_REFRESH_TOKEN_TTL  = Duration.ofDays(365);

    private final SecretKey secretKey;
    private final JwtProperties properties;

    /**
     * Constructs JwtUtils from validated {@link JwtProperties}.
     *
     * <p>Previously this constructor received three {@code @Value}-injected
     * primitives ({@code String secret}, {@code long expirationMs},
     * {@code Duration serviceExpiration}). It now receives a single
     * {@link JwtProperties} record — all JWT config in one place, with
     * type-safety and Bean Validation applied before construction.
     *
     * <p>Secret byte-length validation is still done here because
     * {@code @Size} in Bean Validation counts characters, not bytes —
     * a non-ASCII secret can have fewer bytes than characters.
     */
    public JwtUtils(JwtProperties properties) {
        final byte[] secretBytes =
                properties.secret().getBytes(StandardCharsets.UTF_8);

        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "jwt.secret must be at least 64 bytes (512 bits) for HS512. " +
                            "Character count: " + properties.secret().length() +
                            ", byte count: " + secretBytes.length + ". " +
                            "Generate with: openssl rand -base64 64");
        }

        this.secretKey  = Keys.hmacShaKeyFor(secretBytes);
        this.properties = properties;
    }

    /**
     * Validates that both token TTLs are within sensible ranges.
     * Fails fast at startup — a misconfigured TTL causes an
     * {@link IllegalArgumentException} before any request is processed.
     *
     * <p>Duration range constraints require a third-party library dependency
     * (e.g. {@code @DurationMin}/{@code @DurationMax} from Hibernate Validator
     * extras), which is not justified for two constraints in {@code shared}.
     * This explicit {@code @PostConstruct} method is the library-free alternative.
     */
    @PostConstruct
    void validateConfiguration() {
        validateTtl("jwt.access-token-ttl", properties.accessTokenTtl(),
                MIN_ACCESS_TOKEN_TTL, MAX_ACCESS_TOKEN_TTL);
        validateTtl("jwt.service-token-ttl", properties.serviceTokenTtl(),
                MIN_SERVICE_TOKEN_TTL, MAX_SERVICE_TOKEN_TTL);
        validateTtl("jwt.refresh-token-ttl", properties.refreshTokenTtl(),
                MIN_REFRESH_TOKEN_TTL, MAX_REFRESH_TOKEN_TTL);

        log.info("JwtUtils initialised — accessTokenTtl={}, serviceTokenTtl={}, "
                        + "refreshTokenTtl={}",
                properties.accessTokenTtl(), properties.serviceTokenTtl(),
                properties.refreshTokenTtl());
    }

    // ── token generation ──────────────────────────────────────────────────

    /**
     * Generates an access token for a human operator.
     * TTL controlled by {@code jwt.access-token-ttl} (default PT15M).
     */
    public String generateToken(UUID userId, String tenantId,
                                String email, List<String> roles,
                                List<UUID> teamIds) {
        final Instant now        = Instant.now();
        final Instant expiration = now.plus(properties.accessTokenTtl());

        final String token = Jwts.builder()
                .id(UUID.randomUUID().toString())   // jti — unique ID for revocation
                .subject(userId.toString())
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_TEAM_IDS, teamIds.stream()
                        .map(java.util.UUID::toString)
                        .toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();

        // DEBUG not INFO — token generation happens on every login;
        // INFO would flood logs in systems with many active users.
        log.debug("Access token generated: userId={}, tenantId={}, expiresAt={}",
                userId, tenantId, expiration);

        return token;
    }

    /**
     * Generates a service token for inter-service authentication.
     * TTL controlled by {@code jwt.service-token-ttl} (default PT1H).
     */
    public String generateServiceToken(String serviceName) {
        final Instant now        = Instant.now();
        final Instant expiration = now.plus(properties.serviceTokenTtl());

        final String token = Jwts.builder()
                .subject(serviceName)
                .claim(CLAIM_SERVICE_NAME, serviceName)
                .claim(CLAIM_ROLES, List.of(SecurityRoles.ROLE_SERVICE))
                .claim(CLAIM_TENANT_ID, "system")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();

        log.debug("Service token generated: service={}, expiresAt={}",
                serviceName, expiration);

        return token;
    }

    // ── token TTL accessors ───────────────────────────────────────────────

    /**
     * Returns the access token TTL.
     *
     * <p>Used by {@link com.incidentplatform.auth.service.AuthService} to
     * compute {@code expiresAt} in {@code LoginResponse}. This method did
     * not exist before this refactor — {@code AuthService} mistakenly called
     * {@link #getServiceExpirationMs()} (which returned the service token TTL,
     * PT1H) instead of the access token TTL, causing {@code LoginResponse} to
     * report incorrect expiry to clients. This bug is now impossible because
     * the two TTLs have distinct, correctly named accessor methods.
     */
    public Duration getAccessTokenTtl() {
        return properties.accessTokenTtl();
    }

    /**
     * Returns the service token TTL.
     * Used by {@link ServiceTokenProvider} to compute token refresh timing.
     */
    public Duration getServiceTokenTtl() {
        return properties.serviceTokenTtl();
    }

    /**
     * Returns the refresh token TTL.
     * Used by {@link com.incidentplatform.auth.service.AuthTokenService}
     * to set the expiry on newly generated refresh tokens.
     */
    public Duration getRefreshTokenTtl() {
        return properties.refreshTokenTtl();
    }


    // ── claims extraction ─────────────────────────────────────────────────

    public Optional<Claims> validateAndGetClaims(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        try {
            final Claims claims = Jwts.parser()
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


    /**
     * Extracts team UUIDs from the {@code teamIds} JWT claim.
     * Returns an empty list if the claim is absent (e.g. tokens issued
     * before team support was added).
     */
    @SuppressWarnings("unchecked")
    public List<java.util.UUID> extractTeamIds(Claims claims) {
        final List<String> raw = claims.get(CLAIM_TEAM_IDS, List.class);
        if (raw == null) return List.of();
        return raw.stream()
                .map(java.util.UUID::fromString)
                .toList();
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

    /**
     * Extracts the JWT ID (jti claim) — unique identifier per token.
     * Used for token revocation: revoked JTIs are stored in Redis until
     * the token's natural expiry, after which Redis TTL cleans them up automatically.
     */
    public Optional<String> extractJti(Claims claims) {
        return Optional.ofNullable(claims.getId());
    }

    public Optional<Date> extractExpiration(Claims claims) {
        return Optional.ofNullable(claims.getExpiration());
    }

    public Optional<String> extractEmail(Claims claims) {
        return Optional.ofNullable(claims.get(CLAIM_EMAIL, String.class));
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(Claims claims) {
        final Object rolesObj = claims.get(CLAIM_ROLES);
        if (rolesObj instanceof List<?> rolesList) {
            return rolesList.stream()
                    .filter(r -> r instanceof String)
                    .map(r -> (String) r)
                    .toList();
        }
        return List.of();
    }

    // ── private ───────────────────────────────────────────────────────────

    private void validateTtl(String propertyName, Duration value,
                             Duration min, Duration max) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(String.format(
                    "%s must be between %s and %s, got: %s. " +
                            "Use ISO-8601 format, e.g. PT15M for 15 minutes, P30D for 30 days.",
                    propertyName, min, max, value));
        }
    }
}