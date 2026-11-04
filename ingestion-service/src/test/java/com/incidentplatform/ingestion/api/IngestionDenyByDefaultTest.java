package com.incidentplatform.ingestion.api;

import com.incidentplatform.ingestion.config.SecurityConfig;
import com.incidentplatform.ingestion.ratelimit.RateLimitingService;
import com.incidentplatform.ingestion.service.AlertIngestionService;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.ApiKeyAuthFilter.ApiKeyLookupResult;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.SecurityRoles;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Every route of ingestion-service refuses a caller that has not authenticated
 * (backlog #0-191, ADR-0048), in the shape of {@code IncidentDenyByDefaultTest}:
 * the real {@link SecurityConfig}, every route of
 * {@link RequestMappingHandlerMapping}, and three callers: no
 * {@code Authorization} (401), a real purpose token addressed to this service
 * (401 or 403), and an unknown API key as {@code ApiKey ipl_…} and as
 * {@code Bearer ipl_…} (401).
 *
 * <h2>The chain as production builds it</h2>
 * {@link SecurityConfig}'s own {@code JwtAuthFilter} (no service-token
 * audience, backlog #0-16) with a real {@link JwtUtils}, and the shared
 * auto-configuration's {@link ApiKeyAuthFilter}. Only the key lookup behind it
 * is mocked, in place of {@code RemoteApiKeyLookupService} (introspection into
 * auth-service), and it answers {@code Invalid} for every key: the 401 for an
 * unknown key is the real filter's. The scan starts at the service's root
 * package, so a controller in a new package is walked too.
 * {@link #aValidUserTokenGetsPastAuthentication()} shows a valid token does
 * authenticate in this context, so the 401s are the chain refusing the caller
 * (ADR-0048 rule 7, backlog #0-198).
 *
 * <h2>The unknown key is refused by {@code ApiKeyAuthFilter} in the chain</h2>
 * {@link #everyRouteRefusesAnUnknownApiKey()} also verifies that the mocked
 * lookup was asked for the key, after each of the two forms (backlog #0-198).
 * That shows the filter inside the chain, not only its servlet registration:
 * {@link ApiKeyAuthFilter} is also a plain {@code Filter} bean with no
 * {@code @Order} (backlog #0-193), which MockMvc registers after
 * {@code springSecurityFilterChain}. Without the chain's
 * {@code addFilterBefore(apiKeyAuthFilter, JwtAuthFilter.class)} the entry
 * point answers 401 before that registration runs, the lookup is never
 * called, and the verify fails.
 *
 * <h2>Why the exempt set is a literal and not {@code PUBLIC_PATHS}</h2>
 * Reading {@code SharedSecurityAutoConfiguration.PUBLIC_PATHS} here would
 * exempt exactly the route a bad new entry there opens. Each exemption is
 * written out with its reason; a route the walk finds open is a finding, not
 * a new entry.
 */
@WebMvcTest
@ContextConfiguration(classes = IngestionDenyByDefaultTest.TestApplication.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class, JwtUtils.class})
@ImportAutoConfiguration(SharedSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
        "security.cors.allowed-origins=http://localhost:4200",
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "jwt.refresh-token-ttl=P30D",
        "spring.application.name=ingestion-service"
})
@DisplayName("ingestion-service — every route refuses an unauthenticated caller")
class IngestionDenyByDefaultTest {

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

    /** ingestion-service's {@code spring.application.name}; it has no {@code ServiceNames} entry (#0-16). */
    private static final String INGESTION_SERVICE = "ingestion-service";

    @SpringBootApplication(scanBasePackages = {
            "com.incidentplatform.ingestion",
            "com.incidentplatform.shared.security",
            "com.incidentplatform.shared.exception"
    })
    static class TestApplication {
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    // The controller's collaborators: never reached, the chain refuses first
    // (and @PreAuthorize refuses the positive control's RESPONDER).
    @MockitoBean private AlertIngestionService alertIngestionService;
    @MockitoBean private RateLimitingService rateLimitingService;
    @MockitoBean private ClientIpResolver clientIpResolver;
    @MockitoBean private ServiceTokenProvider serviceTokenProvider;
    // Replaces RemoteApiKeyLookupService (tested on its own): the real
    // ApiKeyAuthFilter runs in the chain, the lookup answers Invalid.
    @MockitoBean private ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService;
    // Replaces AuthServiceTenantStatusProvider (application.yml sets
    // auth-service.base-url); every tenant is FULL, so the positive control's
    // token is not refused for its tenant.
    @MockitoBean private TenantStatusProvider tenantStatusProvider;

    @BeforeEach
    void everyKeyIsUnknownAndTenantsActive() {
        given(apiKeyLookupService.lookup(anyString(), any())).willReturn(new ApiKeyLookupResult.Invalid());
        given(tenantStatusProvider.accessOf(anyString())).willReturn(TenantAccess.FULL);
    }

    @Test
    @DisplayName("no Authorization header: 401 on every route")
    void everyRouteRefusesAnAnonymousCaller() throws Exception {
        assertEveryRoute(mockMvc, handlerMapping, "anonymous", request -> request, status -> status == 401);
    }

    @Test
    @DisplayName("a real purpose token addressed to ingestion-service: 401 or 403 on every route")
    void everyRouteRefusesAPurposeToken() throws Exception {
        final String token = jwtUtils.generatePurposeToken(
                "some-service", TokenPurposes.API_KEY_INTROSPECTION, INGESTION_SERVICE);

        assertEveryRoute(mockMvc, handlerMapping, "purpose token",
                request -> request.header("Authorization", "Bearer " + token),
                status -> status == 401 || status == 403);
    }

    @Test
    @DisplayName("an unknown API key, as 'ApiKey ipl_…' and as 'Bearer ipl_…': 401 on every route")
    void everyRouteRefusesAnUnknownApiKey() throws Exception {
        assertEveryRoute(mockMvc, handlerMapping, "ApiKey ipl_",
                request -> request.header("Authorization", "ApiKey " + UNKNOWN_KEY), status -> status == 401);
        verify(apiKeyLookupService, atLeastOnce()).lookup(eq(UNKNOWN_KEY), any());
        clearInvocations(apiKeyLookupService);

        assertEveryRoute(mockMvc, handlerMapping, "Bearer ipl_",
                request -> request.header("Authorization", "Bearer " + UNKNOWN_KEY), status -> status == 401);
        verify(apiKeyLookupService, atLeastOnce()).lookup(eq(UNKNOWN_KEY), any());
    }

    /**
     * A lower bound, not an exact count (ADR-0048): a route added later is
     * walked without anyone editing this number, and an enumeration that finds
     * nothing still fails. Every ingestion mapping declares its method (only
     * the exempt {@code /error} has none), so walking every method of a mapping
     * with no method condition (backlog #0-198) leaves the bound as it was.
     */
    @Test
    @DisplayName("the walk finds the known routes of AlertIngestionController, and none of the test-only routes")
    void enumerationFindsTheKnownRoutes() {
        assertThat(routes(handlerMapping)).as("routes walked")
                .hasSizeGreaterThanOrEqualTo(2)
                .contains("POST /api/v1/alerts/{source}", "GET /api/v1/alerts/sources")
                .doesNotContain("GET " + OpenRouteTestController.PATH,
                        "POST " + OpenRouteTestController.ANY_METHOD_PATH);
    }

    /**
     * ADR-0048 rule 7 (backlog #0-198): a valid user token gets past
     * authentication, so the walk's 401s come from refusing its callers, not
     * from a context in which no token validates. A RESPONDER is not admitted
     * by {@code AlertIngestionController.ingestAlerts}'s {@code @PreAuthorize},
     * and method security answers an authenticated principal with 403 (an
     * anonymous one gets 401 from the entry point); the controller is not
     * reached.
     */
    @Test
    @DisplayName("a valid RESPONDER token is authenticated: 403, not 401, on POST /api/v1/alerts/{source}")
    void aValidUserTokenGetsPastAuthentication() throws Exception {
        final String token = jwtUtils.generateToken(UUID.randomUUID(), "acme-corp", "user@acme.com",
                List.of(SecurityRoles.ROLE_RESPONDER), List.of(), List.of());

        final int status = mockMvc.perform(post("/api/v1/alerts/{source}", "prometheus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getStatus();

        assertThat(status).as("status for a valid RESPONDER token").isNotEqualTo(401).isEqualTo(403);
        verifyNoInteractions(alertIngestionService);
    }

    /**
     * ingestion's own {@link SecurityConfig} answers a browser's CORS preflight
     * without a token, and nothing else about {@code OPTIONS} is exempt
     * (backlog #0-200, ADR-0049 rule 4): its {@code CorsFilter} answers a valid
     * preflight before authentication, and any other {@code OPTIONS} is
     * authenticated like every method. It shares the enclosing context, whose
     * {@code @ImportAutoConfiguration} supplies the shared
     * {@code corsConfigurationSource}; without that source the preflight would
     * get 403, and without {@code CorsFilter} in the chain 401. Sent to
     * {@code POST /api/v1/alerts/{source}}, a route the chain authenticates.
     */
    @Nested
    @DisplayName("OPTIONS preflight")
    class CorsPreflight {

        /** The enclosing class's {@code security.cors.allowed-origins}. */
        private static final String ALLOWED_ORIGIN = "http://localhost:4200";

        private static final String ROUTE = "/api/v1/alerts/prometheus";

        @Test
        @DisplayName("a preflight from the allowed origin, no token: 200 with the origin, no handler runs")
        void allowedPreflightIs200WithAllowOrigin() throws Exception {
            final MockHttpServletResponse response = mockMvc.perform(options(ROUTE)
                            .header("Origin", ALLOWED_ORIGIN)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Authorization"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).as("status of an allowed preflight").isEqualTo(200);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).as("Access-Control-Allow-Origin")
                    .isEqualTo(ALLOWED_ORIGIN);
            verifyNoInteractions(alertIngestionService, rateLimitingService);
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
     * plus a test-only route and a chain that permits it. Its own context, so
     * the outer one never holds either class
     * ({@link #enumerationFindsTheKnownRoutes()} asserts it).
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
            "spring.application.name=ingestion-service"
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
         * {@code GET} alone (ADR-0048 rule 2, backlog #0-198): its {@code POST}
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

    /** The context of {@link OpenRoute}: no scan, only what that class imports. */
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
     * backlog #0-198; #0-191 walked such a mapping with {@code GET} alone).
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
