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
import org.springframework.web.cors.CorsConfigurationSource;

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
 *
 * <h2>Fixed: CORS was never actually enabled</h2>
 * This class's own {@code securityFilterChain} never called
 * {@code .cors(cors -> cors.configurationSource(...))} on the
 * {@code HttpSecurity} builder — see {@code auth-service}'s own
 * {@code SecurityConfig} class Javadoc for the full account of why a
 * {@code CorsConfigurationSource} bean existing in the application
 * context isn't enough on its own, and why every cross-origin request
 * was rejected with a generic "Invalid CORS request" 403 as a result.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   UnauthorizedEntryPoint unauthorizedEntryPoint)
            throws Exception {
        return SharedSecurityAutoConfiguration.buildCommonSecurity(
                        http, jwtAuthFilter, unauthorizedEntryPoint)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}