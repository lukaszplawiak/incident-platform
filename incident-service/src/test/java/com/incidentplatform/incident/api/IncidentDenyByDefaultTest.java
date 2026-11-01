package com.incidentplatform.incident.api;

import com.incidentplatform.incident.config.DevSecurityConfig;
import com.incidentplatform.incident.config.SecurityConfig;
import com.incidentplatform.incident.service.AuditQueryService;
import com.incidentplatform.incident.service.IncidentCommandService;
import com.incidentplatform.incident.service.IncidentQueryService;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ServiceNames;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import com.incidentplatform.shared.security.TenantStatusProvider;
import com.incidentplatform.shared.security.TokenPurposes;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Every route of incident-service refuses a caller that has not authenticated
 * (backlog #0-191, ADR-0048). Like auth-service's {@code ApiKeyDenyByDefaultTest},
 * it goes through the real {@link SecurityConfig} and walks every route of
 * {@link RequestMappingHandlerMapping}, so a route added later is checked
 * without anyone adding it here. Three callers: no {@code Authorization}
 * (401), a real purpose token addressed to this service (401 or 403), and an
 * unknown API key as {@code ApiKey ipl_…} and as {@code Bearer ipl_…} (401).
 *
 * <h2>The chain as production builds it</h2>
 * The filters are the shared auto-configuration's beans with a real
 * {@link JwtUtils} and its properties: no hand-made {@code JwtAuthFilter} and
 * no mocked {@code JwtUtils}, which the other incident tests use and which
 * would answer 401 to every token whatever the chain said. The scan starts at
 * the service's root package, so a controller in a new package is walked too.
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
            "/error", "Spring Boot's error page, public in every chain",
            // The WebSocket handshake: a browser cannot send an Authorization
            // header on it, and StompAuthChannelInterceptor authenticates the
            // STOMP CONNECT frame. It is not a RequestMappingHandlerMapping route
            // today; the entry keeps a future HTTP mapping under it from being
            // reported as the hole it is not.
            "/ws/**", "WebSocket handshake; STOMP CONNECT is authenticated by StompAuthChannelInterceptor");

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

    // The controllers' collaborators: never reached, the chain refuses first.
    @MockitoBean private IncidentQueryService incidentQueryService;
    @MockitoBean private IncidentCommandService incidentCommandService;
    @MockitoBean private AuditQueryService auditQueryService;
    @MockitoBean private ServiceTokenProvider serviceTokenProvider;
    // Replaces AuthServiceTenantStatusProvider (application.yml sets
    // auth-service.base-url): no request here authenticates, so it is never asked.
    @MockitoBean private TenantStatusProvider tenantStatusProvider;

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
     * nothing still fails, as does one that misses either controller.
     */
    @Test
    @DisplayName("the walk finds the known routes of both controllers, and not the test-only route")
    void enumerationFindsTheKnownRoutes() {
        assertThat(routes(handlerMapping)).as("routes walked")
                .hasSizeGreaterThanOrEqualTo(8)
                .contains("GET /api/v1/incidents/{id}", "GET /api/v1/incidents/{incidentId}/audit")
                .doesNotContain("GET " + OpenRouteTestController.PATH);
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
     * The walk itself goes red on an open route: the same {@link SecurityConfig}
     * plus a test-only route and a chain that permits it, the shape of
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

    /** Every {@code METHOD pattern} of the handler mapping (no method: GET) outside {@link #EXEMPT}. */
    private static List<String> routes(RequestMappingHandlerMapping handlerMapping) {
        final List<String> routes = new ArrayList<>();
        for (final RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            final Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (final String pattern : info.getPatternValues()) {
                if (isExempt(pattern)) {
                    continue;
                }
                for (final RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
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
