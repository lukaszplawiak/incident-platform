package com.incidentplatform.notification.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * notification-service security configuration.
 *
 * <p>Extends the platform baseline with a public path for Slack interactive
 * action callbacks. Slack sends signed POST requests to this endpoint without
 * a JWT token — authentication is handled by
 * {@code SlackSignatureVerifier} which validates the {@code X-Slack-Signature}
 * HMAC header instead.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter)
            throws Exception {
        return SharedSecurityAutoConfiguration.buildCommonSecurity(http, jwtAuthFilter)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        // Slack sends signed callbacks without JWT — verified by SlackSignatureVerifier
                        .requestMatchers("/api/v1/slack/actions").permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}