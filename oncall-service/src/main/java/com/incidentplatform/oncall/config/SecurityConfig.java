package com.incidentplatform.oncall.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * oncall-service security configuration.
 *
 * <p>Extends the platform baseline with two layers of access control:
 *
 * <h2>Layer 1 — URL-level (SecurityFilterChain)</h2>
 * <ul>
 *   <li>{@code /api/v1/oncall/current} — restricted to {@code ROLE_SERVICE}
 *       and {@code ROLE_ADMIN}. This endpoint is called by internal services
 *       ({@code notification-service}, {@code escalation-service}) using
 *       service-to-service tokens. No {@code @PreAuthorize} is placed on the
 *       controller method because the URL rule is already more restrictive
 *       than "any authenticated user".</li>
 *   <li>All other requests — must be authenticated ({@code anyRequest().authenticated()}).</li>
 * </ul>
 *
 * <h2>Layer 2 — Method-level (@PreAuthorize)</h2>
 * {@code @EnableMethodSecurity} activates Spring's AOP-based method security,
 * making {@code @PreAuthorize} annotations on
 * {@link com.incidentplatform.oncall.api.OncallScheduleController} methods
 * effective. Without this annotation, {@code @PreAuthorize} is silently ignored
 * regardless of its presence on individual methods.
 *
 * <p>Role mapping on controller methods:
 * <ul>
 *   <li>{@code GET /schedules}, {@code GET /schedules/{id}} —
 *       {@code ROLE_RESPONDER} or {@code ROLE_ADMIN}</li>
 *   <li>{@code POST /schedules}, {@code DELETE /schedules/{id}} —
 *       {@code ROLE_ADMIN} only (schedule management is an admin operation)</li>
 *   <li>{@code GET /by-slack/{slackUserId}} — authenticated only
 *       (called by {@code notification-service} with {@code ROLE_SERVICE})</li>
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
                        // Called by notification-service / escalation-service via service token.
                        // URL-level restriction is sufficient here — no @PreAuthorize on
                        // the controller method to avoid redundant duplication.
                        .requestMatchers("/api/v1/oncall/current")
                        .hasAnyRole(SecurityRoles.SERVICE, SecurityRoles.ADMIN)
                        .anyRequest().authenticated()
                )
                .build();
    }
}