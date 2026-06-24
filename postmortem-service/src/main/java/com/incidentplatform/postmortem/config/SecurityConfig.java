package com.incidentplatform.postmortem.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * postmortem-service security configuration.
 *
 * <p>Extends the platform baseline from
 * {@link SharedSecurityAutoConfiguration#buildCommonSecurity} with
 * method-level security ({@code @EnableMethodSecurity}) required for
 * {@code @PreAuthorize} annotations in {@link com.incidentplatform.postmortem.api.PostmortemController}
 * to take effect.
 *
 * <h2>Why a service-specific SecurityConfig is needed</h2>
 * {@link SharedSecurityAutoConfiguration} provides the shared
 * {@link SecurityFilterChain} baseline (JWT filter, CSRF, headers etc.)
 * but does not and cannot enable {@code @EnableMethodSecurity} — that
 * annotation must live in each application context individually. Without it,
 * {@code @PreAuthorize} on controller methods is silently ignored by Spring,
 * giving a false sense of role-based access control while any authenticated
 * request passes regardless of role.
 *
 * <h2>Defense in depth</h2>
 * <ul>
 *   <li>Layer 1 — {@link SecurityFilterChain}: every request must carry a
 *       valid JWT ({@code anyRequest().authenticated()}).</li>
 *   <li>Layer 2 — {@code @PreAuthorize} on each controller method: only
 *       tokens with {@code ROLE_RESPONDER} or {@code ROLE_ADMIN} may access
 *       postmortem endpoints.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter)
            throws Exception {
        return SharedSecurityAutoConfiguration.buildCommonSecurity(http, jwtAuthFilter)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}