package com.incidentplatform.ingestion.config;

import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import com.incidentplatform.shared.security.TokenRevocationChecker;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
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
 *
 * <h2>Fixed (backlog #0-16): Integration API keys are accepted</h2>
 * This chain replaces the shared default, and the shared default is the only
 * place {@link ApiKeyAuthFilter} used to be added — so ingestion-service
 * never ran it, and every alert source needed a JWT. The filter is now added
 * here, before {@link JwtAuthFilter}, with {@code RemoteApiKeyLookupService}
 * (introspection into auth-service) behind it. That is how external alert
 * sources — a tenant's Alertmanager, Wazuh, the platform's own Alertmanager
 * posting into the operator tenant — authenticate.
 *
 * <h2>No service tokens</h2>
 * {@link #jwtAuthFilter} is declared here with no service-token audience, so
 * this service accepts none (fail closed). The only service-token caller used
 * to be the platform's Alertmanager, with a 30-day {@code ROLE_SERVICE} token
 * for tenant "system" that could not be revoked, and ingest accepted any
 * {@code ROLE_SERVICE} token for the tenant; both are gone with backlog #0-16.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtUtils jwtUtils,
                                       TokenRevocationChecker revocationChecker) {
        return new JwtAuthFilter(jwtUtils, revocationChecker);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   ApiKeyAuthFilter apiKeyAuthFilter,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   UnauthorizedEntryPoint unauthorizedEntryPoint)
            throws Exception {
        return SharedSecurityAutoConfiguration.buildCommonSecurity(http, jwtAuthFilter, unauthorizedEntryPoint)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .addFilterBefore(apiKeyAuthFilter, JwtAuthFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        .anyRequest().access(
                                SharedSecurityAutoConfiguration.authenticatedExceptPurposeTokens())
                )
                .build();
    }
}
