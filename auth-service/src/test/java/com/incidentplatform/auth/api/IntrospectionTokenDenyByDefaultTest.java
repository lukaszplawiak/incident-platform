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
import com.incidentplatform.auth.service.MfaRecoveryService;
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
import com.incidentplatform.shared.security.JwtProperties;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.ServiceNames;
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
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
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
 * {@code JwtAuthFilter}, against <em>every</em> route and method the
 * controllers declare: it enumerates the handler mappings instead of listing
 * paths, so a route added later is covered without anyone remembering to add
 * it here (ADR-0048).
 *
 * <h2>Why the exempt set is a literal and not {@code PUBLIC_PATHS}</h2>
 * Reading {@code SharedSecurityAutoConfiguration.PUBLIC_PATHS} here would
 * exempt exactly the route a bad new entry there opens. Each exemption in
 * {@link #EXEMPT} is written out with its reason, keyed by
 * {@code "<METHOD> <pattern>"} (or a bare pattern for every method), so
 * {@code GET} of a public {@code POST} route is still walked; a route the walk
 * finds open is a finding, not a new entry (ADR-0048 rule 3).
 *
 * <h2>Fixed (backlog #0-197): what the walk used to skip</h2>
 * It exempted every route that {@code startsWith} a {@code PUBLIC_PATHS}
 * entry, and walked a mapping with no method condition with {@code GET}
 * alone. It now reads {@link #EXEMPT} and sends every {@link RequestMethod}
 * to such a mapping ({@link #theWalkSendsEveryMethodToAMethodlessMapping()}).
 * The negative case of ADR-0048 rule 5 is {@code ApiKeyDenyByDefaultTest.OpenRoute}.
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

    /**
     * {@code "<METHOD> <pattern>"}, or a bare pattern for every method, to the
     * reason SecurityConfig lets the token through on purpose. A pattern ending
     * in {@code /**} covers its base and the patterns below it (ADR-0048 rule 3).
     */
    private static final Map<String, String> EXEMPT = Map.ofEntries(
            Map.entry("/error", "Spring Boot's error page, public in every chain"),
            Map.entry("POST " + INTROSPECT,
                    "backlog #0-16: the one route an introspection token may reach (introspectionRouteAllowed)"),
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

    private String introspectionToken() {
        return jwtUtils.generatePurposeToken(
                "ingestion-service", TokenPurposes.API_KEY_INTROSPECTION, ServiceNames.AUTH_SERVICE);
    }

    @Test
    @DisplayName("every other route answers 403 to a real introspection token")
    void everyOtherRouteIsForbidden() throws Exception {
        assertThat(routes(handlerMapping)).as("routes checked").hasSizeGreaterThan(30)
                .doesNotContain("POST " + INTROSPECT);
        assertEveryRoute(mockMvc, handlerMapping, "Bearer " + introspectionToken());
    }

    @Test
    @DisplayName("every exempt entry has a reason and a valid key")
    void everyExemptEntryHasAReason() {
        assertThat(EXEMPT.values()).as("reasons of EXEMPT").allSatisfy(reason -> assertThat(reason).isNotBlank());
        assertThat(EXEMPT.keySet()).as("keys of EXEMPT")
                .allSatisfy(key -> assertThat(isValidKey(key)).as(key).isTrue());
    }

    @Test
    @DisplayName("every exempt entry names a route the controllers declare")
    void exemptEntriesNameRealRoutes() {
        assertThat(EXEMPT.keySet()).as("exempt keys").containsExactlyInAnyOrder(
                "/error", "POST " + INTROSPECT,
                "POST /api/v1/auth/login", "POST /api/v1/auth/refresh", "POST /api/v1/auth/accept-invite",
                "POST /api/v1/auth/forgot-password", "POST /api/v1/auth/reset-password",
                "POST /api/v1/auth/mfa-recovery/cancel",
                "POST /api/v1/auth/mfa/verify", "POST /api/v1/auth/mfa/verify-backup",
                "POST /api/v1/auth/mfa/setup-required", "POST /api/v1/auth/mfa/enable-required");

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

    /**
     * A mapping with no method condition is walked with every
     * {@link RequestMethod}, not {@code GET} alone (ADR-0048 rule 2, backlog
     * #0-197). A handler mapping of its own, with one such mapping registered:
     * this context has no test-only route, and the walk over it is
     * {@code ApiKeyDenyByDefaultTest.OpenRoute}'s.
     */
    @Test
    @DisplayName("the walk sends every method to a mapping with no method condition")
    void theWalkSendsEveryMethodToAMethodlessMapping() throws Exception {
        final String pattern = "/api/v1/test-only/any-method";
        final RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(new StaticApplicationContext());
        mapping.registerMapping(RequestMappingInfo.paths(pattern).build(),
                new MethodlessHandler(), MethodlessHandler.class.getDeclaredMethod("handle"));

        assertThat(routes(mapping)).as("routes walked for a mapping with no method condition")
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(RequestMethod.values())
                        .map(method -> method + " " + pattern).toList())
                .hasSize(8);
    }

    /** The handler of {@link #theWalkSendsEveryMethodToAMethodlessMapping()}; never called. */
    static class MethodlessHandler {
        String handle() {
            return "any method";
        }
    }

    /**
     * The status every walked route must answer the token: 403, but for
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
        assertThat(misses).as("routes an introspection token reached").isEmpty();
    }

    /** Every {@code METHOD pattern} of the handler mapping outside {@link #EXEMPT}. */
    private static List<String> routes(RequestMappingHandlerMapping handlerMapping) {
        final List<String> routes = new ArrayList<>();
        for (final String route : allRoutes(handlerMapping)) {
            final int space = route.indexOf(' ');
            final RequestMethod method = RequestMethod.valueOf(route.substring(0, space));
            if (!isExempt(EXEMPT, method, route.substring(space + 1))) {
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
