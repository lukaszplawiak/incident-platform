package com.incidentplatform.oncall.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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
 *       and {@code ROLE_ADMIN}. Called by internal services via service tokens
 *       (notification-service, escalation-service) — never by end users, so
 *       there's no reason for RESPONDER to reach it directly.</li>
 *   <li>{@code /api/v1/oncall/current/all} — {@code ROLE_RESPONDER} and
 *       {@code ROLE_ADMIN}. Backs the admin panel's "Currently on-call"
 *       display. Previously had no dedicated rule — being a different exact
 *       path from {@code /current}, it silently fell through to the
 *       generic "any authenticated user" rule below, which meant SERVICE
 *       and INGESTOR could reach it too, by accident rather than design.
 *       No internal service caller was found using it (grepped the whole
 *       codebase) — only the frontend does. This rule is now explicit and
 *       narrower: RESPONDER/ADMIN only.
 *       Matches PagerDuty's own permission model: viewing who is on-call is
 *       kept broadly available (their docs: "beneficial for everyone to
 *       see"), while only managing/editing schedules is more restricted —
 *       see the POST/DELETE {@code /schedules} rules below, ADMIN-only.</li>
 *   <li>All other requests — must be authenticated. Unauthenticated requests
 *       receive {@code 401 Unauthorized} via {@link UnauthorizedEntryPoint}.</li>
 * </ul>
 *
 * <h2>Layer 2 — Method-level (@PreAuthorize)</h2>
 * {@code @EnableMethodSecurity} activates Spring AOP method security:
 * <ul>
 *   <li>{@code GET /schedules}, {@code GET /schedules/{id}} — ROLE_RESPONDER or ROLE_ADMIN</li>
 *   <li>{@code POST /schedules}, {@code DELETE /schedules/{id}} — ROLE_ADMIN only</li>
 *   <li>{@code GET /by-slack/{slackUserId}} — authenticated only (ROLE_SERVICE allowed)</li>
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
        return SharedSecurityAutoConfiguration.buildCommonSecurity(http, jwtAuthFilter, unauthorizedEntryPoint)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        .requestMatchers("/api/v1/oncall/current/all")
                        .hasAnyRole(SecurityRoles.RESPONDER, SecurityRoles.ADMIN)
                        .requestMatchers("/api/v1/oncall/current")
                        .hasAnyRole(SecurityRoles.SERVICE, SecurityRoles.ADMIN)
                        .anyRequest().authenticated()
                )
                .build();
    }
}