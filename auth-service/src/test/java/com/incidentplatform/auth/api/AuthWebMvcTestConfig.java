package com.incidentplatform.auth.api;

import com.incidentplatform.shared.security.TokenRevocationChecker;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
}