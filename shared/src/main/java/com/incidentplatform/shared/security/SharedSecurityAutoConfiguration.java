package com.incidentplatform.shared.security;

import org.springframework.beans.factory.annotation.Value;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Auto-configuration that registers a default {@link SecurityFilterChain}
 * shared by all incident-platform services.
 *
 * <h2>What this configures</h2>
 * <ul>
 *   <li>Stateless session (JWT, no HTTP session)
 *   <li>CSRF disabled (stateless API, no browser form submissions)
 *   <li>Public paths: actuator health/info/prometheus, Swagger UI, /error
 *   <li>All other requests: require authentication
 *   <li>JWT filter registered before {@code UsernamePasswordAuthenticationFilter}
 *   <li>Security headers: X-Frame-Options DENY, HSTS, Referrer-Policy
 *   <li>CORS configured from {@code security.cors.allowed-origins} property
 * </ul>
 *
 * <h2>How services customise behaviour</h2>
 * A service that needs service-specific rules (e.g. additional public paths,
 * role-based access on specific endpoints) declares its own
 * {@link SecurityFilterChain} {@code @Bean}. Because this auto-configuration
 * is annotated {@link ConditionalOnMissingBean}, it backs off and the
 * service-specific chain takes full control.
 *
 * <p>The shared {@link CorsConfigurationSource} bean is also conditional —
 * a service may declare its own if it needs different CORS rules.
 *
 * <h2>Problems solved</h2>
 * Before this class, each of the 6 services had its own {@code SecurityConfig}
 * with ~50 lines of identical boilerplate. Changes to the security policy
 * (new header, updated CORS, new public endpoint) had to be applied 6 times.
 * This class is the single source of truth for platform-wide security policy.
 *
 * <p>Also fixes:
 * <ul>
 *   <li>Duplicate actuator {@code permitAll()} block in ingestion-service
 *   <li>Duplicate {@code JwtAuthFilter} {@code @Bean} in incident-service
 *   <li>Missing security headers in escalation, notification and oncall services
 *   <li>Duplicate public-path list between {@link JwtAuthFilter#shouldNotFilter}
 *       and per-service {@code SecurityConfig} — now defined once here and
 *       replicated in the filter via the same constant set
 * </ul>
 */
@AutoConfiguration
public class SharedSecurityAutoConfiguration {

    /**
     * Public paths that require no authentication across all services.
     * Referenced both here (Spring Security matcher) and in
     * {@link JwtAuthFilter#shouldNotFilter} to keep the two lists in sync
     * from a single source.
     */
    public static final String[] PUBLIC_PATHS = {
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    @Value("${security.cors.allowed-origins:http://localhost:4200}")
    private List<String> allowedOrigins;

    /**
     * Default {@link SecurityFilterChain} applied to every service that does
     * not declare its own. A service with additional rules should declare
     * its own {@code SecurityFilterChain} bean — this auto-configuration
     * will then back off.
     */
    /**
     * Default JwtAuthFilter bean with no-op revocation checker.
     * Used by all services except auth-service which overrides this bean
     * with @Primary and wires in TokenRevocationService::isRevoked.
     *
     * <p>@ConditionalOnMissingBean ensures auth-service's @Primary bean wins
     * without conflict.
     */
    @Bean
    @ConditionalOnMissingBean(JwtAuthFilter.class)
    public JwtAuthFilter jwtAuthFilter(JwtUtils jwtUtils) {
        return new JwtAuthFilter(jwtUtils); // no-op revocation: jti -> false
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain defaultSecurityFilterChain(
            HttpSecurity http,
            JwtAuthFilter jwtAuthFilter,
            UnauthorizedEntryPoint unauthorizedEntryPoint) throws Exception {

        return buildCommonSecurity(http, jwtAuthFilter, unauthorizedEntryPoint)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }

    /**
     * Applies platform-wide security settings common to every chain.
     * Extracted as a static helper so service-specific {@code SecurityConfig}
     * classes can call it and add their own authorization rules on top:
     *
     * <pre>{@code
     * @Bean
     * public SecurityFilterChain securityFilterChain(HttpSecurity http,
     *                                                JwtAuthFilter filter) throws Exception {
     *     return SharedSecurityAutoConfiguration.buildCommonSecurity(http, filter)
     *             .authorizeHttpRequests(auth -> auth
     *                     .requestMatchers(PUBLIC_PATHS).permitAll()
     *                     .requestMatchers("/api/v1/oncall/current")
     *                         .hasAnyRole(SERVICE, ADMIN)
     *                     .anyRequest().authenticated()
     *             )
     *             .build();
     * }
     * }</pre>
     */
    public static HttpSecurity buildCommonSecurity(
            HttpSecurity http,
            JwtAuthFilter jwtAuthFilter,
            UnauthorizedEntryPoint unauthorizedEntryPoint) throws Exception {

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthorizedEntryPoint)
                )
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .xssProtection(xss -> xss.disable())
                        .contentTypeOptions(content -> {})
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31_536_000)
                                .includeSubDomains(true))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy
                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )
                .addFilterBefore(jwtAuthFilter,
                        UsernamePasswordAuthenticationFilter.class);
    }

    /**
     * Default CORS configuration sourced from
     * {@code security.cors.allowed-origins} (comma-separated, default:
     * {@code http://localhost:4200}).
     *
     * <p>Set the property per environment:
     * <pre>
     *   # application.yml (dev)
     *   security.cors.allowed-origins: http://localhost:4200
     *
     *   # k8s/overlays/prod/app-config.yml
     *   security.cors.allowed-origins: https://app.incidentplatform.com
     * </pre>
     *
     * <p>A service that needs different CORS rules may declare its own
     * {@link CorsConfigurationSource} bean — this one will back off.
     */
    @Bean
    @ConditionalOnMissingBean(CorsConfigurationSource.class)
    public CorsConfigurationSource corsConfigurationSource() {
        final CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        final UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}