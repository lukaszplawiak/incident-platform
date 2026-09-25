package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.dto.ApiKeyIntrospectionResponse;
import com.incidentplatform.auth.service.ApiKeyIntrospectionService;
import com.incidentplatform.auth.service.ApiKeyService;
import com.incidentplatform.auth.service.AuthService;
import com.incidentplatform.auth.service.AuthTokenService;
import com.incidentplatform.auth.service.ForgotPasswordService;
import com.incidentplatform.auth.service.IntegrationService;
import com.incidentplatform.auth.service.InviteService;
import com.incidentplatform.auth.service.LogoutService;
import com.incidentplatform.auth.service.MfaService;
import com.incidentplatform.auth.service.PasswordService;
import com.incidentplatform.auth.service.ResendInviteService;
import com.incidentplatform.auth.service.SlackWorkspaceService;
import com.incidentplatform.auth.service.TeamService;
import com.incidentplatform.auth.service.TenantSettingsService;
import com.incidentplatform.auth.service.UserManagementService;
import com.incidentplatform.auth.service.UserQueryService;
import com.incidentplatform.auth.service.UserService;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.JwtProperties;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.ServiceNames;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import com.incidentplatform.shared.security.TokenPurposes;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An API key introspection token acts for no tenant, so it must reach exactly
 * one route of auth-service (backlog #0-16). This test proves it with a
 * <em>real</em> token through the <em>real</em> {@link SecurityConfig} and
 * {@code JwtAuthFilter}, against <em>every</em> route the controllers
 * declare: it enumerates the handler mappings instead of listing paths, so a
 * route added later is covered without anyone remembering to add it here.
 */
@WebMvcTest
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class, JwtUtils.class,
        IntrospectionTokenDenyByDefaultTest.JwtPropertiesConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "jwt.refresh-token-ttl=P30D",
        "spring.application.name=auth-service",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE=",
        "slack.encryption-key=c2xhY2sta2V5LTMyLWJ5dGVzLWZvci1kZXYtb25seSE="
})
@DisplayName("Introspection token — denied on every route but /internal/api-keys/introspect")
class IntrospectionTokenDenyByDefaultTest {

    /** Real JwtUtils needs its properties; a @WebMvcTest slice does not bind them. */
    @TestConfiguration
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesConfig {
    }

    private static final String INTROSPECT = "/api/v1/internal/api-keys/introspect";
    private static final String HASH = "a".repeat(64);

    /** The public POST routes of SecurityConfig: any principal may call them. */
    private static final Set<String> PUBLIC_POSTS = Set.of(
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/accept-invite",
            "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
            "/api/v1/auth/mfa/verify", "/api/v1/auth/mfa/verify-backup",
            "/api/v1/auth/mfa/setup-required", "/api/v1/auth/mfa/enable-required");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean private ApiKeyIntrospectionService apiKeyIntrospectionService;
    @MockitoBean private ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private AuthService authService;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private InviteService inviteService;
    @MockitoBean private LogoutService logoutService;
    @MockitoBean private ForgotPasswordService forgotPasswordService;
    @MockitoBean private PasswordService passwordService;
    @MockitoBean private MfaService mfaService;
    @MockitoBean private TeamService teamService;
    @MockitoBean private UserService userService;
    @MockitoBean private UserQueryService userQueryService;
    @MockitoBean private UserManagementService userManagementService;
    @MockitoBean private ResendInviteService resendInviteService;
    @MockitoBean private TenantSettingsService tenantSettingsService;
    @MockitoBean private SlackWorkspaceService slackWorkspaceService;
    @MockitoBean private IntegrationService integrationService;

    private String introspectionToken() {
        return jwtUtils.generatePurposeToken(
                "ingestion-service", TokenPurposes.API_KEY_INTROSPECTION, ServiceNames.AUTH_SERVICE);
    }

    @Test
    @DisplayName("every other route answers 403 to a real introspection token")
    void everyOtherRouteIsForbidden() throws Exception {
        final String token = introspectionToken();
        final List<String> checked = new ArrayList<>();
        final List<String> notForbidden = new ArrayList<>();

        for (final RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (final String pattern : info.getPatternValues()) {
                final Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                for (final RequestMethod method : methods.isEmpty()
                        ? Set.of(RequestMethod.GET) : methods) {
                    if (isExempt(pattern, method)) {
                        continue;
                    }
                    final String path = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                    final MvcResult result = mockMvc.perform(
                                    request(HttpMethod.valueOf(method.name()), path)
                                            .header("Authorization", "Bearer " + token)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{}"))
                            .andReturn();
                    checked.add(method + " " + pattern);
                    if (result.getResponse().getStatus() != 403) {
                        notForbidden.add(method + " " + pattern + " -> "
                                + result.getResponse().getStatus());
                    }
                }
            }
        }

        assertThat(checked).as("routes checked").hasSizeGreaterThan(30);
        assertThat(notForbidden).as("routes an introspection token reached").isEmpty();
    }

    private static boolean isExempt(String pattern, RequestMethod method) {
        if (pattern.equals(INTROSPECT)) {
            return true;
        }
        if (method == RequestMethod.POST && PUBLIC_POSTS.contains(pattern)) {
            return true;
        }
        for (final String publicPath : SharedSecurityAutoConfiguration.PUBLIC_PATHS) {
            final String prefix = publicPath.endsWith("/**")
                    ? publicPath.substring(0, publicPath.length() - 3) : publicPath;
            if (pattern.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("the introspection route answers it")
    void introspectionRouteAllowed() throws Exception {
        given(apiKeyIntrospectionService.introspect(anyString()))
                .willReturn(ApiKeyIntrospectionResponse.inactive());

        mockMvc.perform(post(INTROSPECT)
                        .header("Authorization", "Bearer " + introspectionToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyHash\":\"" + HASH + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    @Test
    @DisplayName("the introspection route refuses a real ADMIN user token")
    void introspectionRefusesAdmin() throws Exception {
        final String userToken = jwtUtils.generateToken(UUID.randomUUID(), "acme",
                "admin@acme.test", List.of(SecurityRoles.ROLE_ADMIN), List.of(), List.of());

        mockMvc.perform(post(INTROSPECT)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyHash\":\"" + HASH + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the introspection route refuses a real tenant-bound service token for auth-service")
    void introspectionRefusesServiceToken() throws Exception {
        final String serviceToken = jwtUtils.generateServiceToken(
                "notification-service", "acme", ServiceNames.AUTH_SERVICE);

        mockMvc.perform(post(INTROSPECT)
                        .header("Authorization", "Bearer " + serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyHash\":\"" + HASH + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the introspection route answers 401 without a token and 400 for a malformed hash")
    void unauthenticatedAndMalformed() throws Exception {
        mockMvc.perform(post(INTROSPECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyHash\":\"" + HASH + "\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(INTROSPECT)
                        .header("Authorization", "Bearer " + introspectionToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyHash\":\"ipl_rawkey_not_a_hash\"}"))
                .andExpect(status().isBadRequest());
    }
}
