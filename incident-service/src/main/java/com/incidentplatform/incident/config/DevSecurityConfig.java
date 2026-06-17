package com.incidentplatform.incident.config;

import com.incidentplatform.incident.api.DevTokenController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for {@code /dev/**} endpoints.
 *
 * <p>Active <strong>only</strong> on {@code local} and {@code dev} profiles.
 * On {@code prod} (or any other profile) this entire class is not instantiated
 * by Spring — the {@code /dev/**} path falls through to the main
 * {@link SecurityConfig} which requires authentication for all requests.
 *
 * <p>This is a deliberate two-layer defence against accidental exposure:
 * <ol>
 *   <li>This class ({@code @Profile}) — the {@code /dev/**} permit rule only
 *       exists when the profile explicitly includes {@code local} or {@code dev}.
 *   <li>{@link DevTokenController} ({@code @Profile} + {@code @PostConstruct}
 *       fail-fast guard) — the token-generation endpoint itself refuses to start
 *       if it somehow runs outside the expected profiles.
 * </ol>
 *
 * <p>{@code @Order(1)} ensures this chain is evaluated before the main
 * {@link SecurityConfig} chain (which has default order). Spring Security
 * applies the first matching chain — so requests to {@code /dev/**} on local/dev
 * are handled here and never reach the main chain.
 */
@Configuration
@Profile({"local", "dev"})
public class DevSecurityConfig {

    /**
     * Permits all requests to {@code /dev/**} without authentication.
     * Scoped to {@code local} and {@code dev} profiles only.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/dev/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .build();
    }
}