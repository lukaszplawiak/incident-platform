package com.incidentplatform.oncall.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * oncall-service security configuration.
 *
 * <p>Extends the platform baseline with role-based access control on the
 * {@code /api/v1/oncall/current} endpoint — restricted to
 * {@code ROLE_SERVICE} (inter-service calls from notification/escalation)
 * and {@code ROLE_ADMIN} only.
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
                        .requestMatchers("/api/v1/oncall/current")
                        .hasAnyRole(SecurityRoles.SERVICE, SecurityRoles.ADMIN)
                        .anyRequest().authenticated()
                )
                .build();
    }
}