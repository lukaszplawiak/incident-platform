package com.incidentplatform.auth.api;

import com.incidentplatform.auth.service.MfaSessionStatusService;
import com.incidentplatform.shared.security.TokenRevocationChecker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Primary;

/**
 * Test-only security configuration for {@code @WebMvcTest} slices
 * in the {@code auth.api} package.
 *
 * <h2>Problem solved</h2>
 * {@code auth-service/SecurityConfig.jwtAuthFilter()} requires a
 * {@link TokenRevocationChecker} bean — implemented in production by
 * {@code TokenRevocationService} which needs Redis. Redis is not available
 * in a {@code @WebMvcTest} slice.
 *
 * <h2>Solution</h2>
 * This config provides a no-op {@code TokenRevocationChecker} ({@code jti -> false})
 * marked {@code @Primary}. Spring Boot's bean overriding picks this up and
 * {@code SecurityConfig.jwtAuthFilter()} uses it instead of the missing
 * Redis-backed implementation.
 *
 * <p>We do NOT redefine {@code jwtAuthFilter} here — that would cause a
 * {@code BeanDefinitionOverrideException} since bean overriding is disabled
 * by default. Instead, we let {@code SecurityConfig} create the filter using
 * our no-op checker.
 *
 * <h2>Loaded automatically</h2>
 * {@code AuthApiTestApplication} scans {@code com.incidentplatform.auth.api}
 * — this class lives in that package and is annotated {@code @TestConfiguration}.
 */
@TestConfiguration
public class AuthWebMvcTestConfig {

    /**
     * No-op revocation checker — all tokens appear valid (not revoked).
     * Replaces {@code TokenRevocationService} which requires Redis.
     *
     * <p>{@code @Primary} ensures this bean wins over any other
     * {@code TokenRevocationChecker} candidate in the test context.
     */
    @Bean
    @Primary
    public TokenRevocationChecker tokenRevocationChecker() {
        return jti -> false;
    }

    /**
     * Backlog #0-83: {@code PlatformAccess} (in the scanned config package)
     * needs the session MFA lookup, a service the web slice does not load. A
     * mock that answers "no MFA" by default: a test of the platform API
     * replaces it with {@code @MockitoBean} and says which session passed.
     *
     * <p>{@code @Fallback} and a name of its own: {@code AuthServiceApplication}'s
     * explicit {@code @ComponentScan} has no {@code TypeExcludeFilter}, so the
     * {@code @SpringBootTest} integration tests scan this test configuration too
     * (backlog #0-86). There the real service must win; a same-named bean
     * would silently replace it.
     */
    @Bean
    @Fallback
    public MfaSessionStatusService webSliceMfaSessionStatusService() {
        final MfaSessionStatusService mock = Mockito.mock(MfaSessionStatusService.class);
        Mockito.when(mock.check(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(MfaSessionStatusService.Status.NO_MFA);
        return mock;
    }

    /**
     * Backlog #0-83: {@code PlatformTenantController} counts provisioned
     * tenants; the web slice has no metrics auto-configuration.
     * {@code @Fallback} for the same reason as above.
     */
    @Bean
    @Fallback
    public MeterRegistry webSliceMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}