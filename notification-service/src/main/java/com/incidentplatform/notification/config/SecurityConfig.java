package com.incidentplatform.notification.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * notification-service security configuration.
 *
 * <p>Extends the platform baseline with a public path for Slack interactive
 * action callbacks. Slack sends signed POST requests to this endpoint without
 * a JWT token — authentication is handled by
 * {@code SlackSignatureVerifier} which validates the {@code X-Slack-Signature}
 * HMAC header instead.
 *
 * <h2>CORS</h2>
 * {@code SharedSecurityAutoConfiguration.buildCommonSecurity} applies CORS to
 * every chain built from it (backlog #0-199, ADR-0049); the
 * {@code .cors(cors -> cors.configurationSource(corsConfigurationSource))}
 * call below names the shared source explicitly, as an override. Before
 * #0-199 the chain had no CORS without that call, and this class did not
 * make it, so every cross-origin request was rejected with a generic
 * "Invalid CORS request" 403 — see {@code auth-service}'s own
 * {@code SecurityConfig} class Javadoc for that account.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   UnauthorizedEntryPoint unauthorizedEntryPoint)
            throws Exception {
        return SharedSecurityAutoConfiguration.buildCommonSecurity(http, jwtAuthFilter, unauthorizedEntryPoint)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        // Slack sends signed callbacks without JWT — verified by SlackSignatureVerifier
                        .requestMatchers("/api/v1/slack/actions").permitAll()
                        // Backlog #0-16: not authenticated() — a purpose token (tenant-less,
                        // valid for one operation) is denied here even if this service's
                        // JwtAuthFilter is ever given an accepted purpose.
                        .anyRequest().access(
                                SharedSecurityAutoConfiguration.authenticatedExceptPurposeTokens())
                )
                .build();
    }
}