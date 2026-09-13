package com.incidentplatform.incident.config;

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
 * incident-service security configuration.
 *
 * <p>Extends the platform baseline from
 * {@link SharedSecurityAutoConfiguration#buildCommonSecurity} with a
 * service-specific public path for WebSocket connections ({@code /ws/**}).
 * That public path is only the HTTP-level handshake — a second,
 * independent authentication layer for the actual STOMP traffic exists
 * in {@code StompAuthChannelInterceptor} (registered via
 * {@code WebSocketConfig.configureClientInboundChannel}); see that
 * class's own Javadoc for the full account.
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
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   UnauthorizedEntryPoint unauthorizedEntryPoint)
            throws Exception {
        return SharedSecurityAutoConfiguration.buildCommonSecurity(http, jwtAuthFilter, unauthorizedEntryPoint)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        // WebSocket handshake endpoint — a browser's native
                        // WebSocket handshake cannot carry a custom
                        // Authorization header, so this HTTP-level permitAll()
                        // is correct and necessary. Authentication genuinely
                        // happens afterward, at the STOMP frame level, via
                        // StompAuthChannelInterceptor (registered in
                        // WebSocketConfig.configureClientInboundChannel) —
                        // see that class's own Javadoc for the full account
                        // of the gap this closed: this exact comment used to
                        // claim the same thing while no such mechanism
                        // actually existed, leaving /ws/** fully
                        // unauthenticated end to end.
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}