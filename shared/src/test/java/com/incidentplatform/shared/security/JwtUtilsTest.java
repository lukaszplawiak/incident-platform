package com.incidentplatform.shared.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtUtils")
class JwtUtilsTest {

    // HS512 requires minimum 64 bytes (512 bits).
    private static final String TEST_SECRET =
            "test-secret-key-minimum-64-characters-long-for-hs512-absolutely-not-for-production";
    private static final Duration ACCESS_TOKEN_TTL  = Duration.ofHours(1);
    private static final Duration SERVICE_TOKEN_TTL = Duration.ofHours(1);

    private JwtUtils jwtUtils;

    /**
     * Factory method — keeps tests readable while encapsulating
     * the JwtProperties record construction.
     */
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private static JwtUtils buildJwtUtils(String secret,
                                          Duration accessTokenTtl,
                                          Duration serviceTokenTtl) {
        return new JwtUtils(new JwtProperties(
                secret, accessTokenTtl, serviceTokenTtl,
                REFRESH_TOKEN_TTL));
    }

    private static JwtUtils buildJwtUtils(String secret,
                                          Duration accessTokenTtl,
                                          Duration serviceTokenTtl,
                                          Duration refreshTokenTtl) {
        return new JwtUtils(new JwtProperties(
                secret, accessTokenTtl, serviceTokenTtl, refreshTokenTtl));
    }

    @BeforeEach
    void setUp() {
        jwtUtils = buildJwtUtils(TEST_SECRET, ACCESS_TOKEN_TTL, SERVICE_TOKEN_TTL);
        jwtUtils.validateConfiguration();
    }

    // ── token generation ──────────────────────────────────────────────────

