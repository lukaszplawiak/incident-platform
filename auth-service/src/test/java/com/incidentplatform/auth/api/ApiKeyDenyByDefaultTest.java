package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.domain.ApiKeyScope;
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
import com.incidentplatform.auth.service.TenantProvisioningService;
import com.incidentplatform.auth.service.TenantSettingsService;
import com.incidentplatform.auth.service.UserManagementService;
import com.incidentplatform.auth.service.UserQueryService;
import com.incidentplatform.auth.service.UserService;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.ApiKeyAuthFilter.ApiKeyLookupResult;
import com.incidentplatform.shared.security.JwtProperties;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import com.incidentplatform.shared.security.UserPrincipal;
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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An API key reaches only the routes of auth-service that SecurityConfig lists
 * for keys, and only with the scope each names (backlog #0-89). Like
 * {@link IntrospectionTokenDenyByDefaultTest}, it goes through the real filter
 * chain and enumerates every route the controllers declare, so a route added
 * later is checked without anyone adding it here.
 *
 * <p>The key is an admin's personal key carrying every scope: the strongest
 * key there is, so a route it does not reach is closed to every key.
 */
@WebMvcTest
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class, JwtUtils.class,
        ApiKeyDenyByDefaultTest.JwtPropertiesConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "jwt.refresh-token-ttl=P30D",
        "spring.application.name=auth-service",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE=",
        "slack.encryption-key=c2xhY2sta2V5LTMyLWJ5dGVzLWZvci1kZXYtb25seSE="
})
@DisplayName("API keys — denied on every auth-service route but the listed team routes")
class ApiKeyDenyByDefaultTest {

    @TestConfiguration
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesConfig {
    }

    private static final String TENANT = "acme";
    private static final String RAW_KEY = "ipl_abcdefgh12345678901234567890123456";
    private static final UUID TEAM_ID = UUID.randomUUID();

    /** The public POST routes of SecurityConfig: any caller may call them. */
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
    @MockitoBean private TenantProvisioningService tenantProvisioningService;
    @MockitoBean private com.incidentplatform.auth.ratelimit.PlatformRateLimiter platformRateLimiter;
    @MockitoBean private SlackWorkspaceService slackWorkspaceService;
    @MockitoBean private IntegrationService integrationService;

    private void keyWithScopes(List<String> roles, ApiKeyScope... scopes) {
        final UserPrincipal key = new UserPrincipal(UUID.randomUUID(), TENANT, "admin@acme.test",
                roles, List.of(), List.of(), true,
                Arrays.stream(scopes).map(ApiKeyScope::getScopeName).toList(), null);
        given(apiKeyLookupService.lookup(anyString(), any()))
                .willReturn(new ApiKeyLookupResult.Authenticated(key));
    }

    @Test
    @DisplayName("an admin's key with every scope gets 403 on every route but /api/v1/teams")
    void everyOtherRouteIsForbidden() throws Exception {
        keyWithScopes(List.of(SecurityRoles.ROLE_ADMIN), ApiKeyScope.values());
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
                                            .header("Authorization", "ApiKey " + RAW_KEY)
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

        assertThat(checked).as("routes checked").hasSizeGreaterThan(30)
                // The routes that make a key a lasting foothold (backlog #0-89).
                .contains("POST /api/v1/users", "POST /api/v1/api-keys", "POST /api/v1/integrations",
                        "POST /api/v1/auth/mfa/setup");
        assertThat(notForbidden).as("routes an API key reached").isEmpty();
    }

    /** Public routes, and the team routes this rule lets a key reach (tested below). */
    private static boolean isExempt(String pattern, RequestMethod method) {
        if (pattern.equals("/api/v1/teams") || pattern.startsWith("/api/v1/teams/")) {
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
    @DisplayName("teams:read lets a key read teams, and nothing else of them")
    void teamsReadScope() throws Exception {
        keyWithScopes(List.of(SecurityRoles.ROLE_RESPONDER), ApiKeyScope.TEAMS_READ);

        mockMvc.perform(get("/api/v1/teams").header("Authorization", "ApiKey " + RAW_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/teams/{id}/members", TEAM_ID).header("Authorization", "ApiKey " + RAW_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/teams").header("Authorization", "ApiKey " + RAW_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"SRE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("HEAD is a read: teams:read is enough (review of #0-89)")
    void headIsARead() throws Exception {
        keyWithScopes(List.of(SecurityRoles.ROLE_RESPONDER), ApiKeyScope.TEAMS_READ);

        mockMvc.perform(head("/api/v1/teams").header("Authorization", "ApiKey " + RAW_KEY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("teams:write alone does not read teams")
    void writeScopeDoesNotRead() throws Exception {
        keyWithScopes(List.of(SecurityRoles.ROLE_ADMIN), ApiKeyScope.TEAMS_WRITE);

        mockMvc.perform(get("/api/v1/teams").header("Authorization", "ApiKey " + RAW_KEY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a trailing slash or an encoded dot segment does not get a key past the rule")
    void pathVariantsStayClosed() throws Exception {
        keyWithScopes(List.of(SecurityRoles.ROLE_ADMIN), ApiKeyScope.values());

        for (final String path : List.of("/api/v1/users/", "/api/v1/api-keys/",
                "/api/v1/teams/%2e%2e/users", "/api/v1/teams/../users")) {
            final int status = mockMvc.perform(post(path).header("Authorization", "ApiKey " + RAW_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(path).isGreaterThanOrEqualTo(400);
            org.mockito.Mockito.verifyNoInteractions(userService, apiKeyService);
        }
    }

    @Test
    @DisplayName("a key without teams:read cannot read teams, whatever its owner's role")
    void noScopeNoTeams() throws Exception {
        keyWithScopes(List.of(SecurityRoles.ROLE_ADMIN), ApiKeyScope.INCIDENTS_READ);

        mockMvc.perform(get("/api/v1/teams").header("Authorization", "ApiKey " + RAW_KEY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("teams:write passes the filter chain; the method's role check still applies")
    void teamsWriteScopeKeepsRoleCheck() throws Exception {
        keyWithScopes(List.of(SecurityRoles.ROLE_ADMIN), ApiKeyScope.TEAMS_WRITE);
        mockMvc.perform(post("/api/v1/teams").header("Authorization", "ApiKey " + RAW_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"SRE\"}"))
                .andExpect(status().isCreated());

        // Same scope on a responder's key (as one created before a role change
        // would carry): the role check refuses.
        keyWithScopes(List.of(SecurityRoles.ROLE_RESPONDER), ApiKeyScope.TEAMS_WRITE);
        mockMvc.perform(post("/api/v1/teams").header("Authorization", "ApiKey " + RAW_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"SRE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a login (JWT) is not affected: an admin still reaches the routes a key cannot")
    void jwtUnaffected() throws Exception {
        final String token = jwtUtils.generateToken(UUID.randomUUID(), TENANT,
                "admin@acme.test", List.of(SecurityRoles.ROLE_ADMIN), List.of(), List.of());

        mockMvc.perform(get("/api/v1/api-keys").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/teams").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
