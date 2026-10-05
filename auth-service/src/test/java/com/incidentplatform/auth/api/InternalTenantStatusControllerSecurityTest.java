package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.service.TenantAccessService;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.IntrospectionPrincipal;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.ServicePrincipal;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.TokenRevocationChecker;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link InternalTenantStatusController} (backlog #0-82, step 2): a service
 * token reads its own tenant's access; nothing else reaches it.
 */
@WebMvcTest(InternalTenantStatusController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=auth-service",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE=",
        "slack.encryption-key=c2xhY2sta2V5LTMyLWJ5dGVzLWZvci1kZXYtb25seSE="
})
@DisplayName("InternalTenantStatusController — security")
class InternalTenantStatusControllerSecurityTest {

    private static final String PATH = "/api/v1/internal/tenant-status";
    private static final String TENANT_ID = "test-tenant";

    @Autowired
    private MockMvc mockMvc;

    /** Also the chain's TenantStatusProvider. */
    @MockitoBean
    private TenantAccessService tenantAccessService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    @MockitoBean
    private ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService;

    @MockitoBean
    private TokenRevocationChecker tokenRevocationChecker;

    @BeforeEach
    void setUp() {
        // What JwtAuthFilter sets from the token's signed tenant claim.
        TenantContext.set(TENANT_ID);
        given(tenantAccessService.accessOf(anyString())).willReturn(TenantAccess.FULL);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static RequestPostProcessor service() {
        return authentication(new UsernamePasswordAuthenticationToken(
                new ServicePrincipal("notification-service", TENANT_ID), null,
                List.of(new SimpleGrantedAuthority(SecurityRoles.ROLE_SERVICE))));
    }

    private static RequestPostProcessor user(boolean apiKey) {
        final UserPrincipal principal = new UserPrincipal(UUID.randomUUID(), TENANT_ID, "admin@acme.test",
                List.of(SecurityRoles.ROLE_ADMIN), List.of(), List.of(), apiKey, List.of(), null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                principal.getAuthorities()));
    }

    @ParameterizedTest
    @EnumSource(TenantAccess.class)
    @DisplayName("a service token gets its tenant's access")
    void serviceGetsAccess(TenantAccess access) throws Exception {
        given(tenantAccessService.accessOf(TENANT_ID)).willReturn(access);

        mockMvc.perform(get(PATH).with(service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access").value(access.name()))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.mode").doesNotExist());
    }

    @Test
    @DisplayName("no credentials: 401")
    void anonymous() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        then(tenantAccessService).should(never()).accessOf(TENANT_ID);
    }

    @Test
    @DisplayName("an admin's JWT: 403 — service-only, not a higher grade of admin")
    void adminRefused() throws Exception {
        mockMvc.perform(get(PATH).with(user(false))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an API key: 403")
    void apiKeyRefused() throws Exception {
        mockMvc.perform(get(PATH).with(user(true))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a purpose token (no tenant): 403")
    void purposeTokenRefused() throws Exception {
        mockMvc.perform(get(PATH).with(authentication(new UsernamePasswordAuthenticationToken(
                        new IntrospectionPrincipal("ingestion-service"), null,
                        List.of(new SimpleGrantedAuthority(SecurityRoles.ROLE_API_KEY_INTROSPECTION))))))
                .andExpect(status().isForbidden());
    }
}
