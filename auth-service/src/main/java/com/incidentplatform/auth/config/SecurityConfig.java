package com.incidentplatform.auth.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.TokenRevocationChecker;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

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
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    /**
     * Password encoder using Argon2id — OWASP 2024 recommended algorithm.
     *
     * <h2>Why Argon2id over BCrypt</h2>
     * BCrypt uses only ~4KB RAM per attempt — modern GPUs can run thousands
     * of parallel brute-force attempts. Argon2id is memory-hard: each attempt
     * requires 64MB RAM by default, reducing GPU parallelism from thousands
     * of attempts to ~375 (24GB VRAM / 64MB). This is the fundamental
     * advantage — hardware cost scales with memory, not just compute.
     *
     * <p>Spring Security defaults (v5.8+):
     * memory=65536 (64MB), iterations=3, parallelism=1 — RFC 9106 minimum.
     * Output format: {@code $argon2id$v=19$m=65536,t=3,p=1$...}
     *
     * <p>Typed as {@link PasswordEncoder} (interface) — all services
     * ({@code AuthService}, {@code PasswordService}, {@code InviteService})
     * are unaware of the concrete algorithm.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
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
    @Primary
    public JwtAuthFilter jwtAuthFilter(JwtUtils jwtUtils,
                                       TokenRevocationChecker revocationChecker) {
        return new JwtAuthFilter(jwtUtils, revocationChecker);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   UnauthorizedEntryPoint unauthorizedEntryPoint)
            throws Exception {
        return SharedSecurityAutoConfiguration
                .buildCommonSecurity(http, jwtAuthFilter, unauthorizedEntryPoint)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/accept-invite").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/forgot-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/reset-password").permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}