package com.incidentplatform.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link PayloadSizeLimitFilter} as a plain servlet filter —
 * not added into Spring Security's {@code SecurityFilterChain} (unlike
 * {@code JwtAuthFilter}, wired via {@code HttpSecurity.addFilterBefore}
 * in {@link SecurityConfig}) — with the highest possible precedence, so
 * it runs before Spring Security's own filter chain even starts.
 *
 * <p>This is deliberately a separate registration mechanism from
 * {@code SecurityConfig}: payload-size rejection is a resource-protection
 * concern that applies identically to every request regardless of
 * whether it will ultimately succeed or fail authentication — an
 * oversized body should never even reach JWT parsing, let alone
 * controller logic, whether or not the caller would have been
 * authorized.
 */
@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class PayloadSizeLimitFilterConfig {

    @Bean
    public FilterRegistrationBean<PayloadSizeLimitFilter> payloadSizeLimitFilter(
            IngestionProperties properties) {
        final FilterRegistrationBean<PayloadSizeLimitFilter> registration =
                new FilterRegistrationBean<>(
                        new PayloadSizeLimitFilter(properties.maxPayloadBytes()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/api/v1/alerts/*");
        return registration;
    }
}