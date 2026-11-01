package com.incidentplatform.postmortem.api;

import com.incidentplatform.postmortem.config.SecurityConfig;
import com.incidentplatform.postmortem.service.PostmortemService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Every route of postmortem-service refuses a caller that has not authenticated
 * (backlog #0-192, ADR-0048), in the shape of incident-service's
 * {@code IncidentDenyByDefaultTest}: the real {@link SecurityConfig}, every
 * route and method of {@link RequestMappingHandlerMapping}, and three callers:
 * no {@code Authorization} (401), a real purpose token addressed to this
 * service (401 or 403), and an unknown API key as {@code ApiKey ipl_…} and as
 * {@code Bearer ipl_…} (401).
 *
 * <h2>The chain as production builds it</h2>
 * The filters are the shared auto-configuration's beans with a real
 * {@link JwtUtils} and its properties: no hand-made {@code JwtAuthFilter} and
 * no mocked {@code JwtUtils}, which {@link PostmortemApiTestApplication} and the
 * other tests of this package use and which would answer 401 to every token
 * whatever the chain said. Its own {@link TestApplication}, not
 * {@link PostmortemApiTestApplication}: that one scans {@code postmortem.api} and
 * {@code postmortem.config} only, and this one starts at the service's root
 * package, so a controller in a new package is walked too.
 * {@link #aValidUserTokenGetsPastAuthentication()} shows a valid token does
 * authenticate in this context, so the 401s are the chain refusing the caller.
 *
 * <h2>An unknown API key, without {@code ApiKeyAuthFilter}</h2>
 * postmortem's chain does not add {@code ApiKeyAuthFilter} (backlog #0-193), so
 * nothing in the chain reads the key: its 401 comes from
 * {@link UnauthorizedEntryPoint}, as for an anonymous caller. The test pins the
 * status either way; #0-193 decides which filter gives it.
 *
 * <h2>Why the exempt set is a literal and not {@code PUBLIC_PATHS}</h2>
 * Reading {@code SharedSecurityAutoConfiguration.PUBLIC_PATHS} here would
 * exempt exactly the route a bad new entry there opens. Each exemption is
 * written out with its reason; a route the walk finds open is a finding, not
 * a new entry. springdoc's {@code /v3/api-docs/**} and {@code /swagger-ui/**}
 * are not in this slice ({@code @WebMvcTest} does not load springdoc's
 * auto-configuration, {@link #enumerationFindsTheKnownRoutes()} asserts it),
 * and actuator is served on the management port, so neither needs an entry.
 *
 * <h2>The nested test applications pin the other tests of this package</h2>
 * {@link TestApplication} and {@link OpenRouteApplication} are
 * {@code @SpringBootConfiguration}s in {@code postmortem.api}, so
 * {@code PostmortemControllerSecurityTest} and {@code OpenApiDocumentTest},
 * which used to find {@link PostmortemApiTestApplication} by searching the
 * package, name it with {@code @ContextConfiguration}.
 */
@WebMvcTest
@ContextConfiguration(classes = PostmortemDenyByDefaultTest.TestApplication.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class, JwtUtils.class})
@ImportAutoConfiguration(SharedSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
        "security.cors.allowed-origins=http://localhost:4200",
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "jwt.refresh-token-ttl=P30D",
        "spring.application.name=postmortem-service"
})
@DisplayName("postmortem-service — every route refuses an unauthenticated caller")
class PostmortemDenyByDefaultTest {

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

    /**
     * postmortem-service's {@code spring.application.name}: it has no
     * {@code ServiceNames} entry, and this item does not change {@code shared}.
     */
    private static final String POSTMORTEM_SERVICE = "postmortem-service";

    @SpringBootApplication(scanBasePackages = {
            "com.incidentplatform.postmortem",
            "com.incidentplatform.shared.security",
            "com.incidentplatform.shared.exception",
            "com.incidentplatform.shared.observability"
    })
    static class TestApplication {
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    // The controller's collaborator: never reached by the walk, the chain
    // refuses first.
    @MockitoBean private PostmortemService postmortemService;
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
    @DisplayName("a real purpose token addressed to postmortem-service: 401 or 403 on every route")
    void everyRouteRefusesAPurposeToken() throws Exception {
        final String token = jwtUtils.generatePurposeToken(
                "some-service", TokenPurposes.API_KEY_INTROSPECTION, POSTMORTEM_SERVICE);

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
     * A lower bound, not an exact count (ADR-0048): the 4 routes of
     * {@link PostmortemController} on the branch that added the test. A route
     * added later is walked without anyone editing this number, and an
     * enumeration that finds nothing still fails. No springdoc route is in the
     * slice, which is why the exempt set needs no entry for one.
     */
    @Test
    @DisplayName("the walk finds the known routes, no springdoc route, and not the test-only route")
    void enumerationFindsTheKnownRoutes() {
        assertThat(routes(handlerMapping)).as("routes walked")
                .hasSizeGreaterThanOrEqualTo(4)
                .contains("GET /api/v1/postmortems", "GET /api/v1/postmortems/incident/{incidentId}")
                .doesNotContain("GET " + OpenRouteTestController.PATH)
                .noneMatch(route -> {
                    final String pattern = route.substring(route.indexOf(' ') + 1);
                    return pattern.startsWith("/v3/api-docs") || pattern.startsWith("/swagger-ui");
                });
    }

    /**
     * ADR-0048 rule 7: a valid user token gets past authentication, so the
     * walk's 401s come from refusing its callers, not from a context in which
     * no token validates. The route admits RESPONDER; whatever the mocked
     * service then answers, it is not 401.
     */
    @Test
    @DisplayName("a valid RESPONDER token is authenticated: not 401 on /postmortems/incident/{incidentId}")
    void aValidUserTokenGetsPastAuthentication() throws Exception {
        final String token = jwtUtils.generateToken(UUID.randomUUID(), "acme-corp", "user@acme.com",
                List.of(SecurityRoles.ROLE_RESPONDER), List.of(), List.of());

        final int status = mockMvc.perform(get("/api/v1/postmortems/incident/{incidentId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();

        assertThat(status).as("status for a valid RESPONDER token").isNotEqualTo(401);
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
            "spring.application.name=postmortem-service"
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
    }

    /** The context of {@link OpenRoute}: no scan, only what that class imports. */
    @SpringBootConfiguration
    static class OpenRouteApplication {
    }

    /** A route that answers 200 to whoever reaches it. */
    @RestController
    static class OpenRouteTestController {

        static final String PATH = "/api/v1/test-only/open";

        @GetMapping(PATH)
        String open() {
            return "open";
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
     * {@code permitAll} on {@code POST} of the path) is seen too (ADR-0048 rule 2).
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
