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
import com.incidentplatform.auth.service.MfaRecoveryService;
import com.incidentplatform.auth.service.PasswordService;
import com.incidentplatform.auth.service.ResendInviteService;
import com.incidentplatform.auth.service.SlackWorkspaceService;
import com.incidentplatform.auth.service.TeamService;
import com.incidentplatform.auth.service.TenantProvisioningService;
import com.incidentplatform.auth.service.TenantSettingsService;
import com.incidentplatform.auth.service.UserManagementService;
import com.incidentplatform.auth.service.UserQueryService;
import com.incidentplatform.auth.service.MfaSessionStatusService;
import com.incidentplatform.auth.service.UserService;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.ApiKeyAuthFilter.ApiKeyLookupResult;
import com.incidentplatform.shared.security.JwtProperties;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantStatusProvider;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * chain and walks every route and method the controllers declare (ADR-0048),
 * so a route added later is checked without anyone adding it here.
 *
 * <p>The key is an admin's personal key carrying every scope: the strongest
 * key there is, so a route it does not reach is closed to every key.
 *
 * <h2>Why the exempt set is a literal and not {@code PUBLIC_PATHS}</h2>
 * Reading {@code SharedSecurityAutoConfiguration.PUBLIC_PATHS} here would
 * exempt exactly the route a bad new entry there opens. Each exemption in
 * {@link #EXEMPT} is written out with its reason, keyed by
 * {@code "<METHOD> <pattern>"} (or a bare pattern for every method), so
 * {@code GET} of a public {@code POST} route is still walked; a route the walk
 * finds open is a finding, not a new entry (ADR-0048 rule 3). The team routes
 * a key may reach are {@link #KEY_ROUTES}, exact patterns: a new team route is
 * walked, and fails, until it is listed with its scope.
 *
 * <h2>Fixed (backlog #0-197): what the walk used to skip</h2>
 * It exempted every route that {@code startsWith} a {@code PUBLIC_PATHS}
 * entry, every route under the {@code /api/v1/teams} prefix, and walked a
 * mapping with no method condition with {@code GET} alone. It now reads
 * {@link #EXEMPT} and {@link #KEY_ROUTES}, sends every {@link RequestMethod}
 * to such a mapping, and {@link OpenRoute} shows the walk fails on an open
 * route and names it (ADR-0048 rule 5).
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

    /**
     * {@code "<METHOD> <pattern>"}, or a bare pattern for every method, to the
     * reason SecurityConfig lets any caller through on purpose. A pattern ending
     * in {@code /**} covers its base and the patterns below it (ADR-0048 rule 3).
     */
    private static final Map<String, String> EXEMPT = Map.ofEntries(
            Map.entry("/error", "Spring Boot's error page, public in every chain"),
            Map.entry("POST /api/v1/auth/login",
                    "public POST only (SecurityConfig): the caller signs in, it holds no token yet"),
            Map.entry("POST /api/v1/auth/refresh",
                    "public POST only (SecurityConfig): the refresh token in the body is the credential"),
            Map.entry("POST /api/v1/auth/accept-invite",
                    "public POST only (SecurityConfig): the invite token in the body is the credential"),
            Map.entry("POST /api/v1/auth/forgot-password",
                    "public POST only (SecurityConfig): a user who cannot sign in asks for a reset link"),
            Map.entry("POST /api/v1/auth/reset-password",
                    "public POST only (SecurityConfig): the reset token in the body is the credential"),
            Map.entry("POST /api/v1/auth/mfa-recovery/cancel",
                    "backlog #0-90: the cancel link of an MFA recovery notice; the token names the user and tenant"),
            Map.entry("POST /api/v1/auth/mfa/verify",
                    "MFA verify: public because the client holds only mfaToken, not a JWT"),
            Map.entry("POST /api/v1/auth/mfa/verify-backup",
                    "MFA verify with a backup code: public because the client holds only mfaToken, not a JWT"),
            Map.entry("POST /api/v1/auth/mfa/setup-required",
                    "MFA required by the tenant: the client holds only mfaSetupToken, login was blocked"),
            Map.entry("POST /api/v1/auth/mfa/enable-required",
                    "MFA required by the tenant: the client holds only mfaSetupToken, login was blocked"));

    /**
     * The exact routes SecurityConfig opens to a key with a scope (backlog
     * #0-89, {@code ApiKeyAccess.scopeOrElse}), to that rule. Skipped by the
     * walk for every method; {@link #teamsReadScope()}, {@link #headIsARead()},
     * {@link #writeScopeDoesNotRead()} and {@link #teamsWriteScopeKeepsRoleCheck()}
     * check them. Exact patterns, not the {@code /api/v1/teams} prefix: a new
     * team route is walked until it is listed here.
     */
    private static final Map<String, String> KEY_ROUTES = Map.of(
            "/api/v1/teams", "GET/HEAD need teams:read, any other method teams:write (SecurityConfig, #0-89)",
            "/api/v1/teams/{teamId}", "GET/HEAD need teams:read (/api/v1/teams/*), DELETE teams:write (#0-89)",
            "/api/v1/teams/{teamId}/restore", "POST needs teams:write (/api/v1/teams/**, #0-89)",
            "/api/v1/teams/{teamId}/members",
            "GET/HEAD need teams:read (/api/v1/teams/*/members), POST teams:write (#0-89)",
            "/api/v1/teams/{teamId}/members/{userId}/role", "PATCH needs teams:write (/api/v1/teams/**, #0-89)",
            "/api/v1/teams/{teamId}/members/{userId}", "DELETE needs teams:write (/api/v1/teams/**, #0-89)");

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
    @MockitoBean private com.incidentplatform.auth.service.TenantLifecycleService tenantLifecycleService;
    @MockitoBean private MfaRecoveryService mfaRecoveryService;
    @MockitoBean private com.incidentplatform.auth.ratelimit.PlatformRateLimiter platformRateLimiter;
    @MockitoBean private SlackWorkspaceService slackWorkspaceService;
    @MockitoBean private IntegrationService integrationService;
    // Backlog #0-82 step 2: InternalTenantStatusController needs it. It is also the
    // chain's TenantStatusProvider here, so it answers FULL: these tests are about
    // which principal reaches a route, not about suspension.
    @MockitoBean private com.incidentplatform.auth.service.TenantAccessService tenantAccessService;

    @org.junit.jupiter.api.BeforeEach
    void tenantsActive() {
        org.mockito.BDDMockito.given(tenantAccessService.accessOf(org.mockito.ArgumentMatchers.anyString()))
                .willReturn(com.incidentplatform.shared.security.TenantAccess.FULL);
    }

    private void keyWithScopes(List<String> roles, ApiKeyScope... scopes) {
        final UserPrincipal key = new UserPrincipal(UUID.randomUUID(), TENANT, "admin@acme.test",
                roles, List.of(), List.of(), true,
                Arrays.stream(scopes).map(ApiKeyScope::getScopeName).toList(), null);
        given(apiKeyLookupService.lookup(anyString(), any()))
                .willReturn(new ApiKeyLookupResult.Authenticated(key));
    }

    @Test
    @DisplayName("an admin's key with every scope gets 403 on every route and method but the listed ones")
    void everyOtherRouteIsForbidden() throws Exception {
        keyWithScopes(List.of(SecurityRoles.ROLE_ADMIN), ApiKeyScope.values());

        assertThat(routes(handlerMapping)).as("routes checked").hasSizeGreaterThan(30)
                // The routes that make a key a lasting foothold (backlog #0-89).
                .contains("POST /api/v1/users", "POST /api/v1/api-keys", "POST /api/v1/integrations",
                        "POST /api/v1/auth/mfa/setup")
                // The test-only routes live in OpenRoute's context alone.
                .doesNotContain("GET " + OpenRouteTestController.PATH,
                        "POST " + OpenRouteTestController.ANY_METHOD_PATH);
        assertEveryRoute(mockMvc, handlerMapping, "ApiKey " + RAW_KEY);
    }

    @Test
    @DisplayName("every exempt entry and every key route has a reason, and every key has a valid shape")
    void everyExemptEntryHasAReason() {
        assertThat(EXEMPT.values()).as("reasons of EXEMPT").allSatisfy(reason -> assertThat(reason).isNotBlank());
        assertThat(KEY_ROUTES.values()).as("reasons of KEY_ROUTES")
                .allSatisfy(reason -> assertThat(reason).isNotBlank());
        assertThat(EXEMPT.keySet()).as("keys of EXEMPT")
                .allSatisfy(key -> assertThat(isValidKey(key)).as(key).isTrue());
        assertThat(KEY_ROUTES.keySet()).as("keys of KEY_ROUTES: bare patterns")
                .allSatisfy(key -> assertThat(isValidKey(key) && !key.contains(" ")).as(key).isTrue());
    }

    @Test
    @DisplayName("every exempt entry and every key route names a route the controllers declare")
    void exemptEntriesNameRealRoutes() {
        assertThat(EXEMPT.keySet()).as("exempt keys").containsExactlyInAnyOrder(
                "/error",
                "POST /api/v1/auth/login", "POST /api/v1/auth/refresh", "POST /api/v1/auth/accept-invite",
                "POST /api/v1/auth/forgot-password", "POST /api/v1/auth/reset-password",
                "POST /api/v1/auth/mfa-recovery/cancel",
                "POST /api/v1/auth/mfa/verify", "POST /api/v1/auth/mfa/verify-backup",
                "POST /api/v1/auth/mfa/setup-required", "POST /api/v1/auth/mfa/enable-required");
        assertThat(KEY_ROUTES.keySet()).as("key routes").containsExactlyInAnyOrder(
                "/api/v1/teams", "/api/v1/teams/{teamId}", "/api/v1/teams/{teamId}/restore",
                "/api/v1/teams/{teamId}/members", "/api/v1/teams/{teamId}/members/{userId}/role",
                "/api/v1/teams/{teamId}/members/{userId}");

        final List<String> allRoutes = allRoutes(handlerMapping);
        final Set<String> patterns = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .collect(Collectors.toSet());
        for (final String key : EXEMPT.keySet()) {
            if (key.endsWith("/**")) {
                continue;
            }
            if (key.contains(" ")) {
                assertThat(allRoutes).as("route of exempt key " + key).contains(key);
            } else {
                assertThat(patterns).as("pattern of exempt key " + key).contains(key);
            }
        }
        assertThat(patterns).as("patterns of KEY_ROUTES").containsAll(KEY_ROUTES.keySet());
    }

    @Test
    @DisplayName("a /** key covers its base and below, not a sibling; a METHOD key covers that method only")
    void exemptMatchingFollowsRule3() {
        final Map<String, String> exempt = Map.of(
                "/api/v1/x/**", "synthetic", "POST /a", "synthetic", "/b", "synthetic");

        assertThat(isExempt(exempt, RequestMethod.GET, "/api/v1/x")).as("GET /api/v1/x").isTrue();
        assertThat(isExempt(exempt, RequestMethod.DELETE, "/api/v1/x/{id}")).as("DELETE /api/v1/x/{id}").isTrue();
        assertThat(isExempt(exempt, RequestMethod.GET, "/api/v1/x/{id}/y")).as("GET /api/v1/x/{id}/y").isTrue();
        assertThat(isExempt(exempt, RequestMethod.GET, "/api/v1/xy")).as("GET /api/v1/xy").isFalse();
        assertThat(isExempt(exempt, RequestMethod.GET, "/api/v1")).as("GET /api/v1").isFalse();
        assertThat(isExempt(exempt, RequestMethod.POST, "/a")).as("POST /a").isTrue();
        assertThat(isExempt(exempt, RequestMethod.GET, "/a")).as("GET /a").isFalse();
        assertThat(isExempt(exempt, RequestMethod.POST, "/a/c")).as("POST /a/c").isFalse();
        assertThat(isExempt(exempt, RequestMethod.PUT, "/b")).as("PUT /b").isTrue();
        assertThat(isExempt(exempt, RequestMethod.GET, "/b")).as("GET /b").isTrue();
        assertThat(isExempt(exempt, RequestMethod.GET, "/bc")).as("GET /bc").isFalse();
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

    /**
     * The walk itself goes red on an open route (ADR-0048 rule 5): the same
     * {@link SecurityConfig} plus a test-only route and a chain that permits
     * it. Its own context ({@code OVERRIDE}), so the outer one never holds
     * either class ({@link #everyOtherRouteIsForbidden()} asserts it).
     *
     * <p>The beans are listed in {@code @ContextConfiguration} and no
     * {@code @SpringBootConfiguration} is declared: a second one in
     * {@code auth.api} would make every {@code @WebMvcTest} of the package fail
     * with "Found multiple @SpringBootConfiguration" ({@link AuthApiTestApplication}).
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @WebMvcTest
    @ContextConfiguration(classes = {SecurityConfig.class, UnauthorizedEntryPoint.class, JwtUtils.class,
            JwtPropertiesConfig.class, OpenRouteTestController.class, OpenRouteChain.class})
    @ImportAutoConfiguration(SharedSecurityAutoConfiguration.class)
    @TestPropertySource(properties = {
            "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
            "jwt.access-token-ttl=PT15M",
            "jwt.service-token-ttl=PT1H",
            "jwt.refresh-token-ttl=P30D",
            "spring.application.name=auth-service",
            // The outer class's two test keys again (OVERRIDE inherits none of its properties): base64 of
            // "test-key-32-bytes-for-dev-only!!" and "slack-key-32-bytes-for-dev-only!", the values of every
            // auth-service security test, no secret.
            "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE=", // gitleaks:allow: dev-only test key
            "slack.encryption-key=c2xhY2sta2V5LTMyLWJ5dGVzLWZvci1kZXYtb25seSE=" // gitleaks:allow: dev-only test key
    })
    @DisplayName("with a route a chain permits")
    class OpenRoute {

        @Autowired private MockMvc openMockMvc;
        @Autowired
        @Qualifier("requestMappingHandlerMapping")
        private RequestMappingHandlerMapping openHandlerMapping;

        @MockitoBean private ApiKeyAuthFilter.ApiKeyLookupService openApiKeyLookupService;
        // PlatformAccess (SecurityConfig) needs it; never asked, no route is a platform route.
        @MockitoBean private MfaSessionStatusService openMfaSessionStatusService;
        @MockitoBean private TenantStatusProvider openTenantStatusProvider;

        /** The outer walk's caller: an admin's key with every scope, its tenant FULL. */
        @BeforeEach
        void adminKeyWithEveryScope() {
            final UserPrincipal key = new UserPrincipal(UUID.randomUUID(), TENANT, "admin@acme.test",
                    List.of(SecurityRoles.ROLE_ADMIN), List.of(), List.of(), true,
                    Arrays.stream(ApiKeyScope.values()).map(ApiKeyScope::getScopeName).toList(), null);
            given(openApiKeyLookupService.lookup(anyString(), any()))
                    .willReturn(new ApiKeyLookupResult.Authenticated(key));
            given(openTenantStatusProvider.accessOf(anyString())).willReturn(TenantAccess.FULL);
        }

        @Test
        @DisplayName("the walk fails and names the open route with its status")
        void theWalkFailsAndNamesTheOpenRoute() {
            assertThatThrownBy(() -> assertEveryRoute(openMockMvc, openHandlerMapping, "ApiKey " + RAW_KEY))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("GET " + OpenRouteTestController.PATH + " -> 200");
        }

        /**
         * A mapping with no method condition is walked with every method, not
         * {@code GET} alone (ADR-0048 rule 2, backlog #0-197): its {@code POST}
         * is reported, so a chain rule keyed on a method other than
         * {@code GET} is seen too.
         */
        @Test
        @DisplayName("the walk sends every method to a mapping with no method condition")
        void theWalkSendsEveryMethodToAMethodlessMapping() {
            assertThatThrownBy(() -> assertEveryRoute(openMockMvc, openHandlerMapping, "ApiKey " + RAW_KEY))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("POST " + OpenRouteTestController.ANY_METHOD_PATH + " -> 200");
        }
    }

    /**
     * Routes that answer 200 to whoever reaches them. A class nested in a test
     * is left out of the {@code @WebMvcTest} scan of {@link AuthApiTestApplication}
     * (Spring Boot's {@code TestTypeExcludeFilter}); {@code AuthServiceApplication}'s
     * plain {@code @ComponentScan} does pick it up in a {@code @SpringBootTest},
     * as it does {@code PlatformTenantControllerSecurityTest.UnannotatedPlatformController},
     * where SecurityConfig's {@code anyRequest()} rule covers it. A controller
     * needs the stereotype to be a handler at all.
     */
    @RestController
    static class OpenRouteTestController {

        static final String PATH = "/api/v1/test-only/open";

        /** No method condition: the walk sends it every {@link RequestMethod}. */
        static final String ANY_METHOD_PATH = "/api/v1/test-only/any-method";

        @GetMapping(PATH)
        String open() {
            return "open";
        }

        @RequestMapping(ANY_METHOD_PATH)
        String anyMethod() {
            return "any method";
        }
    }

    /**
     * A chain ahead of {@link SecurityConfig}'s that permits the test-only
     * routes, in the shape of incident-service's {@code DevSecurityConfig}.
     * Given to {@link OpenRoute}'s context by {@code classes} only, and
     * deliberately not a {@code @Configuration}: {@code AuthServiceApplication}'s
     * plain {@code @ComponentScan} has no {@code TypeExcludeFilter}, so it would
     * put this chain into every {@code @SpringBootTest} context
     * ({@code AuthRepositoryIntegrationTest}), as {@code PlatformRateLimiterTest}
     * explains for its {@code FailClosed.Config}.
     */
    static class OpenRouteChain {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        SecurityFilterChain openRouteChain(HttpSecurity http) throws Exception {
            return http
                    .securityMatcher("/api/v1/test-only/**")
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }
    }

    /**
     * The status every walked route must answer the key: 403, but for
     * {@code TRACE}, which Spring Security's {@code StrictHttpFirewall} rejects
     * with 400 before any chain runs (ADR-0049 rule 4, as backlog #0-192 pinned
     * it in {@code NotificationDenyByDefaultTest}). Only a mapping with no
     * method condition is sent {@code TRACE}.
     */
    private static int expectedStatus(RequestMethod method) {
        return method == RequestMethod.TRACE ? 400 : 403;
    }

    /**
     * Sends every walked route, with its path variables filled and a JSON body,
     * and fails naming each {@code METHOD pattern -> status} that did not
     * answer {@link #expectedStatus(RequestMethod)}.
     */
    private static void assertEveryRoute(MockMvc mockMvc, RequestMappingHandlerMapping handlerMapping,
                                         String authorization) throws Exception {
        final List<String> misses = new ArrayList<>();
        for (final String route : routes(handlerMapping)) {
            final int space = route.indexOf(' ');
            final RequestMethod method = RequestMethod.valueOf(route.substring(0, space));
            final String pattern = route.substring(space + 1);
            final String path = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            final int status = mockMvc.perform(request(HttpMethod.valueOf(method.name()), path)
                            .header("Authorization", authorization)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn().getResponse().getStatus();
            if (status != expectedStatus(method)) {
                misses.add(route + " -> " + status);
            }
        }
        assertThat(misses).as("routes an API key reached").isEmpty();
    }

    /** Every {@code METHOD pattern} of the handler mapping outside {@link #EXEMPT} and {@link #KEY_ROUTES}. */
    private static List<String> routes(RequestMappingHandlerMapping handlerMapping) {
        final List<String> routes = new ArrayList<>();
        for (final String route : allRoutes(handlerMapping)) {
            final int space = route.indexOf(' ');
            final RequestMethod method = RequestMethod.valueOf(route.substring(0, space));
            final String pattern = route.substring(space + 1);
            if (!KEY_ROUTES.containsKey(pattern) && !isExempt(EXEMPT, method, pattern)) {
                routes.add(route);
            }
        }
        return routes;
    }

    /**
     * Every {@code METHOD pattern} of the handler mapping. A mapping with no
     * method condition answers every method, so it is listed with every
     * {@link RequestMethod}: a chain rule keyed on the method (a
     * {@code permitAll} on {@code POST} of the path) is seen too (ADR-0048
     * rule 2; before backlog #0-197 such a mapping was walked with {@code GET}
     * alone).
     */
    private static List<String> allRoutes(RequestMappingHandlerMapping handlerMapping) {
        final List<String> routes = new ArrayList<>();
        for (final RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            final Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (final String pattern : info.getPatternValues()) {
                for (final RequestMethod method : methods.isEmpty() ? EnumSet.allOf(RequestMethod.class) : methods) {
                    final String route = method + " " + pattern;
                    if (!routes.contains(route)) {
                        routes.add(route);
                    }
                }
            }
        }
        return routes;
    }

    /**
     * Whether {@code exempt} covers {@code method} on {@code pattern}. A key
     * {@code "<METHOD> <pattern>"} covers that method only, a bare pattern
     * every method. The pattern part is compared exactly, except one ending in
     * {@code /**}: it is matched by Spring's {@code PathPattern}, which covers
     * its base and the patterns below it but not a sibling ({@code /a/bc} for
     * {@code /a/b/**}), ADR-0048 rule 3.
     */
    private static boolean isExempt(Map<String, String> exempt, RequestMethod method, String pattern) {
        for (final String key : exempt.keySet()) {
            final int space = key.indexOf(' ');
            if (space >= 0 && !key.substring(0, space).equals(method.name())) {
                continue;
            }
            final String exemptPattern = key.substring(space + 1);
            final boolean matches = exemptPattern.endsWith("/**")
                    ? PathPatternParser.defaultInstance.parse(exemptPattern).matches(PathContainer.parsePath(pattern))
                    : exemptPattern.equals(pattern);
            if (matches) {
                return true;
            }
        }
        return false;
    }

    /** A bare pattern starting with {@code /}, or a {@link RequestMethod} name, one space and such a pattern. */
    private static boolean isValidKey(String key) {
        final int space = key.indexOf(' ');
        if (space >= 0 && Arrays.stream(RequestMethod.values())
                .noneMatch(method -> method.name().equals(key.substring(0, space)))) {
            return false;
        }
        final String pattern = key.substring(space + 1);
        return pattern.startsWith("/") && !pattern.contains(" ");
    }
}
