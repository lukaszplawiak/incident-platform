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
                        .anyRequest().authenticated()
                )
                .build();
    }
}