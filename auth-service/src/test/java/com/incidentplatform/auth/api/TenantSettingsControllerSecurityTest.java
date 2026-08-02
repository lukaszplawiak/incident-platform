package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.dto.TenantSettingsDto;
import com.incidentplatform.auth.dto.UpdateTenantSettingsRequest;
import com.incidentplatform.auth.service.TenantSettingsService;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for {@link TenantSettingsController} — previously with
 * no test coverage of any kind, despite controlling a tenant-wide setting
 * ({@code mfaRequired}) that gates every user's login flow.
 *
 * <p>Both endpoints are {@code hasRole('ADMIN')} — no RESPONDER access at
 * all, same pattern as IntegrationController.
 *
 * <p>See TeamControllerSecurityTest for why {@link #principal} replaces
 * {@code @WithMockUser}.
 */
@WebMvcTest(TenantSettingsController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=auth-service",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE="
})
@DisplayName("TenantSettingsController — security")
class TenantSettingsControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TenantSettingsService tenantSettingsService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    @MockitoBean
    private ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService;

    @MockitoBean
    private TokenRevocationChecker tokenRevocationChecker;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private TenantSettingsDto buildSettingsDto(boolean mfaRequired) {
        return new TenantSettingsDto(TENANT_ID, mfaRequired);
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
        @DisplayName("GET /tenants/settings — 401")
        void getSettings_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/tenants/settings"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /tenants/settings — 401")
        void updateSettings_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/tenants/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new UpdateTenantSettingsRequest(true))))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── RESPONDER — 403 for every endpoint, no read access either ───────────
    //
    // Notably stricter than TeamController's read endpoints: even seeing
    // whether MFA is required tenant-wide is ADMIN-only.

    @Nested
    @DisplayName("RESPONDER role")
    class ResponderRole {

        @Test
        @DisplayName("GET /tenants/settings — 403")
        void getSettings_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/tenants/settings").with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /tenants/settings — 403")
        void updateSettings_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/tenants/settings")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new UpdateTenantSettingsRequest(true))))
                    .andExpect(status().isForbidden());
        }
    }

    // ── SERVICE / INGESTOR — 403 too ─────────────────────────────────────────

    @Nested
    @DisplayName("wrong role")
    class WrongRole {

        @Test
        @DisplayName("GET /tenants/settings — 403 for SERVICE")
        void getSettings_returns403ForService() throws Exception {
            mockMvc.perform(get("/api/v1/tenants/settings").with(principal("ROLE_SERVICE")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /tenants/settings — 403 for INGESTOR")
        void getSettings_returns403ForIngestor() throws Exception {
            mockMvc.perform(get("/api/v1/tenants/settings").with(principal("ROLE_INGESTOR")))
                    .andExpect(status().isForbidden());
        }
    }

    // ── ADMIN — 200 for every endpoint ──────────────────────────────────────

    @Nested
    @DisplayName("ADMIN role")
    class AdminRole {

        @Test
        @DisplayName("GET /tenants/settings — 200")
        void getSettings_returns200() throws Exception {
            given(tenantSettingsService.getSettings()).willReturn(buildSettingsDto(false));

            mockMvc.perform(get("/api/v1/tenants/settings").with(principal("ROLE_ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST /tenants/settings — 200, enabling mfaRequired")
        void updateSettings_returns200() throws Exception {
            given(tenantSettingsService.updateSettings(anyBoolean(), any()))
                    .willReturn(buildSettingsDto(true));

            mockMvc.perform(post("/api/v1/tenants/settings")
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new UpdateTenantSettingsRequest(true))))
                    .andExpect(status().isOk());
        }
    }
}