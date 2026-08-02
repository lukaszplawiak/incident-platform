package com.incidentplatform.ingestion.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.ingestion.config.SecurityConfig;
import com.incidentplatform.ingestion.ratelimit.RateLimitResult;
import com.incidentplatform.ingestion.ratelimit.RateLimitingService;
import com.incidentplatform.ingestion.service.AlertIngestionService;
import com.incidentplatform.ingestion.service.IngestionSummary;
import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for {@link AlertIngestionController} — previously with
 * no test coverage of any kind.
 *
 * <p>Written after fixing a real, confirmed bug: {@code POST /{source}}'s
 * 4th @PreAuthorize OR-branch referenced a bean
 * ({@code @apiKeyAuthorizationService}) that does not exist anywhere in
 * the codebase. Fixed to call {@link UserPrincipal#hasScope} directly —
 * see {@link AlertIngestionController}'s class Javadoc for the full
 * account, including why this wasn't just a theoretical bug: TENANT-type
 * Integration API keys (what Prometheus/Wazuh/Grafana actually use to
 * call this endpoint) get ROLE_RESPONDER, not INGESTOR/ADMIN/SERVICE, so
 * they depended entirely on the broken branch.
 *
 * <p>{@code apiKeyPrincipal(...)} below builds a principal shaped like
 * what {@code ApiKeyLookupServiceImpl} actually produces for an
 * Integration key (isApiKey=true, ROLE_RESPONDER, granted scopes) — as
 * close as a WebMvcTest can get to reproducing the real authentication
 * path without standing up the full API-key filter chain.
 *
 * <p>See TeamControllerSecurityTest (auth-service) for why
 * {@code @WithMockUser} isn't used — same reasoning applies here, this
 * controller also reads {@code @AuthenticationPrincipal UserPrincipal}.
 */
@WebMvcTest(AlertIngestionController.class)
@ContextConfiguration(classes = AlertIngestionControllerSecurityTest.TestApplication.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=ingestion-service"
})
@DisplayName("AlertIngestionController — security")
class AlertIngestionControllerSecurityTest {

    @SpringBootApplication(scanBasePackages = {
            "com.incidentplatform.ingestion.api",
            "com.incidentplatform.ingestion.config",
            "com.incidentplatform.shared.security",
            "com.incidentplatform.shared.exception"
    })
    static class TestApplication {

        @org.springframework.context.annotation.Bean
        public JwtAuthFilter jwtAuthFilter(JwtUtils jwtUtils) {
            return new JwtAuthFilter(jwtUtils);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AlertIngestionService alertIngestionService;

    @MockitoBean
    private RateLimitingService rateLimitingService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
        // Every test that reaches the service goes through the rate
        // limiter first — default to "allowed" so tests only need to
        // override this when specifically testing rate limiting (not
        // done here, out of scope for a security test).
        given(rateLimitingService.tryConsume(anyString(), anyString()))
                .willReturn(RateLimitResult.permit());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static final String PROMETHEUS_PAYLOAD = """
            {
              "version": "4",
              "status": "firing",
              "alerts": []
            }""";

    private IngestionSummary buildSummary() {
        return IngestionSummary.of(0, 0, 0, 0, 0);
    }

    /**
     * JWT-shaped principal — role-based, isApiKey=false, no scopes. See
     * TeamControllerSecurityTest for why this replaces {@code @WithMockUser}.
     */
    private static RequestPostProcessor principal(String... roles) {
        final UserPrincipal userPrincipal = new UserPrincipal(
                USER_ID, TENANT_ID, "user@acme.com", List.of(roles), List.of());
        return authenticationFor(userPrincipal, roles);
    }

    /**
     * API-key-shaped principal, matching what
     * {@code ApiKeyLookupServiceImpl.buildPrincipal} actually produces for
     * a TENANT-type Integration key: ROLE_RESPONDER, isApiKey=true, and
     * whatever scopes were granted to the key.
     */
    private static RequestPostProcessor apiKeyPrincipal(String... scopes) {
        final UserPrincipal userPrincipal = new UserPrincipal(
                USER_ID, TENANT_ID, "api-key:prometheus-integration",
                List.of("ROLE_RESPONDER"), List.of(), true, List.of(scopes));
        return authenticationFor(userPrincipal, "ROLE_RESPONDER");
    }

    private static RequestPostProcessor authenticationFor(UserPrincipal userPrincipal, String... roles) {
        final List<GrantedAuthority> authorities = List.of(roles).stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(
                userPrincipal, null, authorities));
    }

    // ── Unauthenticated — 401 for every endpoint ────────────────────────────

