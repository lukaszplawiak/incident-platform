package com.incidentplatform.auth.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.TokenRevocationChecker;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * auth-service security configuration.
 *
 * <p>{@code /api/v1/auth/login} must be public — it is the endpoint that
 * issues tokens. All other endpoints require authentication.
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
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/api/v1/auth/accept-invite").permitAll()
                        .requestMatchers("/api/v1/auth/forgot-password").permitAll()
                        .requestMatchers("/api/v1/auth/reset-password").permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}