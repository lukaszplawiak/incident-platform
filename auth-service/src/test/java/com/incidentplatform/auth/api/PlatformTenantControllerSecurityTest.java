package com.incidentplatform.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.auth.config.PlatformAccess;
import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.dto.ProvisionTenantRequest;
import com.incidentplatform.auth.dto.ProvisionTenantResponse;
import com.incidentplatform.auth.dto.TenantDto;
import com.incidentplatform.auth.service.TenantProvisioningService;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.ApiKeyAuthFilter.ApiKeyLookupResult;
import com.incidentplatform.shared.security.JwtProperties;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.ServiceNames;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may use the platform API (backlog #0-80): only an admin of the
 * platform-operator tenant with a JWT. Uses <em>real</em> tokens through the
 * <em>real</em> {@link SecurityConfig} and {@code JwtAuthFilter}, like
 * {@code IntrospectionTokenDenyByDefaultTest}, so the tenant and role checks
 * run on what a token actually carries, not on a hand-built principal.
 */
@WebMvcTest(PlatformTenantController.class)
@Import({SecurityConfig.class, PlatformAccess.class, UnauthorizedEntryPoint.class, JwtUtils.class,
        PlatformTenantControllerSecurityTest.JwtPropertiesConfig.class,
        PlatformTenantControllerSecurityTest.UnannotatedPlatformController.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "jwt.refresh-token-ttl=P30D",
        "spring.application.name=auth-service",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE=",
        "slack.encryption-key=c2xhY2sta2V5LTMyLWJ5dGVzLWZvci1kZXYtb25seSE="
})
@DisplayName("PlatformTenantController — security")
class PlatformTenantControllerSecurityTest {

    /** Real JwtUtils needs its properties; a @WebMvcTest slice does not bind them. */
    @TestConfiguration
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesConfig {
    }

    /**
     * A platform route with no {@code @PreAuthorize}: only SecurityConfig's
     * filter-chain rule protects it. Proves that layer on its own, which the
     * real controller cannot, since its annotations would refuse too.
     */
    @RestController
    static class UnannotatedPlatformController {
        @GetMapping("/api/v1/platform/probe")
        String probe() {
            return "ok";
        }
    }

    private static final String TENANTS = "/api/v1/platform/tenants";
    private static final String REINVITE = TENANTS + "/acme/admin-invite";
    private static final String ONE = TENANTS + "/acme";
    private static final UUID OPERATOR_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private TenantProvisioningService provisioningService;
    @MockitoBean private ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService;

    private String token(String tenantId, String... roles) {
        return jwtUtils.generateToken(OPERATOR_ID, tenantId, "ops@platform.test",
                List.of(roles), List.of(), List.of());
    }

    private String operatorAdmin() {
        return token(ReservedTenants.PLATFORM_OPERATOR, SecurityRoles.ROLE_ADMIN);
    }

    private String provisionBody() throws Exception {
        return objectMapper.writeValueAsString(
                new ProvisionTenantRequest("acme", "Acme Corp", "admin@acme.test"));
    }

    private MockHttpServletRequestBuilder provisionAs(String bearer) throws Exception {
        final MockHttpServletRequestBuilder request = post(TENANTS)
                .contentType(MediaType.APPLICATION_JSON).content(provisionBody());
        return bearer == null ? request : request.header("Authorization", "Bearer " + bearer);
    }

    // ── Allowed: admin of the platform-operator tenant ──────────────────────

    @Test
    @DisplayName("operator admin: POST creates the tenant — 201 with Location")
    void operatorAdminProvisions() throws Exception {
        final UUID adminId = UUID.randomUUID();
        given(provisioningService.provision(any(), any())).willReturn(
                new ProvisionTenantResponse("acme", "Acme Corp", adminId, "admin@acme.test"));

        mockMvc.perform(provisionAs(operatorAdmin()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost" + TENANTS + "/acme"))
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.adminUserId").value(adminId.toString()));

        final var principal = forClass(UserPrincipal.class);
        verify(provisioningService).provision(
                eq(new ProvisionTenantRequest("acme", "Acme Corp", "admin@acme.test")),
                principal.capture());
        assertThat(principal.getValue().userId()).isEqualTo(OPERATOR_ID);
        assertThat(principal.getValue().tenantId()).isEqualTo(ReservedTenants.PLATFORM_OPERATOR);
    }

    @Test
    @DisplayName("operator admin: GET one tenant — 200, 404 when missing")
    void operatorAdminGetsOne() throws Exception {
        given(provisioningService.get("acme")).willReturn(
                new TenantDto("acme", "Acme Corp", "admin@acme.test", false, Instant.now(), OPERATOR_ID));
        given(provisioningService.get("nope")).willThrow(
                new ResourceNotFoundException("Tenant", "nope"));

        mockMvc.perform(get(ONE).header("Authorization", "Bearer " + operatorAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.adminActive").value(false));
        mockMvc.perform(get(TENANTS + "/nope").header("Authorization", "Bearer " + operatorAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("operator admin: reissue — 202")
    void operatorAdminReissues() throws Exception {
        mockMvc.perform(post(REINVITE).header("Authorization", "Bearer " + operatorAdmin()))
                .andExpect(status().isAccepted());

        verify(provisioningService).reissueFirstAdminInvite(eq("acme"), any());
    }

    @Test
    @DisplayName("operator admin: list — 200, page size capped at 100, client sort ignored")
    void operatorAdminLists() throws Exception {
        given(provisioningService.list(any())).willReturn(new PageImpl<>(List.of(
                new TenantDto("acme", "Acme Corp", "admin@acme.test", true, Instant.now(), OPERATOR_ID)),
                PageRequest.of(0, 100), 1));

        mockMvc.perform(get(TENANTS).param("size", "1000").param("sort", "displayName,asc")
                        .header("Authorization", "Bearer " + operatorAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].tenantId").value("acme"))
                .andExpect(jsonPath("$.content[0].adminActive").value(true));

        final var pageable = forClass(Pageable.class);
        verify(provisioningService).list(pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(pageable.getValue().getSort().isSorted()).isFalse();
    }

    // ── Refused: everyone else ───────────────────────────────────────────────

    @Test
    @DisplayName("no token — 401 on every endpoint")
    void unauthenticated() throws Exception {
        mockMvc.perform(provisionAs(null)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(REINVITE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(TENANTS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ONE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(provisioningService);
    }

    @Test
    @DisplayName("admin of a customer tenant — 403 on every endpoint")
    void customerTenantAdminForbidden() throws Exception {
        final String customerAdmin = token("acme", SecurityRoles.ROLE_ADMIN);
        assertForbiddenEverywhere(customerAdmin);
    }

    @Test
    @DisplayName("non-admin of the platform-operator tenant — 403 on every endpoint")
    void operatorResponderForbidden() throws Exception {
        assertForbiddenEverywhere(token(ReservedTenants.PLATFORM_OPERATOR, SecurityRoles.ROLE_RESPONDER));
    }

    @Test
    @DisplayName("admin of the reserved 'system' tenant — 403")
    void systemTenantAdminForbidden() throws Exception {
        assertForbiddenEverywhere(token(ReservedTenants.LEGACY_SYSTEM, SecurityRoles.ROLE_ADMIN));
    }

    @Test
    @DisplayName("service token for auth-service in the platform-operator tenant — 403")
    void serviceTokenForbidden() throws Exception {
        assertForbiddenEverywhere(jwtUtils.generateServiceToken(
                "notification-service", ReservedTenants.PLATFORM_OPERATOR, ServiceNames.AUTH_SERVICE));
    }

    @Test
    @DisplayName("API key of an operator admin — 403: the platform API needs a JWT")
    void apiKeyForbidden() throws Exception {
        final UserPrincipal keyPrincipal = new UserPrincipal(OPERATOR_ID,
                ReservedTenants.PLATFORM_OPERATOR, "ops@platform.test",
                List.of(SecurityRoles.ROLE_ADMIN), List.of(), List.of(), true,
                List.of("incidents:read"), null);
        given(apiKeyLookupService.lookup(anyString(), any()))
                .willReturn(new ApiKeyLookupResult.Authenticated(keyPrincipal));

        assertForbiddenEverywhere("ipl_operator_personal_key");
    }

    private void assertForbiddenEverywhere(String bearer) throws Exception {
        mockMvc.perform(provisionAs(bearer)).andExpect(status().isForbidden());
        mockMvc.perform(post(REINVITE).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(TENANTS).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(ONE).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isForbidden());
        verifyNoInteractions(provisioningService);
    }

    @Test
    @DisplayName("the filter chain alone closes a platform route without @PreAuthorize")
    void filterChainRuleAlone() throws Exception {
        mockMvc.perform(get("/api/v1/platform/probe")
                        .header("Authorization", "Bearer " + token("acme", SecurityRoles.ROLE_ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/platform/probe")
                        .header("Authorization", "Bearer " + operatorAdmin()))
                .andExpect(status().isOk());
    }

    // ── Input and service errors reach the client as their status ───────────

    @Test
    @DisplayName("reissue refused by the service — 409 with its error code")
    void reissueConflict() throws Exception {
        willThrow(new BusinessException(ErrorCodes.BUSINESS_RULE_VIOLATION,
                "Tenant 'acme' already has an active admin", HttpStatus.CONFLICT))
                .given(provisioningService).reissueFirstAdminInvite(eq("acme"), any());

        mockMvc.perform(post(REINVITE).header("Authorization", "Bearer " + operatorAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.BUSINESS_RULE_VIOLATION));
    }

    @Test
    @DisplayName("malformed tenant id — 400 before the service is called")
    void malformedTenantId() throws Exception {
        mockMvc.perform(post(TENANTS).header("Authorization", "Bearer " + operatorAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ProvisionTenantRequest("Acme_Corp", "Acme", "admin@acme.test"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(provisioningService);
    }

    @Test
    @DisplayName("tenant already exists — 409")
    void duplicateTenant() throws Exception {
        willThrow(new BusinessException(ErrorCodes.ALREADY_EXISTS, "Tenant 'acme' already exists",
                HttpStatus.CONFLICT)).given(provisioningService).provision(any(), any());

        mockMvc.perform(provisionAs(operatorAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.ALREADY_EXISTS));
    }
}
