package com.incidentplatform.incident.api;

import com.incidentplatform.incident.config.DevSecurityConfig;
import com.incidentplatform.incident.config.SecurityConfig;
import com.incidentplatform.incident.service.AuditQueryService;
import com.incidentplatform.incident.service.IncidentCommandService;
import com.incidentplatform.incident.service.IncidentQueryService;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.ServiceNames;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantStatusProvider;
import com.incidentplatform.shared.security.TokenPurposes;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Every route of incident-service refuses a caller that has not authenticated
 * (backlog #0-191, ADR-0048). Like auth-service's {@code ApiKeyDenyByDefaultTest},
 * it goes through the real {@link SecurityConfig} and walks every route and
 * method of {@link RequestMappingHandlerMapping}, so a route added later is
 * checked without anyone adding it here. Three callers: no {@code Authorization}
 * (401), a real purpose token addressed to this service (401 or 403), and an
 * unknown API key as {@code ApiKey ipl_…} and as {@code Bearer ipl_…} (401).
 *
 * <h2>The chain as production builds it</h2>
 * The filters are the shared auto-configuration's beans with a real
 * {@link JwtUtils} and its properties: no hand-made {@code JwtAuthFilter} and
 * no mocked {@code JwtUtils}, which the other incident tests use and which
 * would answer 401 to every token whatever the chain said. The scan starts at
 * the service's root package, so a controller in a new package is walked too.
 * {@link #aValidUserTokenGetsPastAuthentication()} shows a valid token does
 * authenticate in this context, so the 401s are the chain refusing the caller
 * (ADR-0048 rule 7, backlog #0-196).
 *
 * <h2>An unknown API key, without {@code ApiKeyAuthFilter}</h2>
 * incident's chain does not add {@code ApiKeyAuthFilter} (backlog #0-193), so
 * nothing in the chain reads the key: its 401 comes from
 * {@link UnauthorizedEntryPoint}, as for an anonymous caller. The test pins the
 * status either way; #0-193 decides which filter gives it.
 *
 * <h2>Why the exempt set is a literal and not {@code PUBLIC_PATHS}</h2>
 * Reading {@code SharedSecurityAutoConfiguration.PUBLIC_PATHS} here would
 * exempt exactly the route a bad new entry there opens. Each exemption is
 * written out with its reason; a route the walk finds open is a finding, not
 * a new entry.
 *
 * <h2>No exemption for {@code /ws/}</h2>
 * {@link SecurityConfig} permits {@code /ws/**} for the STOMP handshake, which
 * {@code WebSocketConfig} registers as a STOMP endpoint, not a
 * {@code RequestMappingHandlerMapping} route: the walk never meets it, so it
 * needs no entry. The {@code /ws/**} entry #0-191 had would have exempted any
 * REST mapping someone later puts under {@code /ws/}, which that
 * {@code permitAll} opens to anyone; without it the walk reports such a
 * mapping ({@link WsRoute} shows it). Backlog #0-196, ADR-0048 rule 3 as
 * amended.
 *
 * <h2>{@code DevSecurityConfig} is imported on purpose</h2>
 * Its {@code @Profile({"local","dev"})} is then evaluated in this context, so
 * {@link #noProfileAndNoDevChain()} shows the profile keeping its
 * {@code permitAll()} chain out, not a context that never saw the class.
 */
@WebMvcTest
@ContextConfiguration(classes = IncidentDenyByDefaultTest.TestApplication.class)
@Import({SecurityConfig.class, DevSecurityConfig.class, UnauthorizedEntryPoint.class, JwtUtils.class})
@ImportAutoConfiguration(SharedSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
        "security.cors.allowed-origins=http://localhost:4200",
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "jwt.refresh-token-ttl=P30D",
        "spring.application.name=incident-service"
})
@DisplayName("incident-service — every route refuses an unauthenticated caller")
class IncidentDenyByDefaultTest {

    /**
     * Pattern to the reason the chain lets it through on purpose. A key ending
     * in {@code /**} covers the patterns below it.
     */
    private static final Map<String, String> EXEMPT = Map.of(
            // Spring Boot's BasicErrorController: it renders the error of a
            // request the chain has already handled.
            "/error", "Spring Boot's error page, public in every chain");

    /** An obviously fake Integration key: the {@code ipl_} prefix and 32 characters. */
    private static final String UNKNOWN_KEY = "ipl_" + "0".repeat(32);

    @SpringBootApplication(scanBasePackages = {
            "com.incidentplatform.incident",
            "com.incidentplatform.shared.security",
            "com.incidentplatform.shared.exception",
            "com.incidentplatform.shared.observability"
    })
    static class TestApplication {
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private ApplicationContext context;
    @Autowired private Environment environment;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    // The controllers' collaborators: the walk never reaches them, the chain
    // refuses first; only the positive control reaches IncidentQueryService.
    @MockitoBean private IncidentQueryService incidentQueryService;
    @MockitoBean private IncidentCommandService incidentCommandService;
    @MockitoBean private AuditQueryService auditQueryService;
    @MockitoBean private ServiceTokenProvider serviceTokenProvider;
    // Replaces AuthServiceTenantStatusProvider (application.yml sets
    // auth-service.base-url); every tenant is FULL, so the positive control's
    // token is not refused for its tenant.
    @MockitoBean private TenantStatusProvider tenantStatusProvider;

    @BeforeEach
    void tenantsActive() {
        given(tenantStatusProvider.accessOf(anyString())).willReturn(TenantAccess.FULL);
    }

    @Test
    @DisplayName("no Authorization header: 401 on every route")
    void everyRouteRefusesAnAnonymousCaller() throws Exception {
        assertEveryRoute(mockMvc, handlerMapping, "anonymous", request -> request, status -> status == 401);
    }

    @Test
    @DisplayName("a real purpose token addressed to incident-service: 401 or 403 on every route")
    void everyRouteRefusesAPurposeToken() throws Exception {
        final String token = jwtUtils.generatePurposeToken(
                "some-service", TokenPurposes.API_KEY_INTROSPECTION, ServiceNames.INCIDENT_SERVICE);

        assertEveryRoute(mockMvc, handlerMapping, "purpose token",
                request -> request.header("Authorization", "Bearer " + token),
                status -> status == 401 || status == 403);
    }

    @Test
    @DisplayName("an unknown API key, as 'ApiKey ipl_…' and as 'Bearer ipl_…': 401 on every route")
    void everyRouteRefusesAnUnknownApiKey() throws Exception {
        assertEveryRoute(mockMvc, handlerMapping, "ApiKey ipl_",
                request -> request.header("Authorization", "ApiKey " + UNKNOWN_KEY), status -> status == 401);
        assertEveryRoute(mockMvc, handlerMapping, "Bearer ipl_",
                request -> request.header("Authorization", "Bearer " + UNKNOWN_KEY), status -> status == 401);
    }

    /**
     * A lower bound, not an exact count (ADR-0048): a route added later is
     * walked without anyone editing this number, and an enumeration that finds
     * nothing still fails, as does one that misses either controller. Every
     * real incident mapping declares its method, so walking every method of a
     * mapping with no method condition (backlog #0-196) leaves the count as it was.
     */
    @Test
    @DisplayName("the walk finds the known routes of both controllers, and none of the test-only routes")
    void enumerationFindsTheKnownRoutes() {
        assertThat(routes(handlerMapping)).as("routes walked")
                .hasSizeGreaterThanOrEqualTo(8)
                .contains("GET /api/v1/incidents/{id}", "GET /api/v1/incidents/{incidentId}/audit")
                .doesNotContain("GET " + OpenRouteTestController.PATH,
                        "POST " + OpenRouteTestController.ANY_METHOD_PATH,
                        "GET " + WsRouteTestController.PATH);
    }

    /**
     * ADR-0048 rule 7 (backlog #0-196): a valid user token gets past
     * authentication, so the walk's 401s come from refusing its callers, not
     * from a context in which no token validates. A RESPONDER passes the
     * route's {@code @PreAuthorize}, and the controller asks
     * {@link IncidentQueryService} with the tenant of the token: the chain
     * authenticated the caller and set its tenant.
     */
    @Test
    @DisplayName("a valid RESPONDER token is authenticated: not 401 on GET /api/v1/incidents/{id}, tenant from the token")
    void aValidUserTokenGetsPastAuthentication() throws Exception {
        final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000196");
        final String token = jwtUtils.generateToken(UUID.randomUUID(), "acme-corp", "user@acme.com",
                List.of(SecurityRoles.ROLE_RESPONDER), List.of(), List.of());

        final int status = mockMvc.perform(get("/api/v1/incidents/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();

        assertThat(status).as("status for a valid RESPONDER token").isNotEqualTo(401);
        verify(incidentQueryService).findById(id, "acme-corp");
    }

    @Test
    @DisplayName("no profile is active, so neither DevSecurityConfig nor a /dev route is in the context")
    void noProfileAndNoDevChain() {
        assertThat(environment.getActiveProfiles()).as("active profiles").isEmpty();
        assertThat(context.getBeanNamesForType(DevSecurityConfig.class)).as("DevSecurityConfig beans").isEmpty();
        assertThat(routes(handlerMapping)).as("routes walked")
                .noneMatch(route -> route.substring(route.indexOf(' ') + 1).startsWith("/dev"));
    }

    /**
     * incident's own {@link SecurityConfig} answers a browser's CORS preflight
     * without a token, and nothing else about {@code OPTIONS} is exempt
     * (backlog #0-200, ADR-0049 rule 4): its {@code CorsFilter} answers a valid
     * preflight before authentication, and any other {@code OPTIONS} is
     * authenticated like every method. It shares the enclosing context, whose
     * {@code @ImportAutoConfiguration} supplies the shared
     * {@code corsConfigurationSource}; without that source the preflight would
     * get 403, and without {@code CorsFilter} in the chain 401. Sent to
     * {@code GET /api/v1/incidents}, a route the chain authenticates.
     */
    @Nested
    @DisplayName("OPTIONS preflight")
    class CorsPreflight {

        /** The enclosing class's {@code security.cors.allowed-origins}. */
        private static final String ALLOWED_ORIGIN = "http://localhost:4200";

        private static final String ROUTE = "/api/v1/incidents";

        @Test
        @DisplayName("a preflight from the allowed origin, no token: 200 with the origin, no handler runs")
        void allowedPreflightIs200WithAllowOrigin() throws Exception {
            final MockHttpServletResponse response = mockMvc.perform(options(ROUTE)
                            .header("Origin", ALLOWED_ORIGIN)
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "Authorization"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).as("status of an allowed preflight").isEqualTo(200);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).as("Access-Control-Allow-Origin")
                    .isEqualTo(ALLOWED_ORIGIN);
            verifyNoInteractions(incidentQueryService);
        }

        @Test
        @DisplayName("a bare OPTIONS with no principal: 401 like every method")
        void bareOptionsIs401() throws Exception {
            final int status = mockMvc.perform(options(ROUTE)).andReturn().getResponse().getStatus();

            assertThat(status).as("status of a bare anonymous OPTIONS").isEqualTo(401);
        }
    }

    /**
     * The walk itself goes red on an open route: the same {@link SecurityConfig}
     * plus test-only routes and a chain that permits them, the shape of
     * {@link DevSecurityConfig}. Its own context, so the outer one never holds
     * either class ({@link #enumerationFindsTheKnownRoutes()} asserts it).
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @WebMvcTest
    @ContextConfiguration(classes = OpenRouteApplication.class)
    @Import({SecurityConfig.class, UnauthorizedEntryPoint.class, JwtUtils.class,
            OpenRouteTestController.class, OpenRouteChainTestConfig.class})
    @ImportAutoConfiguration(SharedSecurityAutoConfiguration.class)
    @TestPropertySource(properties = {
            "security.cors.allowed-origins=http://localhost:4200",
            "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
            "jwt.access-token-ttl=PT15M",
            "jwt.service-token-ttl=PT1H",
            "jwt.refresh-token-ttl=P30D",
            "spring.application.name=incident-service"
    })
    @DisplayName("with a route a chain permits")
    class OpenRoute {

        @Autowired private MockMvc openMockMvc;
        @Autowired
        @Qualifier("requestMappingHandlerMapping")
        private RequestMappingHandlerMapping openHandlerMapping;

        @MockitoBean private ServiceTokenProvider openServiceTokenProvider;
        @MockitoBean private TenantStatusProvider openTenantStatusProvider;

        @Test
        @DisplayName("the walk fails and names the open route with its status")
        void theWalkFailsAndNamesTheOpenRoute() {
            assertThatThrownBy(() -> assertEveryRoute(openMockMvc, openHandlerMapping, "anonymous",
                    request -> request, status -> status == 401))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("GET " + OpenRouteTestController.PATH + " [anonymous] -> 200");
        }

        /**
         * A mapping with no method condition is walked with every method, not
         * {@code GET} alone (ADR-0048 rule 2, backlog #0-196): its {@code POST}
         * is reported, so a chain rule keyed on a method other than
         * {@code GET} is seen too.
         */
        @Test
        @DisplayName("the walk sends every method to a mapping with no method condition")
        void theWalkSendsEveryMethodToAMethodlessMapping() {
            assertThatThrownBy(() -> assertEveryRoute(openMockMvc, openHandlerMapping, "anonymous",
                    request -> request, status -> status == 401))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("POST " + OpenRouteTestController.ANY_METHOD_PATH + " [anonymous] -> 200");
        }
    }

    /**
     * The walk reports a REST mapping under {@code /ws/} (backlog #0-196): the
     * real {@link SecurityConfig} alone, with no test chain, and a test-only
     * controller at {@code /ws/test-only}. The chain's
     * {@code permitAll("/ws/**")} for the STOMP handshake opens that route and
     * nothing else does, so the walk fails because the exempt set has no
     * {@code /ws/**} entry.
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @WebMvcTest
    @ContextConfiguration(classes = OpenRouteApplication.class)
    @Import({SecurityConfig.class, UnauthorizedEntryPoint.class, JwtUtils.class, WsRouteTestController.class})
    @ImportAutoConfiguration(SharedSecurityAutoConfiguration.class)
    @TestPropertySource(properties = {
            "security.cors.allowed-origins=http://localhost:4200",
            "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
            "jwt.access-token-ttl=PT15M",
            "jwt.service-token-ttl=PT1H",
            "jwt.refresh-token-ttl=P30D",
            "spring.application.name=incident-service"
    })
    @DisplayName("with a REST mapping under /ws/")
    class WsRoute {

        @Autowired private MockMvc wsMockMvc;
        @Autowired
        @Qualifier("requestMappingHandlerMapping")
        private RequestMappingHandlerMapping wsHandlerMapping;

        @MockitoBean private ServiceTokenProvider wsServiceTokenProvider;
        @MockitoBean private TenantStatusProvider wsTenantStatusProvider;

        @Test
        @DisplayName("the walk fails and names the REST mapping under /ws/")
        void theWalkReportsARestMappingUnderWs() {
            assertThatThrownBy(() -> assertEveryRoute(wsMockMvc, wsHandlerMapping, "anonymous",
                    request -> request, status -> status == 401))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("GET " + WsRouteTestController.PATH + " [anonymous] -> 200");
        }
    }

    /**
     * The context of {@link OpenRoute} and of {@link WsRoute}: no scan, only
     * what each of them imports.
     */
    @SpringBootConfiguration
    static class OpenRouteApplication {
    }

    /** Routes that answer 200 to whoever reaches them. */
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

    /** A REST route under {@code /ws/}, which {@link SecurityConfig} permits for the STOMP handshake. */
    @RestController
    static class WsRouteTestController {

        static final String PATH = "/ws/test-only";

        @GetMapping(PATH)
        String ws() {
            return "ws";
        }
    }

    /** A chain ahead of {@link SecurityConfig}'s that permits the test-only route. */
    @Configuration
    static class OpenRouteChainTestConfig {

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
     * Sends every walked route, with its path variables filled and a JSON body,
     * and fails naming each one whose status {@code expected} rejects.
     */
    private static void assertEveryRoute(MockMvc mockMvc, RequestMappingHandlerMapping handlerMapping,
                                         String caller, UnaryOperator<MockHttpServletRequestBuilder> auth,
                                         IntPredicate expected) throws Exception {
        final List<String> misses = new ArrayList<>();
        for (final String route : routes(handlerMapping)) {
            final int space = route.indexOf(' ');
            final String method = route.substring(0, space);
            final String pattern = route.substring(space + 1);
            final String path = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            final int status = mockMvc.perform(auth.apply(request(HttpMethod.valueOf(method), path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")))
                    .andReturn().getResponse().getStatus();
            if (!expected.test(status)) {
                misses.add(route + " [" + caller + "] -> " + status);
            }
        }
        assertThat(misses).as("routes that did not refuse the %s caller", caller).isEmpty();
    }

    /**
     * Every {@code METHOD pattern} of the handler mapping outside {@link #EXEMPT}.
     * A mapping with no method condition answers every method, so it is walked
     * with every {@link RequestMethod}: a chain rule keyed on the method (a
     * {@code permitAll} on {@code POST} of the path) is seen too (ADR-0048 rule 2,
     * backlog #0-196; #0-191 walked such a mapping with {@code GET} alone).
     */
    private static List<String> routes(RequestMappingHandlerMapping handlerMapping) {
        final List<String> routes = new ArrayList<>();
        for (final RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            final Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (final String pattern : info.getPatternValues()) {
                if (isExempt(pattern)) {
                    continue;
                }
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

    private static boolean isExempt(String pattern) {
        for (final String exempt : EXEMPT.keySet()) {
            final String base = exempt.endsWith("/**") ? exempt.substring(0, exempt.length() - 3) : exempt;
            if (pattern.equals(base) || (!base.equals(exempt) && pattern.startsWith(base + "/"))) {
                return true;
            }
        }
        return false;
    }
}
