package com.incidentplatform.postmortem.api;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Shared {@code @SpringBootApplication} for all {@code @WebMvcTest} classes
 * in the {@code postmortem.api} package.
 *
 * <h2>Why a shared class</h2>
 * {@code @WebMvcTest} looks for a {@code @SpringBootConfiguration} in the
 * package hierarchy. With one nested {@code TestApplication} per test class,
 * adding a second test class to this package made the search fail in every
 * test with {@code IllegalStateException: Found multiple
 * @SpringBootConfiguration}. A single top-level class gives every
 * {@code @WebMvcTest} here the same bootstrap configuration, with no test
 * depending on another test's internals. Same solution as auth-service's
 * {@code AuthApiTestApplication}.
 *
 * <h2>Why scanning is restricted</h2>
 * {@code PostmortemServiceApplication} carries a broad {@code @ComponentScan}
 * that pulls in {@code GeminiClientImpl} (needs {@code RestClient.Builder}),
 * {@code ShedLockConfig} (needs {@code DataSource}) and Kafka beans — none
 * available in the web slice. This class scans only the packages needed for
 * the web and security layers.
 */
@SpringBootApplication(scanBasePackages = {
        "com.incidentplatform.postmortem.api",
        "com.incidentplatform.postmortem.config",
        "com.incidentplatform.shared.security",
        "com.incidentplatform.shared.exception",
        "com.incidentplatform.shared.observability"
})
public class PostmortemApiTestApplication {

    /**
     * A real {@link JwtAuthFilter} wired with the test's mocked {@link JwtUtils}.
     */
    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtUtils jwtUtils) {
        return new JwtAuthFilter(jwtUtils);
    }
}
