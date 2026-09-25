package com.incidentplatform.ingestion.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.JwtProperties;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ServiceNames;
import com.incidentplatform.shared.security.TokenPurposes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sends <em>real</em> tokens through the {@link JwtAuthFilter} that
 * ingestion-service's {@link SecurityConfig} builds (backlog #0-16, in the
 * style of shared's {@code ServiceTokenAuthenticationTest}): the filter has no
 * service-token audience and no accepted purposes, so no service token of any
 * kind authenticates here — the old Alertmanager path (a {@code ROLE_SERVICE}
 * token aimed at ingestion-service) included. User tokens still work.
 */
@DisplayName("ingestion-service SecurityConfig — JwtAuthFilter accepts no service tokens")
class SecurityConfigTest {

    private JwtUtils jwtUtils;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtUtils = new JwtUtils(new JwtProperties(
                "x".repeat(64), Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofDays(30)));
        filter = new SecurityConfig().jwtAuthFilter(jwtUtils, jti -> false);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Authentication authenticate(String token) throws Exception {
        final MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/alerts/prometheus");
        request.addHeader("Authorization", "Bearer " + token);
        final AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                seen.set(SecurityContextHolder.getContext().getAuthentication()));
        return seen.get();
    }

    @Test
    @DisplayName("a tenant-bound service token addressed to ingestion-service is not accepted")
    void serviceTokenRejected() throws Exception {
        final String token = jwtUtils.generateServiceToken(
                ServiceNames.INCIDENT_SERVICE, "acme", "ingestion-service");

        assertThat(authenticate(token)).isNull();
    }

    @Test
    @DisplayName("a purpose token is not accepted")
    void purposeTokenRejected() throws Exception {
        final String token = jwtUtils.generatePurposeToken(
                "ingestion-service", TokenPurposes.API_KEY_INTROSPECTION, "ingestion-service");

        assertThat(authenticate(token)).isNull();
    }

    @Test
    @DisplayName("a user token still authenticates")
    void userTokenAccepted() throws Exception {
        final String token = jwtUtils.generateToken(UUID.randomUUID(), "acme", "a@acme.com",
                List.of("ROLE_INGESTOR"), List.of(), List.of());

        assertThat(authenticate(token)).isNotNull();
    }
}
