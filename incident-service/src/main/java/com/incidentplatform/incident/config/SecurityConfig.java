package com.incidentplatform.incident.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * incident-service security configuration.
 *
 * <p>Extends the platform baseline from
 * {@link SharedSecurityAutoConfiguration#buildCommonSecurity} with a
 * service-specific public path for WebSocket connections ({@code /ws/**}).
 *
 * <p>Note: the duplicate {@code JwtAuthFilter @Bean} that previously existed
 * in this class has been removed. {@link JwtAuthFilter} is a {@code @Component}
 * in {@code shared} — Spring creates exactly one instance automatically.
 * Declaring it as a local {@code @Bean} created a second instance which was
 * registered as a servlet filter by Spring Boot in addition to being added
 * to the security filter chain — causing the filter to execute twice per
 * request.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        return SharedSecurityAutoConfiguration.buildCommonSecurity(http, jwtAuthFilter)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        // WebSocket handshake endpoint — auth handled inside STOMP protocol
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}