    @Test
    @DisplayName("should generate non-null token")
    void shouldGenerateNonNullToken() {
        final String token = jwtUtils.generateToken(
                UUID.randomUUID(), "acme-corp", "user@acme.com",
                List.of(SecurityRoles.ROLE_ADMIN));

        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("should generate different tokens for different users")
    void shouldGenerateDifferentTokensForDifferentUsers() {
        final String token1 = jwtUtils.generateToken(
                UUID.randomUUID(), "tenant1", "user1@test.com",
                List.of(SecurityRoles.ROLE_ADMIN));
        final String token2 = jwtUtils.generateToken(
                UUID.randomUUID(), "tenant2", "user2@test.com",
                List.of(SecurityRoles.ROLE_RESPONDER));

        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    @DisplayName("should return claims for valid token")
    void shouldReturnClaimsForValidToken() {
        final String token = jwtUtils.generateToken(
                UUID.randomUUID(), "acme-corp", "user@acme.com",
                List.of(SecurityRoles.ROLE_ADMIN));

        assertThat(jwtUtils.validateAndGetClaims(token)).isPresent();
    }

    @Test
    @DisplayName("should return empty for invalid token")
    void shouldReturnEmptyForInvalidToken() {
        assertThat(jwtUtils.validateAndGetClaims("invalid.token.here")).isEmpty();
    }

    @Test
    @DisplayName("should return empty for token with wrong signature")
    void shouldReturnEmptyForWrongSignature() {
        final JwtUtils other = buildJwtUtils(
                "other-secret-key-minimum-64-characters-long-for-hs512-absolutely-not-prod",
                ACCESS_TOKEN_TTL, SERVICE_TOKEN_TTL);
        final String wrongToken = other.generateToken(
                UUID.randomUUID(), "acme", "user@acme.com", List.of());

        assertThat(jwtUtils.validateAndGetClaims(wrongToken)).isEmpty();
    }

    @Test
    @DisplayName("should return empty for expired token")
    void shouldReturnEmptyForExpiredToken() {
        // Duration.ZERO causes the token to expire immediately
        final JwtUtils expiredUtils = buildJwtUtils(
                TEST_SECRET, Duration.ZERO, SERVICE_TOKEN_TTL);
        final String expiredToken = expiredUtils.generateToken(
                UUID.randomUUID(), "acme", "user@acme.com", List.of());

        assertThat(jwtUtils.validateAndGetClaims(expiredToken)).isEmpty();
    }

    @Test
    @DisplayName("should return empty for null token — null guard prevents JJWT exception")
    void shouldReturnEmptyForNullToken() {
        // Guard added in JwtUtils: null check before calling JJWT parser,
        // which would throw IllegalArgumentException for null/blank input.
        assertThat(jwtUtils.validateAndGetClaims(null)).isEmpty();
    }

    @Test
    @DisplayName("should return empty for blank token — blank guard prevents JJWT exception")
    void shouldReturnEmptyForBlankToken() {
        assertThat(jwtUtils.validateAndGetClaims("   ")).isEmpty();
    }

    // ── claims extraction ─────────────────────────────────────────────────

    @Test
    @DisplayName("should extract userId from claims")
    void shouldExtractUserId() {
        final UUID userId = UUID.randomUUID();
        final String token = jwtUtils.generateToken(
                userId, "acme-corp", "user@acme.com", List.of());
        final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();

        assertThat(jwtUtils.extractUserId(claims)).isPresent().contains(userId);
    }

    @Test
    @DisplayName("should extract tenantId from claims")
    void shouldExtractTenantId() {
        final String token = jwtUtils.generateToken(
                UUID.randomUUID(), "acme-corp", "user@acme.com", List.of());
        final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();

        assertThat(jwtUtils.extractTenantId(claims)).isPresent().contains("acme-corp");
    }

    @Test
    @DisplayName("should extract email from claims")
    void shouldExtractEmail() {
        final String token = jwtUtils.generateToken(
                UUID.randomUUID(), "acme-corp", "user@acme.com", List.of());
        final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();

        assertThat(jwtUtils.extractEmail(claims)).isPresent().contains("user@acme.com");
    }

    @Test
    @DisplayName("should extract roles from claims")
    void shouldExtractRoles() {
        final List<String> roles =
                List.of(SecurityRoles.ROLE_ADMIN, SecurityRoles.ROLE_RESPONDER);
        final String token = jwtUtils.generateToken(
                UUID.randomUUID(), "acme-corp", "user@acme.com", roles);
        final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();

        assertThat(jwtUtils.extractRoles(claims))
                .hasSize(2)
                .containsExactlyInAnyOrder(
                        SecurityRoles.ROLE_ADMIN, SecurityRoles.ROLE_RESPONDER);
    }

    @Test
    @DisplayName("should return empty list when no roles in claims")
    void shouldReturnEmptyRolesWhenNone() {
        final String token = jwtUtils.generateToken(
                UUID.randomUUID(), "acme-corp", "user@acme.com", List.of());
        final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();

        assertThat(jwtUtils.extractRoles(claims)).isEmpty();
    }

    @Test
    @DisplayName("should generate token with all claims verifiable")
    void shouldGenerateTokenWithAllClaimsVerifiable() {
        final UUID userId   = UUID.randomUUID();
        final String tenant = "acme-corp";
        final String email  = "admin@acme.com";
        final List<String> roles =
                List.of(SecurityRoles.ROLE_ADMIN, SecurityRoles.ROLE_INGESTOR);

        final String token  = jwtUtils.generateToken(userId, tenant, email, roles);
        final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();

        assertThat(jwtUtils.extractUserId(claims)).contains(userId);
        assertThat(jwtUtils.extractTenantId(claims)).contains(tenant);
        assertThat(jwtUtils.extractEmail(claims)).contains(email);
        assertThat(jwtUtils.extractRoles(claims))
                .containsExactlyInAnyOrder(
                        SecurityRoles.ROLE_ADMIN, SecurityRoles.ROLE_INGESTOR);
    }

    // ── secret validation ─────────────────────────────────────────────────

    @Test
    @DisplayName("should throw when secret is shorter than 64 bytes")
    void shouldThrowWhenSecretTooShort() {
        assertThatThrownBy(() ->
                buildJwtUtils("short-secret", ACCESS_TOKEN_TTL, SERVICE_TOKEN_TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 bytes");
    }

    @Test
    @DisplayName("should throw when secret is between 32 and 63 characters")
    void shouldThrowWhenSecretBetween32And63Characters() {
        final String secret52 = "test-secret-key-minimum-32-characters-long-for-hs256";
        assertThat(secret52).hasSize(52);

        assertThatThrownBy(() ->
                buildJwtUtils(secret52, ACCESS_TOKEN_TTL, SERVICE_TOKEN_TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 bytes");
    }

    @Test
    @DisplayName("should throw when secret is null — @NotBlank caught before constructor")
    void shouldThrowWhenSecretIsNull() {
        // NullPointerException from JwtProperties record before JwtUtils constructor runs
        assertThatThrownBy(() ->
                buildJwtUtils(null, ACCESS_TOKEN_TTL, SERVICE_TOKEN_TTL))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should accept secret with exactly 64 ASCII characters")
    void shouldAcceptSecretWithExactly64Characters() {
        final String secret64 = "1234567890123456789012345678901234567890123456789012345678901234";
        assertThat(secret64).hasSize(64);

        final JwtUtils utils = buildJwtUtils(secret64, ACCESS_TOKEN_TTL, SERVICE_TOKEN_TTL);
        utils.validateConfiguration();
        assertThat(utils).isNotNull();
    }

    // ── TTL accessors ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("TTL accessors")
    class TtlAccessors {

        @Test
        @DisplayName("getAccessTokenTtl returns configured access token TTL")
        void getAccessTokenTtlReturnsConfiguredValue() {
            assertThat(jwtUtils.getAccessTokenTtl()).isEqualTo(ACCESS_TOKEN_TTL);
        }

        @Test
        @DisplayName("getServiceTokenTtl returns configured service token TTL")
        void getServiceTokenTtlReturnsConfiguredValue() {
            assertThat(jwtUtils.getServiceTokenTtl()).isEqualTo(SERVICE_TOKEN_TTL);
        }


        @Test
        @DisplayName("getRefreshTokenTtl returns configured refresh token TTL")
        void getRefreshTokenTtlReturnsConfiguredValue() {
            assertThat(jwtUtils.getRefreshTokenTtl()).isEqualTo(REFRESH_TOKEN_TTL);
        }

        @Test
        @DisplayName("access token TTL and service token TTL can differ")
        void accessAndServiceTtlCanDiffer() {
            final Duration shortAccess  = Duration.ofMinutes(15);
            final Duration longerService = Duration.ofHours(1);

            final JwtUtils utils = buildJwtUtils(TEST_SECRET, shortAccess, longerService);
            utils.validateConfiguration();

            assertThat(utils.getAccessTokenTtl()).isEqualTo(shortAccess);
            assertThat(utils.getServiceTokenTtl()).isEqualTo(longerService);
            assertThat(utils.getAccessTokenTtl())
                    .isLessThan(utils.getServiceTokenTtl());
        }
    }

    // ── TTL validation ────────────────────────────────────────────────────

    @Nested
    @DisplayName("TTL validation — validateConfiguration()")
    class TtlValidation {

        @Test
        @DisplayName("throws when access-token-ttl is below minimum (PT1M)")
        void throwsWhenAccessTokenTtlTooShort() {
            final JwtUtils utils = buildJwtUtils(
                    TEST_SECRET, Duration.ofSeconds(30), SERVICE_TOKEN_TTL);

            assertThatThrownBy(utils::validateConfiguration)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.access-token-ttl");
        }

        @Test
        @DisplayName("throws when access-token-ttl exceeds maximum (PT24H)")
        void throwsWhenAccessTokenTtlTooLong() {
            final JwtUtils utils = buildJwtUtils(
                    TEST_SECRET, Duration.ofHours(25), SERVICE_TOKEN_TTL);

            assertThatThrownBy(utils::validateConfiguration)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.access-token-ttl");
        }

        @Test
        @DisplayName("throws when service-token-ttl is below minimum (PT10M)")
        void throwsWhenServiceTokenTtlTooShort() {
            final JwtUtils utils = buildJwtUtils(
                    TEST_SECRET, ACCESS_TOKEN_TTL, Duration.ofMinutes(5));

            assertThatThrownBy(utils::validateConfiguration)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.service-token-ttl");
        }

        @Test
        @DisplayName("throws when service-token-ttl exceeds maximum (PT24H)")
        void throwsWhenServiceTokenTtlTooLong() {
            final JwtUtils utils = buildJwtUtils(
                    TEST_SECRET, ACCESS_TOKEN_TTL, Duration.ofHours(25));

            assertThatThrownBy(utils::validateConfiguration)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.service-token-ttl");
        }

        @Test
        @DisplayName("accepts minimum valid access-token-ttl (PT1M)")
        void acceptsMinimumAccessTokenTtl() {
            final JwtUtils utils = buildJwtUtils(
                    TEST_SECRET, Duration.ofMinutes(1), SERVICE_TOKEN_TTL);
            utils.validateConfiguration(); // should not throw
        }

        @Test
        @DisplayName("accepts minimum valid service-token-ttl (PT10M)")
        void acceptsMinimumServiceTokenTtl() {
            final JwtUtils utils = buildJwtUtils(
                    TEST_SECRET, ACCESS_TOKEN_TTL, Duration.ofMinutes(10));
            utils.validateConfiguration(); // should not throw
        }

        @Test
        @DisplayName("accepts maximum valid TTLs (PT24H each)")
        void acceptsMaximumValidTtls() {
            final JwtUtils utils = buildJwtUtils(
                    TEST_SECRET, Duration.ofHours(24), Duration.ofHours(24));
            utils.validateConfiguration(); // should not throw
        }

        @Test
        @DisplayName("throws when refresh-token-ttl is below minimum (P1D)")
        void throwsWhenRefreshTokenTtlTooShort() {
            final JwtUtils utils = buildJwtUtils(
                    TEST_SECRET, ACCESS_TOKEN_TTL, SERVICE_TOKEN_TTL,
                    Duration.ofHours(23));
            assertThatThrownBy(utils::validateConfiguration)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.refresh-token-ttl");
        }

        @Test
        @DisplayName("throws when refresh-token-ttl exceeds maximum (P365D)")
        void throwsWhenRefreshTokenTtlTooLong() {
            final JwtUtils utils = buildJwtUtils(
                    TEST_SECRET, ACCESS_TOKEN_TTL, SERVICE_TOKEN_TTL,
                    Duration.ofDays(366));
            assertThatThrownBy(utils::validateConfiguration)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.refresh-token-ttl");
        }

        @Test
        @DisplayName("accepts minimum valid refresh-token-ttl (P1D)")
        void acceptsMinimumRefreshTokenTtl() {
            final JwtUtils utils = buildJwtUtils(
                    TEST_SECRET, ACCESS_TOKEN_TTL, SERVICE_TOKEN_TTL,
                    Duration.ofDays(1));
            utils.validateConfiguration(); // should not throw
        }
    }
}