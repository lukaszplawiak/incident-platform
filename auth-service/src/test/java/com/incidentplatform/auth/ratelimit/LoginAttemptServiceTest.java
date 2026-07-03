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
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoginAttemptService")
class LoginAttemptServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private LoginAttemptService service;

    private static final String EMAIL = "user@example.com";
    private static final String TENANT = "test-tenant";
    private static final int MAX_FAILURES = 5;

    @BeforeEach
    void setUp() {
        final LoginAttemptProperties props = new LoginAttemptProperties(
                true, MAX_FAILURES,
                Duration.ofMinutes(15), Duration.ofMinutes(10));

        service = new LoginAttemptService(redis, props, new SimpleMeterRegistry());
    }

    // ── isLocked ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isLocked")
    class IsLocked {

        @Test
        @DisplayName("returns false when no lockout key exists")
        void returnsFalseWhenNotLocked() {
            given(redis.hasKey(anyString())).willReturn(false);
            assertThat(service.isLocked(EMAIL, TENANT)).isFalse();
        }

        @Test
        @DisplayName("returns true when lockout key exists")
        void returnsTrueWhenLocked() {
            given(redis.hasKey(anyString())).willReturn(true);
            assertThat(service.isLocked(EMAIL, TENANT)).isTrue();
        }

        @Test
        @DisplayName("returns false (fail open) when Redis throws")
        void returnsFalseOnRedisException() {
            given(redis.hasKey(anyString()))
                    .willThrow(new RuntimeException("Redis unavailable"));
            assertThat(service.isLocked(EMAIL, TENANT)).isFalse();
        }
    }

    // ── recordFailure ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("recordFailure")
    class RecordFailure {

        @Test
        @DisplayName("increments attempts counter")
        void incrementsCounter() {
            given(redis.opsForValue()).willReturn(valueOps);
            given(valueOps.increment(anyString())).willReturn(1L);

            service.recordFailure(EMAIL, TENANT);

            then(valueOps).should().increment(
                    "auth:login:attempts:" + TENANT + ":" + EMAIL);
        }

        @Test
        @DisplayName("sets TTL on first attempt")
        void setsTtlOnFirstAttempt() {
            given(redis.opsForValue()).willReturn(valueOps);
            given(valueOps.increment(anyString())).willReturn(1L);

            service.recordFailure(EMAIL, TENANT);

            then(redis).should().expire(
                    eq("auth:login:attempts:" + TENANT + ":" + EMAIL),
                    eq(Duration.ofMinutes(10)));
        }

        @Test
        @DisplayName("does not set TTL on subsequent attempts")
        void doesNotSetTtlOnSubsequentAttempts() {
            given(redis.opsForValue()).willReturn(valueOps);
            given(valueOps.increment(anyString())).willReturn(3L);

            service.recordFailure(EMAIL, TENANT);

            then(redis).should(never()).expire(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("locks account when max failures reached")
        void locksAccountAtMaxFailures() {
            given(redis.opsForValue()).willReturn(valueOps);
            given(valueOps.increment(anyString())).willReturn((long) MAX_FAILURES);

            service.recordFailure(EMAIL, TENANT);

            then(valueOps).should().set(
                    eq("auth:login:locked:" + TENANT + ":" + EMAIL),
                    eq("1"),
                    eq(Duration.ofMinutes(15)));
        }

        @Test
        @DisplayName("does not lock before max failures")
        void doesNotLockBeforeMaxFailures() {
            given(redis.opsForValue()).willReturn(valueOps);
            given(valueOps.increment(anyString())).willReturn((long) MAX_FAILURES - 1);

            service.recordFailure(EMAIL, TENANT);

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
            service.recordSuccess(EMAIL, TENANT);

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
            final LoginAttemptProperties disabledProps = new LoginAttemptProperties(
                    false, MAX_FAILURES,
                    Duration.ofMinutes(15), Duration.ofMinutes(10));
            service = new LoginAttemptService(
                    redis, disabledProps, new SimpleMeterRegistry());
        }

        @Test
        @DisplayName("isLocked always returns false when disabled")
        void alwaysPermitsWhenDisabled() {
            assertThat(service.isLocked(EMAIL, TENANT)).isFalse();
            then(redis).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("recordFailure does nothing when disabled")
        void recordFailureDoesNothingWhenDisabled() {
            service.recordFailure(EMAIL, TENANT);
            then(redis).shouldHaveNoInteractions();
        }
    }
}