    @Nested
    @DisplayName("unauthenticated requests")
    class Unauthenticated {

        @Test
        @DisplayName("POST /alerts/{source} — 401")
        void ingestAlerts_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/alerts/prometheus")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(PROMETHEUS_PAYLOAD))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /alerts/sources — 401")
        void getAvailableSources_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/alerts/sources"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── POST /{source} — explicit roles ─────────────────────────────────────

    @Nested
    @DisplayName("POST /alerts/{source} — role-based access")
    class IngestRoleBased {

        @Test
        @DisplayName("200 for ROLE_INGESTOR")
        void returns200ForIngestor() throws Exception {
            given(alertIngestionService.ingest(any(), any(), any(), any()))
                    .willReturn(buildSummary());

            mockMvc.perform(post("/api/v1/alerts/prometheus")
                            .with(principal("ROLE_INGESTOR"))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(PROMETHEUS_PAYLOAD))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("200 for ROLE_ADMIN")
        void returns200ForAdmin() throws Exception {
            given(alertIngestionService.ingest(any(), any(), any(), any()))
                    .willReturn(buildSummary());

            mockMvc.perform(post("/api/v1/alerts/prometheus")
                            .with(principal("ROLE_ADMIN"))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(PROMETHEUS_PAYLOAD))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("200 for ROLE_SERVICE")
        void returns200ForService() throws Exception {
            given(alertIngestionService.ingest(any(), any(), any(), any()))
                    .willReturn(buildSummary());

            mockMvc.perform(post("/api/v1/alerts/prometheus")
                            .with(principal("ROLE_SERVICE"))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(PROMETHEUS_PAYLOAD))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("403 for plain ROLE_RESPONDER with no API-key scopes")
        void returns403ForResponderWithoutScope() throws Exception {
            mockMvc.perform(post("/api/v1/alerts/prometheus")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(PROMETHEUS_PAYLOAD))
                    .andExpect(status().isForbidden());
        }
    }

    // ── POST /{source} — API-key scope path (the bug this file exists for) ─

    @Nested
    @DisplayName("POST /alerts/{source} — API-key scope-based access")
    class IngestScopeBased {

        @Test
        @DisplayName("200 for an API-key principal granted the alerts:ingest scope " +
                "— regression test for the missing @apiKeyAuthorizationService bean")
        void returns200ForApiKeyWithScope() throws Exception {
            // This is exactly the shape ApiKeyLookupServiceImpl produces for
            // a real Prometheus/Wazuh Integration key: ROLE_RESPONDER (not
            // INGESTOR/ADMIN/SERVICE), isApiKey=true, scopes=[alerts:ingest].
            // Before the fix, this request would have hit the
            // nonexistent @apiKeyAuthorizationService bean and failed.
            given(alertIngestionService.ingest(any(), any(), any(), any()))
                    .willReturn(buildSummary());

            mockMvc.perform(post("/api/v1/alerts/prometheus")
                            .with(apiKeyPrincipal("alerts:ingest"))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(PROMETHEUS_PAYLOAD))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("403 for an API-key principal without the alerts:ingest scope")
        void returns403ForApiKeyWithoutScope() throws Exception {
            mockMvc.perform(post("/api/v1/alerts/prometheus")
                            .with(apiKeyPrincipal("incidents:read"))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(PROMETHEUS_PAYLOAD))
                    .andExpect(status().isForbidden());
        }
    }

    // ── GET /sources ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /alerts/sources — role-based access")
    class AvailableSources {

        @Test
        @DisplayName("200 for ROLE_INGESTOR")
        void returns200ForIngestor() throws Exception {
            given(alertIngestionService.getAvailableSources())
                    .willReturn(List.of("prometheus", "wazuh", "generic"));

            mockMvc.perform(get("/api/v1/alerts/sources").with(principal("ROLE_INGESTOR")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("200 for ROLE_ADMIN")
        void returns200ForAdmin() throws Exception {
            given(alertIngestionService.getAvailableSources()).willReturn(List.of());

            mockMvc.perform(get("/api/v1/alerts/sources").with(principal("ROLE_ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("200 for ROLE_RESPONDER")
        void returns200ForResponder() throws Exception {
            given(alertIngestionService.getAvailableSources()).willReturn(List.of());

            mockMvc.perform(get("/api/v1/alerts/sources").with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("403 for ROLE_SERVICE (not in the allowed list for this endpoint)")
        void returns403ForService() throws Exception {
            mockMvc.perform(get("/api/v1/alerts/sources").with(principal("ROLE_SERVICE")))
                    .andExpect(status().isForbidden());
        }
    }
}