package com.incidentplatform.postmortem.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
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
 * {@code @PreAuthorize} annotations in
 * {@link com.incidentplatform.postmortem.api.PostmortemController} to take effect.
 *
 * <h2>Defense in depth</h2>
 * <ul>
 *   <li>Layer 1 — {@link SecurityFilterChain}: every request must carry a
 *       valid JWT ({@code anyRequest().authenticated()}). Unauthenticated
 *       requests receive {@code 401 Unauthorized} via
 *       {@link UnauthorizedEntryPoint}.</li>
 *   <li>Layer 2 — {@code @PreAuthorize} on each controller method: only
 *       tokens with {@code ROLE_RESPONDER} or {@code ROLE_ADMIN} may access
 *       postmortem endpoints. Wrong-role requests receive {@code 403 Forbidden}.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   UnauthorizedEntryPoint unauthorizedEntryPoint)
            throws Exception {
        return SharedSecurityAutoConfiguration.buildCommonSecurity(
                        http, jwtAuthFilter, unauthorizedEntryPoint)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}