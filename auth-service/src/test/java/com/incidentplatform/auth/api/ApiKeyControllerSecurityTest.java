package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.domain.ApiKeyScope;
import com.incidentplatform.auth.domain.ApiKeyType;
import com.incidentplatform.auth.dto.ApiKeyCreatedResponse;
import com.incidentplatform.auth.dto.CreateApiKeyRequest;
import com.incidentplatform.auth.service.ApiKeyService;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
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
import org.springframework.http.HttpStatus;
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
 * Security tests for {@link ApiKeyController} — previously with no test
 * coverage of any kind.
 *
 * <h2>Why this controller has no @PreAuthorize</h2>
 * Deliberately, not an oversight — verified against {@link ApiKeyService}:
 * authorization here is finer-grained than a role gate can express (TENANT
 * keys ADMIN-only, PERSONAL keys any user but scope-limited to their own
 * role, revoke restricted to ADMIN-or-owner), so it's implemented entirely
 * in the service layer via {@link UserPrincipal#hasRole} checks and
 * {@link BusinessException} with {@link ErrorCodes#FORBIDDEN}. Every
 * endpoint here is reachable by any authenticated role at the HTTP layer —
 * the real authorization decision happens one layer down.
 *
 * <p>Detailed business-rule coverage (ownership checks, scope validation,
 * rate limits) already exists in {@code ApiKeyServiceTest} — not
 * duplicated here. This file covers what's specific to the HTTP boundary:
 * 401 without a token, and that a 403 thrown by the service as a
 * {@link BusinessException} actually propagates as HTTP 403 through
 * {@code GlobalExceptionHandler} rather than, say, a raw 500.
 *
 * <p>See TeamControllerSecurityTest for why {@link #principal} replaces
 * {@code @WithMockUser}.
 */
@WebMvcTest(ApiKeyController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=auth-service",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE="
})
@DisplayName("ApiKeyController — security")
class ApiKeyControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    @MockitoBean
    private ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService;

    @MockitoBean
    private TokenRevocationChecker tokenRevocationChecker;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID KEY_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private CreateApiKeyRequest buildCreateRequest(ApiKeyType type) {
        return new CreateApiKeyRequest(
                "my-key", type, List.of(ApiKeyScope.INCIDENTS_READ), null);
    }

    private ApiKeyCreatedResponse buildCreatedResponse() {
        return new ApiKeyCreatedResponse(
                KEY_ID, "my-key", ApiKeyType.PERSONAL,
                List.of("incidents:read"), "ipl_rawkeyvalue",
                null, Instant.now(), "Store this key securely.");
    }

    /** See class Javadoc / TeamControllerSecurityTest for why not @WithMockUser. */
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
        @DisplayName("POST /api-keys — 401")
        void createApiKey_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/api-keys")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    buildCreateRequest(ApiKeyType.PERSONAL))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /api-keys — 401")
        void listApiKeys_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/api-keys"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("DELETE /api-keys/{id} — 401")
        void revokeApiKey_returns401() throws Exception {
            mockMvc.perform(delete("/api/v1/api-keys/{id}", KEY_ID))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── Any authenticated role reaches the controller/service ──────────────
    //
    // No @PreAuthorize here by design (see class Javadoc) — RESPONDER and
    // ADMIN alike reach the service; the service is what decides.

    @Nested
    @DisplayName("any authenticated role reaches the service")
    class AuthenticatedAccess {

        @Test
        @DisplayName("POST /api-keys — 201 for RESPONDER creating a PERSONAL key")
        void createApiKey_respondersPersonalKey_returns201() throws Exception {
            given(apiKeyService.createApiKey(any(), any())).willReturn(buildCreatedResponse());

            mockMvc.perform(post("/api/v1/api-keys")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    buildCreateRequest(ApiKeyType.PERSONAL))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("POST /api-keys — 201 for ADMIN creating a TENANT key")
        void createApiKey_adminTenantKey_returns201() throws Exception {
            given(apiKeyService.createApiKey(any(), any())).willReturn(buildCreatedResponse());

            mockMvc.perform(post("/api/v1/api-keys")
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    buildCreateRequest(ApiKeyType.TENANT))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("GET /api-keys — 200 for RESPONDER (sees only own keys, decided by the service)")
        void listApiKeys_returns200ForResponder() throws Exception {
            given(apiKeyService.listApiKeys(any())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/api-keys").with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /api-keys — 200 for ADMIN (sees all tenant keys, decided by the service)")
        void listApiKeys_returns200ForAdmin() throws Exception {
            given(apiKeyService.listApiKeys(any())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/api-keys").with(principal("ROLE_ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("DELETE /api-keys/{id} — 204 for RESPONDER revoking their own key")
        void revokeApiKey_returns204() throws Exception {
            mockMvc.perform(delete("/api/v1/api-keys/{id}", KEY_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isNoContent());
        }
    }

    // ── Service-layer 403 propagates correctly through the HTTP boundary ───

    @Nested
    @DisplayName("service-layer authorization failures surface as 403")
    class ServiceLayerForbidden {

        @Test
        @DisplayName("POST /api-keys — 403 when RESPONDER attempts to create a TENANT key")
        void createApiKey_returns403WhenResponderCreatesTenantKey() throws Exception {
            given(apiKeyService.createApiKey(any(), any()))
                    .willThrow(new BusinessException(
                            ErrorCodes.FORBIDDEN,
                            "Only ADMIN users can create TENANT API keys",
                            HttpStatus.FORBIDDEN));

            mockMvc.perform(post("/api/v1/api-keys")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    buildCreateRequest(ApiKeyType.TENANT))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api-keys — 403 when a PERSONAL key requests a scope beyond the caller's role")
        void createApiKey_returns403WhenScopeExceedsRole() throws Exception {
            given(apiKeyService.createApiKey(any(), any()))
                    .willThrow(new BusinessException(
                            ErrorCodes.FORBIDDEN,
                            "Scope 'teams:write' exceeds your role permissions",
                            HttpStatus.FORBIDDEN));

            mockMvc.perform(post("/api/v1/api-keys")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateApiKeyRequest("my-key", ApiKeyType.PERSONAL,
                                            List.of(ApiKeyScope.TEAMS_WRITE), null))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /api-keys/{id} — 403 when RESPONDER attempts to revoke someone else's key")
        void revokeApiKey_returns403WhenNotOwner() throws Exception {
            org.mockito.BDDMockito.willThrow(new BusinessException(
                            ErrorCodes.FORBIDDEN,
                            "You can only revoke your own personal API keys",
                            HttpStatus.FORBIDDEN))
                    .given(apiKeyService).revokeApiKey(any(), any());

            mockMvc.perform(delete("/api/v1/api-keys/{id}", KEY_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }
    }
}