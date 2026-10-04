package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.dto.TenantSettingsDto;
import com.incidentplatform.auth.service.TenantAccessService;
import com.incidentplatform.auth.service.TenantSettingsService;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * auth-service's own filter chain refuses a suspended tenant's requests
 * (backlog #0-82): {@code TenantStatusFilter} from {@code shared}, added by
 * {@code buildCommonSecurity} to the chain auth-service declares itself, with
 * auth-service's {@link TenantAccessService} as the status and its
 * {@code tenant-status.read-only.allowed-writes} from application.yml.
 */
@WebMvcTest(TenantSettingsController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=auth-service",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE=",
        "slack.encryption-key=c2xhY2sta2V5LTMyLWJ5dGVzLWZvci1kZXYtb25seSE="
})
@DisplayName("Tenant suspension through auth-service's filter chain")
class TenantSuspensionFilterWebTest {

    private static final String TENANT = "acme";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TenantSettingsService tenantSettingsService;
    @MockitoBean private TenantAccessService tenantAccessService;
    @MockitoBean private JwtUtils jwtUtils;
    @MockitoBean private ServiceTokenProvider serviceTokenProvider;
    @MockitoBean private ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService;

    @BeforeEach
    void settings() {
        given(tenantSettingsService.getSettings()).willReturn(TenantSettingsDto.of(TENANT, false, 2));
    }

    private static RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(UUID.randomUUID(), TENANT, "admin@acme.test", List.of("ROLE_ADMIN"), List.of()),
                null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private void access(TenantAccess access) {
        given(tenantAccessService.accessOf(TENANT)).willReturn(access);
    }

    @Test
    @DisplayName("active: reads and writes reach the controller")
    void active() throws Exception {
        access(TenantAccess.FULL);
        mockMvc.perform(get("/api/v1/tenants/settings").with(admin())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("suspended in full: even a read is 403 TENANT_SUSPENDED, the controller never runs")
    void suspended() throws Exception {
        access(TenantAccess.NONE);
        mockMvc.perform(get("/api/v1/tenants/settings").with(admin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_SUSPENDED"));
        then(tenantSettingsService).should(never()).getSettings();
    }

    @Test
    @DisplayName("read-only: a read goes on, a write is 403 TENANT_READ_ONLY")
    void readOnly() throws Exception {
        access(TenantAccess.READ_ONLY);
        mockMvc.perform(get("/api/v1/tenants/settings").with(admin())).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/tenants/settings").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mfaRequired\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_READ_ONLY"));
        then(tenantSettingsService).should(never()).updateSettings(org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("read-only: account-security writes (application.yml's list) pass the filter")
    void readOnlyAccountSecurity() throws Exception {
        access(TenantAccess.READ_ONLY);
        // Their controllers are not in this slice: past the filter, the request ends as 404, not 403.
        mockMvc.perform(patch("/api/v1/users/me/password").with(admin())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/auth/mfa/disable").with(admin())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/auth/logout").with(admin())).andExpect(status().isNotFound());
        // Shutting out a leaked key or a departed person (found in review).
        final String id = java.util.UUID.randomUUID().toString();
        mockMvc.perform(delete("/api/v1/api-keys/" + id).with(admin())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/api-keys/revoke-created-by").with(admin())).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/integrations/" + id).with(admin())).andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/users/" + id + "/status").with(admin())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/users/" + id + "/mfa-reset").with(admin())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("read-only: writes next to the allowed ones stay refused (create a key, restore a user, change roles)")
    void readOnlyNeighboursRefused() throws Exception {
        access(TenantAccess.READ_ONLY);
        final String id = java.util.UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/api-keys").with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/integrations").with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/users/" + id + "/restore").with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/users/" + id + "/roles").with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/users/" + id).with(admin())).andExpect(status().isForbidden());
        // Another method on an allowed path: each allowed write names its method.
        mockMvc.perform(put("/api/v1/api-keys/" + id).with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/integrations/" + id).with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/mfa/backup-codes").with(admin())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("another tenant's admin is unaffected")
    void otherTenant() throws Exception {
        given(tenantAccessService.accessOf(anyString())).willReturn(TenantAccess.FULL);
        mockMvc.perform(get("/api/v1/tenants/settings").with(authentication(new UsernamePasswordAuthenticationToken(
                        new UserPrincipal(UUID.randomUUID(), "globex", "a@globex.test", List.of("ROLE_ADMIN"), List.of()),
                        null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))))
                .andExpect(status().isOk());
    }
}
