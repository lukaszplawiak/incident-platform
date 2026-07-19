package com.incidentplatform.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenRevocationService")
class TokenRevocationServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private TokenRevocationService service;

    private static final String JTI = "550e8400-e29b-41d4-a716-446655440000";

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new TokenRevocationService(redis);
    }

    // ── revoke ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("revoke")
    class Revoke {

        @Test
        @DisplayName("stores jti in Redis with TTL equal to remaining lifetime")
        void storesJtiWithTtl() {
            lenient().when(redis.opsForValue()).thenReturn(valueOps);
            final Date expiresAt = Date.from(Instant.now().plusSeconds(3600));

            service.revoke(JTI, expiresAt);

            then(valueOps).should().set(
                    eq("auth:revoked:" + JTI),
                    eq("1"),
                    any(Duration.class));
        }

        @Test
        @DisplayName("skips revocation when token already expired")
        void skipsExpiredToken() {
            final Date alreadyExpired = Date.from(Instant.now().minusSeconds(60));

            service.revoke(JTI, alreadyExpired);

            then(redis).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("does not throw when Redis unavailable")
        void doesNotThrowOnRedisFailure() {
            lenient().when(redis.opsForValue()).thenReturn(valueOps);
            org.mockito.BDDMockito.willThrow(new RuntimeException("Redis down"))
                    .given(valueOps).set(anyString(), anyString(), any(Duration.class));

            // Should not throw — logout must succeed even when Redis is down
            service.revoke(JTI, Date.from(Instant.now().plusSeconds(3600)));
        }
    }

    // ── isRevoked ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isRevoked")
    class IsRevoked {

        @Test
        @DisplayName("returns true when jti is in revocation list")
        void returnsTrueWhenRevoked() {
            given(valueOps.get("auth:revoked:" + JTI)).willReturn("1");

            assertThat(service.isRevoked(JTI)).isTrue();
        }

        @Test
        @DisplayName("returns false when jti is not revoked")
        void returnsFalseWhenNotRevoked() {
            given(valueOps.get("auth:revoked:" + JTI)).willReturn(null);

            assertThat(service.isRevoked(JTI)).isFalse();
        }

        @Test
        @DisplayName("returns false (fail-open) when Redis unavailable")
        void returnsFalseOnRedisFailure() {
            given(valueOps.get(anyString()))
                    .willThrow(new RuntimeException("Redis down"));

            // Fail-open: Redis outage must not reject all requests
            assertThat(service.isRevoked(JTI)).isFalse();
        }
    }
}