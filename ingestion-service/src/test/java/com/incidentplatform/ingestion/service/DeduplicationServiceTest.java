package com.incidentplatform.ingestion.service;

import com.incidentplatform.ingestion.config.DeduplicationProperties;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.SourceType;
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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * Tests for {@link DeduplicationService} — previously with no test
 * coverage of any kind, despite the class having a
 * {@code @CircuitBreaker} whose correctness turned out to matter (see
 * that class's Javadoc for the bug this fix addresses).
 *
 * <p>Mirrors {@code BruteForceProtectionServiceTest} (auth-service,
 * renamed from {@code LoginAttemptServiceTest} — backlog #58) — mocks
 * {@link StringRedisTemplate}/{@link ValueOperations} rather than
 * standing up a real Redis, and uses a real {@link SimpleMeterRegistry}
 * instead of mocking every {@code Counter} individually so assertions
 * read the actual recorded value.
 *
 * <p>Critically, this test also verifies something the class's own
 * previous design made impossible to verify: that a Redis failure
 * actually propagates out of {@link DeduplicationService#isDuplicate}
 * rather than being silently swallowed. That propagation is exactly what
 * lets the real, Spring-managed {@code @CircuitBreaker} proxy see and
 * record the failure in production — this test can't exercise the proxy
 * itself (that needs a Spring context), but it proves the precondition
 * the proxy depends on.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeduplicationService")
class DeduplicationServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private SimpleMeterRegistry meterRegistry;
    private DeduplicationService service;

    private static final String TENANT_ID = "acme-corp";
    private static final Duration TTL = Duration.ofMinutes(5);

    @BeforeEach
    void setUp() {
        final DeduplicationProperties properties = new DeduplicationProperties(TTL);
        meterRegistry = new SimpleMeterRegistry();

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new DeduplicationService(redisTemplate, meterRegistry, properties);
    }

    private UnifiedAlertDto buildAlert(String fingerprint) {
        return new UnifiedAlertDto(
                UUID.randomUUID(),
                TENANT_ID,
                "prometheus",
                SourceType.OPS,
                Severity.HIGH,
                "High CPU usage",
                "CPU exceeded 95%",
                Instant.now(),
                fingerprint,
                Map.of(),
                null
        );
    }

    private double counterValue(String name) {
        return meterRegistry.get(name).counter().count();
    }

    // ── isDuplicate ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("isDuplicate")
    class IsDuplicate {

        @Test
        @DisplayName("returns false for a new fingerprint (SETNX succeeds)")
        void returnsFalseForNewFingerprint() {
            final UnifiedAlertDto alert = buildAlert("fp-1");
            given(valueOps.setIfAbsent(
                    eq("dedup:" + TENANT_ID + ":fp-1"), eq("1"), eq(TTL)))
                    .willReturn(true);

            assertThat(service.isDuplicate(alert)).isFalse();
            assertThat(counterValue("dedup.duplicates.rejected")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("returns true and increments the counter for a repeated fingerprint (SETNX fails)")
        void returnsTrueForDuplicateFingerprint() {
            final UnifiedAlertDto alert = buildAlert("fp-1");
            given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .willReturn(false);

            assertThat(service.isDuplicate(alert)).isTrue();
            assertThat(counterValue("dedup.duplicates.rejected")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("uses the tenant + fingerprint as the Redis key, keeping tenants isolated")
        void keysAreTenantScoped() {
            final UnifiedAlertDto alert = buildAlert("shared-fingerprint");
            given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .willReturn(true);

            service.isDuplicate(alert);

            org.mockito.BDDMockito.then(valueOps).should().setIfAbsent(
                    eq("dedup:" + TENANT_ID + ":shared-fingerprint"), eq("1"), eq(TTL));
        }

        @Test
        @DisplayName("propagates a Redis failure instead of swallowing it — " +
                "this is what makes the @CircuitBreaker proxy actually work")
        void propagatesRedisFailure() {
            final UnifiedAlertDto alert = buildAlert("fp-1");
            given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .willThrow(new RuntimeException("Redis connection refused"));

            assertThatThrownBy(() -> service.isDuplicate(alert))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Redis connection refused");
        }
    }

    // ── isDuplicateFallback ──────────────────────────────────────────────

    @Nested
    @DisplayName("isDuplicateFallback")
    class IsDuplicateFallback {

        @Test
        @DisplayName("fails open (returns false) and increments the Redis-error counter")
        void failsOpenAndIncrementsCounter() {
            final UnifiedAlertDto alert = buildAlert("fp-1");

            final boolean result = service.isDuplicateFallback(
                    alert, new RuntimeException("Redis unavailable"));

            assertThat(result).isFalse();
            assertThat(counterValue("dedup.redis.errors")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("does not touch the duplicates-rejected counter — this is not a real duplicate")
        void doesNotAffectDuplicatesCounter() {
            final UnifiedAlertDto alert = buildAlert("fp-1");

            service.isDuplicateFallback(alert, new RuntimeException("boom"));

            assertThat(counterValue("dedup.duplicates.rejected")).isEqualTo(0.0);
        }
    }
}