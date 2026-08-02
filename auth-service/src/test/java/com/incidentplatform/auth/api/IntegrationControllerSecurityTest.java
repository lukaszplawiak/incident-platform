package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.dto.CreateIntegrationRequest;
import com.incidentplatform.auth.dto.IntegrationCreatedResponse;
import com.incidentplatform.auth.service.IntegrationService;
import com.incidentplatform.shared.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for {@link IntegrationController} — previously with no
 * test coverage of any kind.
 *
 * <p>All three endpoints are {@code hasRole('ROLE_ADMIN')} — the simplest
 * access pattern of the controllers covered so far. No RESPONDER access
 * of any kind, not even read-only (unlike TeamController's list/get,
 * which are open to any authenticated user). This makes sense given what
 * an Integration represents: it holds an API key that lets an external
 * system (Prometheus, Wazuh) inject incidents into a team's on-call
 * routing chain — a higher-trust artifact than team membership.
 *
 * <p>See TeamControllerSecurityTest for why {@link #principal} replaces
 * {@code @WithMockUser}.
 */
@WebMvcTest(IntegrationController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=auth-service",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE="
})
@DisplayName("IntegrationController — security")
class IntegrationControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private IntegrationService integrationService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    @MockitoBean
    private ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService;

    @MockitoBean
    private TokenRevocationChecker tokenRevocationChecker;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID INTEGRATION_ID = UUID.randomUUID();
    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private CreateIntegrationRequest buildCreateRequest() {
        return new CreateIntegrationRequest("Prod Prometheus", "prometheus", TEAM_ID, "desc");
    }

    private IntegrationCreatedResponse buildCreatedResponse() {
        return new IntegrationCreatedResponse(
                INTEGRATION_ID, "Prod Prometheus", "prometheus", TEAM_ID, "Platform Team",
                "ipl_rawkeyvalue", "desc", Instant.now(),
                "Configure your monitoring system with this API key.");
    }

    /** See TeamControllerSecurityTest's identical helper for why not @WithMockUser. */
    private static RequestPostProcessor principal(String... roles) {
        final UserPrincipal userPrincipal = new UserPrincipal(
                USER_ID, TENANT_ID, "user@acme.com", List.of(roles), List.of());
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
        @DisplayName("POST /integrations — 401")
        void createIntegration_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/integrations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCreateRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /integrations — 401")
        void listIntegrations_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/integrations"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("DELETE /integrations/{id} — 401")
        void revokeIntegration_returns401() throws Exception {
            mockMvc.perform(delete("/api/v1/integrations/{id}", INTEGRATION_ID))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── RESPONDER — 403 for every endpoint (no read access at all) ─────────

    @Nested
    @DisplayName("RESPONDER role")
    class ResponderRole {

        @Test
        @DisplayName("POST /integrations — 403")
        void createIntegration_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/integrations")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCreateRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /integrations — 403 (unlike TeamController, no read access for RESPONDER)")
        void listIntegrations_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/integrations").with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /integrations/{id} — 403")
        void revokeIntegration_returns403() throws Exception {
            mockMvc.perform(delete("/api/v1/integrations/{id}", INTEGRATION_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }
    }

    // ── SERVICE / INGESTOR — 403 too, not just RESPONDER ────────────────────

    @Nested
    @DisplayName("wrong role")
    class WrongRole {

        @Test
        @DisplayName("GET /integrations — 403 for SERVICE")
        void listIntegrations_returns403ForService() throws Exception {
            mockMvc.perform(get("/api/v1/integrations").with(principal("ROLE_SERVICE")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /integrations — 403 for INGESTOR")
        void listIntegrations_returns403ForIngestor() throws Exception {
            mockMvc.perform(get("/api/v1/integrations").with(principal("ROLE_INGESTOR")))
                    .andExpect(status().isForbidden());
        }
    }

    // ── ADMIN — 2xx for every endpoint ──────────────────────────────────────

    @Nested
    @DisplayName("ADMIN role")
    class AdminRole {

        @Test
        @DisplayName("POST /integrations — 201")
        void createIntegration_returns201() throws Exception {
            given(integrationService.createIntegration(any(), any()))
                    .willReturn(buildCreatedResponse());

            mockMvc.perform(post("/api/v1/integrations")
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCreateRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("GET /integrations — 200")
        void listIntegrations_returns200() throws Exception {
            given(integrationService.listIntegrations()).willReturn(List.of());

            mockMvc.perform(get("/api/v1/integrations").with(principal("ROLE_ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("DELETE /integrations/{id} — 204")
        void revokeIntegration_returns204() throws Exception {
            mockMvc.perform(delete("/api/v1/integrations/{id}", INTEGRATION_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNoContent());
        }
    }
}