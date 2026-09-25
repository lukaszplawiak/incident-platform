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
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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
 *   <li><b>Purpose token</b> — a service token for one operation that acts for
 *       no tenant ({@link #generatePurposeToken}, backlog #0-16).</li>
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
    public static final String CLAIM_MANAGED_TEAM_IDS = "managedTeamIds";
    public static final String CLAIM_SESSION_ID   = "sessionId";
    public static final String CLAIM_PURPOSE      = "purpose";

    /**
     * Shape accepted for the tenant id of a <em>service</em> token: 1-100
     * characters, no whitespace or control characters. Tenant ids have no
     * other format in this codebase (they are free-form strings), so this
     * deliberately does not invent one; it only keeps a tenant id that came
     * from a Kafka payload from carrying newlines into log lines or an absurd
     * length into a signed claim and an outbound header.
     */
    private static final Pattern SERVICE_TENANT_ID_PATTERN =
            Pattern.compile("^[^\\p{Cntrl}\\s]{1,100}$", Pattern.UNICODE_CHARACTER_CLASS);

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
     * Generates an access token for a human operator, with no associated
     * login session — see {@link #generateToken(UUID, String, String,
     * List, List, List, UUID)}'s own Javadoc for when a real
     * {@code sessionId} is needed instead. Used by test code and
     * {@code DevTokenController} (a local-development-only tool that
     * issues standalone test tokens with no real login, and therefore no
     * real session, behind them).
     */
    public String generateToken(UUID userId, String tenantId,
                                String email, List<String> roles,
                                List<UUID> teamIds, List<UUID> managedTeamIds) {
        return generateToken(userId, tenantId, email, roles,
                teamIds, managedTeamIds, null);
    }

    /**
     * Generates an access token for a human operator.
     * TTL controlled by {@code jwt.access-token-ttl} (default PT15M).
     *
     * @param managedTeamIds subset of {@code teamIds} where the user holds
     *                       {@code TeamRole.MANAGER} — added for the Manager
     *                       role feature, embedded as its own claim so
     *                       {@code oncall-service}/{@code auth-service} can
     *                       authorize team-scoped actions without a
     *                       cross-service call back to auth-service's DB.
     * @param sessionId links this access token to the {@code AuthToken}
     *                  (REFRESH type) issued at the same login — see
     *                  migration V16's own comment (auth-service) for the
     *                  full account. Pass the same {@code sessionId} used
     *                  when generating that refresh token
     *                  ({@code AuthTokenService.generateRefreshToken}), or
     *                  the one carried forward by
     *                  {@code AuthTokenService.rotateRefreshToken}. Null
     *                  for tokens with no associated session — see the
     *                  6-argument overload of this method.
     */
    public String generateToken(UUID userId, String tenantId,
                                String email, List<String> roles,
                                List<UUID> teamIds, List<UUID> managedTeamIds,
                                UUID sessionId) {
        final Instant now        = Instant.now();
        final Instant expiration = now.plus(properties.accessTokenTtl());

        final var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())   // jti — unique ID for revocation
                .subject(userId.toString())
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_TEAM_IDS, teamIds.stream()
                        .map(java.util.UUID::toString)
                        .toList())
                .claim(CLAIM_MANAGED_TEAM_IDS, managedTeamIds.stream()
                        .map(java.util.UUID::toString)
                        .toList());

        if (sessionId != null) {
            builder.claim(CLAIM_SESSION_ID, sessionId.toString());
        }

        final String token = builder
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
     * Generates a service token for inter-service authentication, acting
     * for one tenant and valid for one target service. TTL controlled by
     * {@code jwt.service-token-ttl} (default PT1H).
     *
     * <h2>Fixed (backlog #0-11): the tenant and the audience are parameters</h2>
     * The previous one-argument overload hard-coded {@code tenantId =
     * "system"}, and {@link JwtAuthFilter} rejected the token anyway (see
     * {@link ServicePrincipal}). Every tenant-scoped query on the receiving
     * side is filtered by the tenant from the token, so a call for a real
     * tenant needs that tenant in the signed claim — not in a header, which
     * no filter reads. A call that genuinely acts for no tenant uses a purpose
     * token instead ({@link #generatePurposeToken}, backlog #0-16); the
     * "system" tenant constant the old Alertmanager token used is gone.
     *
     * <p>The {@code aud} claim names the service the token is for, and
     * {@link JwtAuthFilter} accepts a service token only where {@code aud}
     * matches its own service name (see {@link ServiceNames}). Without it a
     * token minted to call oncall-service would also authenticate on every
     * other service that shares the secret.
     *
     * <p>Known limitation, tracked as backlog #0-13: every service holds
     * the same HMAC secret, so any service can mint a token for any
     * tenant and any audience. Per-tenant, per-audience tokens stop a
     * forged header or a token replayed against the wrong service; they do
     * not stop a compromised service.
     *
     * @throws IllegalArgumentException if any argument is blank, or if
     *         {@code tenantId} fails {@link #requireValidServiceTenantId}
     */
    public String generateServiceToken(String serviceName, String tenantId,
                                       String audience) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("audience must not be blank");
        }
        requireValidServiceTenantId(tenantId);

        final Instant now        = Instant.now();
        final Instant expiration = now.plus(properties.serviceTokenTtl());

        final String token = Jwts.builder()
                .subject(serviceName)
                .audience().add(audience).and()
                .claim(CLAIM_SERVICE_NAME, serviceName)
                .claim(CLAIM_ROLES, List.of(SecurityRoles.ROLE_SERVICE))
                .claim(CLAIM_TENANT_ID, tenantId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();

        log.debug("Service token generated: service={}, audience={}, " +
                "tenantId={}, expiresAt={}", serviceName, audience, tenantId, expiration);

        return token;
    }

    /**
     * Generates a purpose-scoped service token (backlog #0-16): valid for one
     * operation ({@code purpose}, one of {@link TokenPurposes}) on one target
     * service ({@code aud}), acting for <em>no</em> tenant. TTL is
     * {@code jwt.service-token-ttl}, like other service tokens.
     *
     * <p>It deliberately has neither a {@code tenantId}, a {@code serviceName}
     * nor a {@code roles} claim, so {@link JwtAuthFilter} can never take it
     * through the service-token or the user branch: it is authenticated only
     * by the purpose branch, only in a service that accepts that purpose, and
     * becomes an {@link IntrospectionPrincipal}. The caller is in {@code sub}.
     * It carries a {@code jti}, so it is revocable once revocation is checked
     * outside auth-service (backlog #0-3).
     *
     * @throws IllegalArgumentException if any argument is blank
     */
    public String generatePurposeToken(String serviceName, String purpose,
                                       String audience) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("purpose must not be blank");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("audience must not be blank");
        }

        final Instant now        = Instant.now();
        final Instant expiration = now.plus(properties.serviceTokenTtl());

        final String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(serviceName)
                .audience().add(audience).and()
                .claim(CLAIM_PURPOSE, purpose)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();

        log.debug("Purpose token generated: service={}, purpose={}, audience={}, " +
                "expiresAt={}", serviceName, purpose, audience, expiration);

        return token;
    }

    /**
     * Extracts the {@code purpose} claim. Present only on purpose-scoped
     * service tokens ({@link #generatePurposeToken}).
     */
    public Optional<String> extractPurpose(Claims claims) {
        final String purpose = claims.get(CLAIM_PURPOSE, String.class);
        return purpose == null || purpose.isBlank()
                ? Optional.empty()
                : Optional.of(purpose);
    }

    /**
     * Rejects a tenant id that must not go into a service token, an
     * {@code X-Tenant-Id} header or a log line: null, blank, longer than 100
     * characters, or containing whitespace or control characters.
     *
     * @throws IllegalArgumentException with a message that does not echo the
     *         rejected value (it may itself contain the offending characters)
     */
    public static void requireValidServiceTenantId(String tenantId) {
        if (tenantId == null || !SERVICE_TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            throw new IllegalArgumentException(
                    "tenantId must be 1-100 characters with no whitespace or " +
                            "control characters — a service token always acts " +
                            "for a valid tenant");
        }
    }

    /**
     * Extracts the {@code aud} claim. Present on service tokens; empty for
     * tokens issued without an audience (user access tokens).
     */
    public Set<String> extractAudience(Claims claims) {
        final Set<String> audience = claims.getAudience();
        return audience == null ? Set.of() : audience;
    }

    /**
     * Extracts the {@code serviceName} claim. Present only on service
     * tokens — this is how {@link JwtAuthFilter} tells a service token
     * from a user token.
     */
    public Optional<String> extractServiceName(Claims claims) {
        final String serviceName = claims.get(CLAIM_SERVICE_NAME, String.class);
        return serviceName == null || serviceName.isBlank()
                ? Optional.empty()
                : Optional.of(serviceName);
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

    /**
     * Extracts team UUIDs from the {@code managedTeamIds} JWT claim — teams
     * where this user holds {@code TeamRole.MANAGER}. Returns an empty list
     * if the claim is absent (e.g. tokens issued before the Manager role
     * feature was added, or service tokens which never carry this claim).
     */
    @SuppressWarnings("unchecked")
    public List<java.util.UUID> extractManagedTeamIds(Claims claims) {
        final List<String> raw = claims.get(CLAIM_MANAGED_TEAM_IDS, List.class);
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

    /**
     * Extracts the {@code sessionId} claim — present only on access
     * tokens generated with a session (see the 7-argument overload of
     * {@link #generateToken}). Empty for tokens with no associated
     * session (service tokens, dev/test tokens, or any access token
     * issued before this claim existed).
     */
    public Optional<UUID> extractSessionId(Claims claims) {
        final String raw = claims.get(CLAIM_SESSION_ID, String.class);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid sessionId format in JWT claim: {}", raw);
            return Optional.empty();
        }
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