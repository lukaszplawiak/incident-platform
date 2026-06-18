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

    // HS512 requires minimum 64 characters (512 bits).
    private static final String TEST_SECRET =
            "test-secret-key-minimum-64-characters-long-for-hs512-absolutely-not-for-production";
    private static final long EXPIRATION_MS = 3600_000L;
    private static final Duration SERVICE_EXPIRATION = Duration.ofHours(1);

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils(TEST_SECRET, EXPIRATION_MS, SERVICE_EXPIRATION);
        jwtUtils.validateServiceExpiration();
    }

    @Test
    @DisplayName("should generate non-null token")
    void shouldGenerateNonNullToken() {
        // given
        final UUID userId = UUID.randomUUID();
        final List<String> roles = List.of(SecurityRoles.ROLE_ADMIN);

        // when
        final String token = jwtUtils.generateToken(
                userId, "acme-corp", "user@acme.com", roles);

        // then
        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("should generate different tokens for different users")
    void shouldGenerateDifferentTokensForDifferentUsers() {
        // when
        final String token1 = jwtUtils.generateToken(
                UUID.randomUUID(), "tenant1", "user1@test.com",
                List.of(SecurityRoles.ROLE_ADMIN));
        final String token2 = jwtUtils.generateToken(
                UUID.randomUUID(), "tenant2", "user2@test.com",
                List.of(SecurityRoles.ROLE_RESPONDER));

        // then
        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    @DisplayName("should return claims for valid token")
    void shouldReturnClaimsForValidToken() {
        // given
        final UUID userId = UUID.randomUUID();
        final String token = jwtUtils.generateToken(
                userId, "acme-corp", "user@acme.com",
                List.of(SecurityRoles.ROLE_ADMIN));

        // when
        final Optional<Claims> claims = jwtUtils.validateAndGetClaims(token);

        // then
        assertThat(claims).isPresent();
    }

    @Test
    @DisplayName("should return empty for invalid token")
    void shouldReturnEmptyForInvalidToken() {
        // when
        final Optional<Claims> claims =
                jwtUtils.validateAndGetClaims("invalid.token.here");

        // then
        assertThat(claims).isEmpty();
    }

    @Test
    @DisplayName("should return empty for token with wrong signature")
    void shouldReturnEmptyForWrongSignature() {
        // given
        final JwtUtils otherJwtUtils = new JwtUtils(
                "other-secret-key-minimum-64-characters-long-for-hs512-absolutely-not-prod",
                EXPIRATION_MS, SERVICE_EXPIRATION);
        final String tokenWithWrongSignature = otherJwtUtils.generateToken(
                UUID.randomUUID(), "acme", "user@acme.com", List.of());

        // when
        final Optional<Claims> claims =
                jwtUtils.validateAndGetClaims(tokenWithWrongSignature);

        // then
        assertThat(claims).isEmpty();
    }

    @Test
    @DisplayName("should return empty for expired token")
    void shouldReturnEmptyForExpiredToken() {
        // given
        final JwtUtils expiredJwtUtils = new JwtUtils(TEST_SECRET, 0L, SERVICE_EXPIRATION);
        expiredJwtUtils.validateServiceExpiration();
        final String expiredToken = expiredJwtUtils.generateToken(
                UUID.randomUUID(), "acme", "user@acme.com", List.of());

        // when
        final Optional<Claims> claims =
                jwtUtils.validateAndGetClaims(expiredToken);

        // then
        assertThat(claims).isEmpty();
    }

    @Test
    @DisplayName("should return empty for null token")
    void shouldReturnEmptyForNullToken() {
        // when
        final Optional<Claims> claims = jwtUtils.validateAndGetClaims(null);

        // then
        assertThat(claims).isEmpty();
    }

    @Test
    @DisplayName("should return empty for blank token")
    void shouldReturnEmptyForBlankToken() {
        // when
        final Optional<Claims> claims = jwtUtils.validateAndGetClaims("   ");

        // then
        assertThat(claims).isEmpty();
    }

    @Test
    @DisplayName("should extract userId from claims")
    void shouldExtractUserId() {
        // given
        final UUID userId = UUID.randomUUID();
        final String token = jwtUtils.generateToken(
                userId, "acme-corp", "user@acme.com", List.of());
        final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();

        // when
        final Optional<UUID> extractedId = jwtUtils.extractUserId(claims);

        // then
        assertThat(extractedId).isPresent().contains(userId);
    }

    @Test
    @DisplayName("should extract tenantId from claims")
    void shouldExtractTenantId() {
        // given
        final String token = jwtUtils.generateToken(
                UUID.randomUUID(), "acme-corp", "user@acme.com", List.of());
        final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();

        // when
        final Optional<String> tenantId = jwtUtils.extractTenantId(claims);

        // then
        assertThat(tenantId).isPresent().contains("acme-corp");
    }

    @Test
    @DisplayName("should extract email from claims")
    void shouldExtractEmail() {
        // given
        final String token = jwtUtils.generateToken(
                UUID.randomUUID(), "acme-corp", "user@acme.com", List.of());
        final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();

        // when
        final Optional<String> email = jwtUtils.extractEmail(claims);

        // then
        assertThat(email).isPresent().contains("user@acme.com");
    }

    @Test
    @DisplayName("should extract roles from claims")
    void shouldExtractRoles() {
        // given
        final List<String> roles = List.of(SecurityRoles.ROLE_ADMIN, SecurityRoles.ROLE_RESPONDER);
        final String token = jwtUtils.generateToken(
                UUID.randomUUID(), "acme-corp", "user@acme.com", roles);
        final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();

        // when
        final List<String> extractedRoles = jwtUtils.extractRoles(claims);

        // then
        assertThat(extractedRoles)
                .hasSize(2)
                .containsExactlyInAnyOrder(SecurityRoles.ROLE_ADMIN, SecurityRoles.ROLE_RESPONDER);
    }

    @Test
    @DisplayName("should return empty list when no roles in claims")
    void shouldReturnEmptyRolesWhenNone() {
        // given
        final String token = jwtUtils.generateToken(
                UUID.randomUUID(), "acme-corp", "user@acme.com", List.of());
        final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();

        // when
        final List<String> roles = jwtUtils.extractRoles(claims);

        // then
        assertThat(roles).isEmpty();
    }

    @Test
    @DisplayName("should throw when secret is shorter than 64 characters")
    void shouldThrowWhenSecretTooShort() {
        // then — "short-secret" is only 12 chars, well below 64 minimum for HS512
        assertThatThrownBy(() -> new JwtUtils("short-secret", EXPIRATION_MS, SERVICE_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 characters");
    }

    @Test
    @DisplayName("should throw when secret is between 32 and 63 characters")
    void shouldThrowWhenSecretBetween32And63Characters() {
        final String secret52chars = "test-secret-key-minimum-32-characters-long-for-hs256";
        assertThat(secret52chars).hasSize(52);

        assertThatThrownBy(() -> new JwtUtils(secret52chars, EXPIRATION_MS, SERVICE_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 characters");
    }

    @Test
    @DisplayName("should throw when secret is null")
    void shouldThrowWhenSecretIsNull() {
        // then
        assertThatThrownBy(() -> new JwtUtils(null, EXPIRATION_MS, SERVICE_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should accept secret with exactly 64 characters")
    void shouldAcceptSecretWithExactly64Characters() {
        // given — exactly 64 ASCII chars = 64 bytes = 512 bits, minimum for HS512
        final String secret64 = "1234567890123456789012345678901234567890123456789012345678901234";
        assertThat(secret64).hasSize(64);

        // when/then
        final JwtUtils utils = new JwtUtils(secret64, EXPIRATION_MS, SERVICE_EXPIRATION);
        utils.validateServiceExpiration();
        assertThat(utils).isNotNull();
    }

    @Test
    @DisplayName("should generate token with all claims verifiable")
    void shouldGenerateTokenWithAllClaimsVerifiable() {
        // given
        final UUID userId = UUID.randomUUID();
        final String tenantId = "acme-corp";
        final String email = "admin@acme.com";
        final List<String> roles = List.of(SecurityRoles.ROLE_ADMIN, SecurityRoles.ROLE_INGESTOR);

        // when
        final String token = jwtUtils.generateToken(userId, tenantId, email, roles);
        final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();

        // then
        assertThat(jwtUtils.extractUserId(claims)).contains(userId);
        assertThat(jwtUtils.extractTenantId(claims)).contains(tenantId);
        assertThat(jwtUtils.extractEmail(claims)).contains(email);
        assertThat(jwtUtils.extractRoles(claims))
                .containsExactlyInAnyOrder(SecurityRoles.ROLE_ADMIN, SecurityRoles.ROLE_INGESTOR);
    }

    // ─── service expiration validation ───────────────────────────────────────

    @Nested
    @DisplayName("service expiration validation")
    class ServiceExpirationValidation {

        @Test
        @DisplayName("should throw when service-expiration is below minimum (10 minutes)")
        void shouldThrowWhenServiceExpirationTooShort() {
            final JwtUtils utils = new JwtUtils(
                    TEST_SECRET, EXPIRATION_MS, Duration.ofMinutes(5));

            assertThatThrownBy(utils::validateServiceExpiration)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.service-expiration");
        }

        @Test
        @DisplayName("should throw when service-expiration exceeds maximum (24 hours)")
        void shouldThrowWhenServiceExpirationTooLong() {
            final JwtUtils utils = new JwtUtils(
                    TEST_SECRET, EXPIRATION_MS, Duration.ofHours(25));

            assertThatThrownBy(utils::validateServiceExpiration)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.service-expiration");
        }

        @Test
        @DisplayName("should accept minimum valid duration (10 minutes)")
        void shouldAcceptMinimumValidDuration() {
            final JwtUtils utils = new JwtUtils(
                    TEST_SECRET, EXPIRATION_MS, Duration.ofMinutes(10));
            utils.validateServiceExpiration(); // should not throw
        }

        @Test
        @DisplayName("should accept maximum valid duration (24 hours)")
        void shouldAcceptMaximumValidDuration() {
            final JwtUtils utils = new JwtUtils(
                    TEST_SECRET, EXPIRATION_MS, Duration.ofHours(24));
            utils.validateServiceExpiration(); // should not throw
        }

        @Test
        @DisplayName("getServiceExpirationMs should return duration in milliseconds")
        void shouldReturnServiceExpirationMs() {
            assertThat(jwtUtils.getServiceExpirationMs())
                    .isEqualTo(Duration.ofHours(1).toMillis());
        }
    }
}