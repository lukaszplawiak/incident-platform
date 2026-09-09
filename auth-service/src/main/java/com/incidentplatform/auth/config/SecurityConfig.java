package com.incidentplatform.auth.config;

import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.TokenRevocationChecker;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * auth-service security configuration.
 *
 * <p>Public auth endpoints are restricted to {@code POST} only — prevents
 * accidental exposure of e.g. {@code GET /api/v1/auth/reset-password}.
 * Each path is listed explicitly rather than using a wildcard
 * ({@code /api/v1/auth/**}) to avoid accidentally opening future endpoints.
 *
 * <p>Note: {@code /api/v1/auth/login} is NOT added to
 * {@link SharedSecurityAutoConfiguration#PUBLIC_PATHS} because that constant
 * is shared across all services. The login endpoint is specific to auth-service
 * and has no meaning in other services.
 *
 * <h2>Fixed: CORS was never actually enabled</h2>
 * {@code SharedSecurityAutoConfiguration} declares a {@code CorsConfigurationSource}
 * bean, but Spring Security does not automatically wire a bean like that into
 * the filter chain just because it exists in the application context — it must
 * be attached explicitly via {@code .cors(cors -> cors.configurationSource(...))}
 * on the {@code HttpSecurity} builder. This class's own
 * {@code securityFilterChain} previously never called {@code .cors(...)} at
 * all, so Spring's CORS support was effectively disabled here regardless of
 * how {@code security.cors.allowed-origins} was configured — every
 * cross-origin request (including the browser's own preflight OPTIONS,
 * separately fixed in {@code SharedSecurityAutoConfiguration
 * .buildCommonSecurity}) was rejected with a generic "Invalid CORS request"
 * 403, with no CORS response headers at all. Found while trying to log in
 * from the frontend against a locally running stack — curl against the same
 * endpoint worked (curl doesn't perform CORS preflight/enforcement, only
 * browsers do), which is what made this reproducible only from an actual
 * browser. {@code incident-service}/{@code ingestion-service} already wired
 * this correctly; {@code oncall-service}, {@code notification-service}, and
 * {@code postmortem-service} had the identical gap, fixed the same way.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    /**
     * Password encoder used by {@code AuthService} and {@code PasswordService}.
     *
     * <p>Cost factor 12 (default is 10) — 4x more work for an attacker
     * brute-forcing stolen hashes. On modern hardware ~250ms per hash,
     * acceptable for a login endpoint but significant for an attacker.
     *
     * <p>Typed as {@link PasswordEncoder} (interface) rather than
     * {@code BCryptPasswordEncoder} (concrete class) — allows migrating
     * to Argon2 or SCrypt by changing this single bean without touching
     * any service code.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * API key filter — processes Authorization: ApiKey ipl_... headers.
     * Registered BEFORE JwtAuthFilter so API key requests are handled
     * without reaching JWT processing.
     */
    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(
            ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService) {
        return new ApiKeyAuthFilter(apiKeyLookupService);
    }

    /**
     * Overrides the default JwtAuthFilter from SharedSecurityAutoConfiguration
     * with one that checks the Redis revocation list on every request.
     *
     * <p>Only auth-service does this — it is the only service that issues
     * and revokes tokens. Other services use the no-op TokenRevocationChecker
     * (jti -> false) provided by SharedSecurityAutoConfiguration.
     *
     * <p>{@code @Primary} ensures this bean wins over the
     * {@code @ConditionalOnMissingBean} default in SharedSecurityAutoConfiguration.
     */
    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtUtils jwtUtils,
                                       TokenRevocationChecker revocationChecker) {
        return new JwtAuthFilter(jwtUtils, revocationChecker);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   UnauthorizedEntryPoint unauthorizedEntryPoint,
                                                   ApiKeyAuthFilter apiKeyAuthFilter)
            throws Exception {
        return SharedSecurityAutoConfiguration
                .buildCommonSecurity(http, jwtAuthFilter, unauthorizedEntryPoint)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .addFilterBefore(apiKeyAuthFilter, JwtAuthFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/accept-invite").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/forgot-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/reset-password").permitAll()
                        // MFA verify — public because client holds only mfaToken, not JWT
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/verify").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/verify-backup").permitAll()
                        // MFA required-by-tenant setup flow — public because the client
                        // holds only mfaSetupToken, having never received a JWT (login
                        // was blocked pending MFA configuration; see AuthService.login())
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/setup-required").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/enable-required").permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}