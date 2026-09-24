package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.dto.InstallSlackWorkspaceRequest;
import com.incidentplatform.auth.dto.SlackWorkspaceDto;
import com.incidentplatform.auth.dto.SlackWorkspaceInternalDto;
import com.incidentplatform.auth.service.SlackWorkspaceService;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for {@link SlackWorkspaceController} (backlog #0-21/#0-30).
 *
 * <p>The three admin endpoints are {@code hasRole('ADMIN')}, mirroring
 * {@code IntegrationControllerSecurityTest} exactly (same trust level: this
 * holds a live credential). The internal endpoint is the interesting case —
 * {@code hasRole('SERVICE')} only, and it must reject an ADMIN user token
 * just as firmly as the admin endpoints reject a SERVICE token, proving the
 * split is enforced both ways, not just one.
 *
 * <p>As with {@code OncallScheduleControllerSecurityTest}'s equivalent
 * ROLE_SERVICE endpoint, {@code principal("ROLE_SERVICE")} (a
 * {@link UserPrincipal} carrying that authority) is enough to exercise
 * {@code @PreAuthorize("hasRole('SERVICE')")} here — the annotation checks
 * the granted authority, not the concrete principal type. The real caller
 * is a {@link ServicePrincipal}, built by {@link JwtAuthFilter} from a
 * service token whose {@code aud} names auth-service; that audience check
 * happens in {@link JwtAuthFilter} itself; see {@code JwtAuthFilter}'s own
 * unit tests for that layer.
 */
@WebMvcTest(SlackWorkspaceController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=auth-service",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE=",
        "slack.encryption-key=c2xhY2sta2V5LTMyLWJ5dGVzLWZvci1kZXYtb25seSE="
})
@DisplayName("SlackWorkspaceController — security")
class SlackWorkspaceControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SlackWorkspaceService slackWorkspaceService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    @MockitoBean
    private ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService;

    @MockitoBean
    private TokenRevocationChecker tokenRevocationChecker;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private InstallSlackWorkspaceRequest buildInstallRequest() {
        return new InstallSlackWorkspaceRequest(
                "T0123456", "xoxb-test-token", null, "#incidents", false);
    }

    private SlackWorkspaceDto buildDto() {
        return new SlackWorkspaceDto(
                WORKSPACE_ID, "T0123456", null, "#incidents", false,
                Instant.now(), true);
    }

    private SlackWorkspaceInternalDto buildInternalDto() {
        return new SlackWorkspaceInternalDto(
                "xoxb-test-token", "#incidents", false, null);
    }

    /** See IntegrationControllerSecurityTest's identical helper. */
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
        @DisplayName("POST /slack-workspace — 401")
        void install_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/slack-workspace")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildInstallRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /slack-workspace — 401")
        void get_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/slack-workspace"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("DELETE /slack-workspace/{id} — 401")
        void revoke_returns401() throws Exception {
            mockMvc.perform(delete("/api/v1/slack-workspace/{id}", WORKSPACE_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /internal/slack-workspace — 401")
        void internalGet_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/internal/slack-workspace"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── RESPONDER — 403 for every endpoint ──────────────────────────────────

    @Nested
    @DisplayName("RESPONDER role")
    class ResponderRole {

        @Test
        @DisplayName("POST /slack-workspace — 403")
        void install_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/slack-workspace")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildInstallRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /slack-workspace — 403")
        void get_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/slack-workspace").with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /slack-workspace/{id} — 403")
        void revoke_returns403() throws Exception {
            mockMvc.perform(delete("/api/v1/slack-workspace/{id}", WORKSPACE_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /internal/slack-workspace — 403 (not a service caller)")
        void internalGet_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/internal/slack-workspace")
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }
    }

    // ── ADMIN — admin endpoints 2xx, internal endpoint still 403 ────────────

    @Nested
    @DisplayName("ADMIN role")
    class AdminRole {

        @Test
        @DisplayName("POST /slack-workspace — 201")
        void install_returns201() throws Exception {
            given(slackWorkspaceService.install(any(), any())).willReturn(buildDto());

            mockMvc.perform(post("/api/v1/slack-workspace")
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildInstallRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("GET /slack-workspace — 200")
        void get_returns200() throws Exception {
            given(slackWorkspaceService.get()).willReturn(buildDto());

            mockMvc.perform(get("/api/v1/slack-workspace").with(principal("ROLE_ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("DELETE /slack-workspace/{id} — 204")
        void revoke_returns204() throws Exception {
            mockMvc.perform(delete("/api/v1/slack-workspace/{id}", WORKSPACE_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("GET /internal/slack-workspace — 403 even for ADMIN " +
                "(the internal endpoint is service-only, not a higher-privilege escalation of admin access)")
        void internalGet_returns403ForAdmin() throws Exception {
            mockMvc.perform(get("/api/v1/internal/slack-workspace")
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isForbidden());
        }
    }

    // ── SERVICE — only the internal endpoint accepts it ─────────────────────

    @Nested
    @DisplayName("SERVICE role")
    class ServiceRole {

        @Test
        @DisplayName("GET /internal/slack-workspace — 200")
        void internalGet_returns200() throws Exception {
            given(slackWorkspaceService.getForServiceRead())
                    .willReturn(Optional.of(buildInternalDto()));

            mockMvc.perform(get("/api/v1/internal/slack-workspace")
                            .with(principal("ROLE_SERVICE")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /internal/slack-workspace — 404 when tenant has no active workspace")
        void internalGet_returns404WhenAbsent() throws Exception {
            given(slackWorkspaceService.getForServiceRead()).willReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/internal/slack-workspace")
                            .with(principal("ROLE_SERVICE")))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("POST /slack-workspace — 403 for SERVICE (admin endpoints reject it too)")
        void install_returns403ForService() throws Exception {
            mockMvc.perform(post("/api/v1/slack-workspace")
                            .with(principal("ROLE_SERVICE"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildInstallRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /slack-workspace — 403 for SERVICE")
        void get_returns403ForService() throws Exception {
            mockMvc.perform(get("/api/v1/slack-workspace").with(principal("ROLE_SERVICE")))
                    .andExpect(status().isForbidden());
        }
    }
}
