package com.incidentplatform.auth.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Renamed from LoginAttemptServiceTest (backlog #58) — see
 * {@link BruteForceProtectionService}'s own Javadoc for the full account
 * of the rename/generalization. Existing coverage carried over unchanged
 * (still exercised via {@link BruteForceProtectionService.Scope#LOGIN},
 * matching this class's prior, login-only behavior exactly), plus a new
 * {@code ScopeIsolation} section verifying LOGIN and MFA scopes use
 * genuinely independent Redis keys and counters — the actual point of
 * this generalization.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BruteForceProtectionService")
class BruteForceProtectionServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private BruteForceProtectionService service;

    private static final String EMAIL = "user@example.com";
    private static final String TENANT = "test-tenant";
    private static final int MAX_FAILURES = 5;
    private static final BruteForceProtectionService.Scope LOGIN =
            BruteForceProtectionService.Scope.LOGIN;
    private static final BruteForceProtectionService.Scope MFA =
            BruteForceProtectionService.Scope.MFA;

    @BeforeEach
    void setUp() {
        final BruteForceProtectionProperties props = new BruteForceProtectionProperties(
                true, MAX_FAILURES,
                Duration.ofMinutes(15), Duration.ofMinutes(10));

        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new BruteForceProtectionService(redis, props, new SimpleMeterRegistry());
    }

    // ── isLocked ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isLocked")
    class IsLocked {

        @Test
        @DisplayName("returns false when no lockout key exists")
        void returnsFalseWhenNotLocked() {
            given(valueOps.get(anyString())).willReturn(null);
            assertThat(service.isLocked(LOGIN, EMAIL, TENANT)).isFalse();
        }

        @Test
        @DisplayName("returns true when lockout key exists")
        void returnsTrueWhenLocked() {
            given(valueOps.get(anyString())).willReturn("1");
            assertThat(service.isLocked(LOGIN, EMAIL, TENANT)).isTrue();
        }

        @Test
        @DisplayName("returns false (fail open) when Redis throws")
        void returnsFalseOnRedisException() {
            given(valueOps.get(anyString()))
                    .willThrow(new RuntimeException("Redis unavailable"));
            assertThat(service.isLocked(LOGIN, EMAIL, TENANT)).isFalse();
        }
    }

    // ── recordFailure ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("recordFailure")
    class RecordFailure {

        @Test
        @DisplayName("increments attempts counter")
        void incrementsCounter() {
            lenient().when(redis.opsForValue()).thenReturn(valueOps);
            given(valueOps.increment(anyString())).willReturn(1L);

            service.recordFailure(LOGIN, EMAIL, TENANT);

            then(valueOps).should().increment(
                    "auth:login:attempts:" + TENANT + ":" + EMAIL);
        }

        @Test
        @DisplayName("sets TTL on first attempt")
        void setsTtlOnFirstAttempt() {
            lenient().when(redis.opsForValue()).thenReturn(valueOps);
            given(valueOps.increment(anyString())).willReturn(1L);

            service.recordFailure(LOGIN, EMAIL, TENANT);

            then(redis).should().expire(
                    eq("auth:login:attempts:" + TENANT + ":" + EMAIL),
                    eq(Duration.ofMinutes(10)));
        }

        @Test
        @DisplayName("does not set TTL on subsequent attempts")
        void doesNotSetTtlOnSubsequentAttempts() {
            lenient().when(redis.opsForValue()).thenReturn(valueOps);
            given(valueOps.increment(anyString())).willReturn(3L);


            service.recordFailure(LOGIN, EMAIL, TENANT);

            then(redis).should(never()).expire(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("locks out when max failures reached")
        void locksAtMaxFailures() {
            lenient().when(redis.opsForValue()).thenReturn(valueOps);
            given(valueOps.increment(anyString())).willReturn((long) MAX_FAILURES);

            service.recordFailure(LOGIN, EMAIL, TENANT);

            then(valueOps).should().set(
                    eq("auth:login:locked:" + TENANT + ":" + EMAIL),
                    eq("1"),
                    eq(Duration.ofMinutes(15)));
        }

        @Test
        @DisplayName("does not lock before max failures")
        void doesNotLockBeforeMaxFailures() {
            lenient().when(redis.opsForValue()).thenReturn(valueOps);
            given(valueOps.increment(anyString())).willReturn((long) MAX_FAILURES - 1);

            service.recordFailure(LOGIN, EMAIL, TENANT);

            then(valueOps).should(never()).set(
                    anyString(), anyString(), any(Duration.class));
        }
    }

    // ── recordSuccess ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("recordSuccess")
    class RecordSuccess {

        @Test
        @DisplayName("deletes both attempts and locked keys")
        void deletesBothKeys() {
            service.recordSuccess(LOGIN, EMAIL, TENANT);

            then(redis).should().delete(
                    "auth:login:attempts:" + TENANT + ":" + EMAIL);
            then(redis).should().delete(
                    "auth:login:locked:" + TENANT + ":" + EMAIL);
        }
    }

    // ── disabled mode ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("disabled mode")
    class DisabledMode {

        @BeforeEach
        void disableRateLimiting() {
            final BruteForceProtectionProperties disabledProps =
                    new BruteForceProtectionProperties(
                            false, MAX_FAILURES,
                            Duration.ofMinutes(15), Duration.ofMinutes(10));
            service = new BruteForceProtectionService(
                    redis, disabledProps, new SimpleMeterRegistry());
        }

        @Test
        @DisplayName("isLocked always returns false when disabled")
        void alwaysPermitsWhenDisabled() {
            assertThat(service.isLocked(LOGIN, EMAIL, TENANT)).isFalse();
            then(redis).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("recordFailure does nothing when disabled")
        void recordFailureDoesNothingWhenDisabled() {
            service.recordFailure(LOGIN, EMAIL, TENANT);
            then(redis).shouldHaveNoInteractions();
        }
    }

    /**
     * The actual regression coverage for backlog #58's generalization —
     * verifies LOGIN and MFA scopes are genuinely independent: a failure
     * recorded under one scope must not affect the other's Redis key or
     * lockout state, since the whole point of this fix was ensuring a
     * correct password (LOGIN success) never suppresses an MFA lockout
     * building up from repeated bad TOTP guesses, and vice versa.
     */
    @Nested
    @DisplayName("scope isolation (backlog #58)")
    class ScopeIsolation {

        @Test
        @DisplayName("LOGIN and MFA use different Redis keys for the same identifier/tenant")
        void useDifferentKeysPerScope() {
            lenient().when(redis.opsForValue()).thenReturn(valueOps);
            given(valueOps.increment(anyString())).willReturn(1L);

            service.recordFailure(LOGIN, EMAIL, TENANT);
            service.recordFailure(MFA, EMAIL, TENANT);

            then(valueOps).should().increment("auth:login:attempts:" + TENANT + ":" + EMAIL);
            then(valueOps).should().increment("auth:mfa:attempts:" + TENANT + ":" + EMAIL);
        }

        @Test
        @DisplayName("checking isLocked for MFA does not read the LOGIN lockout key")
        void isLockedChecksOnlyItsOwnScopeKey() {
            given(valueOps.get("auth:mfa:locked:" + TENANT + ":" + EMAIL))
                    .willReturn(null);

            assertThat(service.isLocked(MFA, EMAIL, TENANT)).isFalse();

            then(valueOps).should().get("auth:mfa:locked:" + TENANT + ":" + EMAIL);
            then(valueOps).should(never()).get("auth:login:locked:" + TENANT + ":" + EMAIL);
        }
    }
}