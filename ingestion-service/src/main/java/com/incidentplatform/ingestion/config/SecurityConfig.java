package com.incidentplatform.ingestion.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * ingestion-service security configuration.
 *
 * <p>Extends the platform baseline from
 * {@link SharedSecurityAutoConfiguration#buildCommonSecurity} with
 * service-specific public path rules.
 *
 * <p>CORS and security headers are provided by the shared auto-configuration
 * {@link SharedSecurityAutoConfiguration#corsConfigurationSource()} and
 * {@link SharedSecurityAutoConfiguration#buildCommonSecurity} respectively.
 * Override {@code security.cors.allowed-origins} in {@code application.yml}
 * to change allowed origins per environment.
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
        return SharedSecurityAutoConfiguration.buildCommonSecurity(http, jwtAuthFilter, unauthorizedEntryPoint)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}