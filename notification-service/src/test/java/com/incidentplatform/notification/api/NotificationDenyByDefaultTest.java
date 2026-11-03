package com.incidentplatform.notification.api;

import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.config.SecurityConfig;
import com.incidentplatform.notification.slack.SlackActionService;
import com.incidentplatform.notification.slack.SlackSignatureVerifier;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Every route of notification-service refuses a caller that has not
 * authenticated (backlog #0-192, ADR-0048), in the shape of incident-service's
 * {@code IncidentDenyByDefaultTest}: the real {@link SecurityConfig}, every
 * route and method of {@link RequestMappingHandlerMapping}, and three callers:
 * no {@code Authorization} (401), a real purpose token addressed to this
 * service (401 or 403), and an unknown API key as {@code ApiKey ipl_…} and as
 * {@code Bearer ipl_…} (401).
 *
 * <h2>Two targets, because the one route is exempt</h2>
 * The service's only controller route, {@code POST /api/v1/slack/actions}, is
 * {@code permitAll()} in the chain: Slack cannot send a platform token and signs
 * the request instead. So the walk of the non-exempt routes finds nothing today
 * (it walks a route added later), and the test checks two targets besides:
 * <ul>
 *   <li>a path no controller maps, which reaches the chain's {@code anyRequest()}
 *       rule, with every {@link RequestMethod} and the three callers; that rule
 *       is what a new route falls under;</li>
 *   <li>the Slack route with no signature, which gets 401 from the real
 *       {@link SlackSignatureVerifier} (not a mock, as
 *       {@code SlackWebhookControllerSecurityTest} has it), with a test signing
 *       secret; a correctly signed request gets 200, so the 401 is the
 *       verifier's and the chain does let the route through.</li>
 * </ul>
 * {@link CorsPreflight} sends a CORS preflight to the unmapped path, for the
 * same reason (backlog #0-200).
 *
 * <h2>The chain as production builds it</h2>
 * The filters are the shared auto-configuration's beans with a real
 * {@link JwtUtils} and its properties: no hand-made {@code JwtAuthFilter} and
 * no mocked {@code JwtUtils}. The scan starts at the service's root package, so
 * a controller in a new package is walked too. The web slice filters out
 * {@link SlackSignatureVerifier} (a {@code @Component}) and does not bind
 * {@link NotificationChannelProperties}, so both are added by hand.
 * {@link #aValidUserTokenGetsPastAuthentication()} shows a valid token does
 * authenticate in this context, so the 401s are the chain refusing the caller.
 *
 * <h2>An unknown API key, without {@code ApiKeyAuthFilter}</h2>
 * notification's chain does not add {@code ApiKeyAuthFilter} (backlog #0-193), so
 * nothing in the chain reads the key: its 401 comes from
 * {@link UnauthorizedEntryPoint}, as for an anonymous caller. The test pins the
 * status either way; #0-193 decides which filter gives it.
 *
 * <h2>Why the exempt set is a literal and not {@code PUBLIC_PATHS}</h2>
 * Reading {@code SharedSecurityAutoConfiguration.PUBLIC_PATHS} here would
 * exempt exactly the route a bad new entry there opens. Each exemption is
 * written out with its reason; a route the walk finds open is a finding, not
 * a new entry. {@link #exemptSetIsTheSlackRouteAndErrorOnly()} pins the set.
 */
@WebMvcTest
@ContextConfiguration(classes = NotificationDenyByDefaultTest.TestApplication.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class, JwtUtils.class, SlackSignatureVerifier.class})
@ImportAutoConfiguration(SharedSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
        "security.cors.allowed-origins=http://localhost:4200",
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "jwt.refresh-token-ttl=P30D",
        "spring.application.name=notification-service",
        "notification.channels.slack.signing-secret=" + NotificationDenyByDefaultTest.TEST_SIGNING_SECRET
})
@DisplayName("notification-service — every route refuses an unauthenticated caller")
class NotificationDenyByDefaultTest {

    /** An obviously fake signing secret, the one {@code SlackSignatureVerifierTest} uses. */
    static final String TEST_SIGNING_SECRET = "test-signing-secret-32chars-long";

    private static final String SLACK_ACTIONS = "/api/v1/slack/actions";

    /**
     * Pattern to the reason the chain lets it through on purpose. A key ending
     * in {@code /**} covers the patterns below it.
     */
    private static final Map<String, String> EXEMPT = Map.of(
            // Spring Boot's BasicErrorController: it renders the error of a
            // request the chain has already handled.
            "/error", "Spring Boot's error page, public in every chain",
            // permitAll() in SecurityConfig: Slack cannot send a platform token.
            // SlackWebhookController verifies the request's HMAC signature
            // instead (slackActionsWithoutSignatureIs401FromTheRealVerifier).
            SLACK_ACTIONS, "Slack signs it; verified by SlackSignatureVerifier");

    /** A path no controller maps: it reaches the chain's anyRequest() rule. */
    private static final String UNMAPPED_PATH = "/api/v1/test-only/unmapped";

    /** An obviously fake Integration key: the {@code ipl_} prefix and 32 characters. */
    private static final String UNKNOWN_KEY = "ipl_" + "0".repeat(32);

    /**
     * notification-service's {@code spring.application.name}: it has no
     * {@code ServiceNames} entry, and this item does not change {@code shared}.
     */
    private static final String NOTIFICATION_SERVICE = "notification-service";

    /** Form-encoded {@code {"type":"block_actions"}}, as Slack sends it. */
    private static final String SLACK_BODY = "payload=%7B%22type%22%3A%22block_actions%22%7D";

    @SpringBootApplication(scanBasePackages = {
            "com.incidentplatform.notification",
            "com.incidentplatform.shared.security",
            "com.incidentplatform.shared.exception",
            "com.incidentplatform.shared.observability"
    })
    @EnableConfigurationProperties(NotificationChannelProperties.class)
    static class TestApplication {
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    // The controller's collaborator: reached only by a correctly signed request.
    @MockitoBean private SlackActionService slackActionService;
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
    @DisplayName("no Authorization header: 401 on every non-exempt route")
    void everyRouteRefusesAnAnonymousCaller() throws Exception {
        assertEveryRoute(mockMvc, handlerMapping, "anonymous", request -> request, status -> status == 401);
    }

    @Test
    @DisplayName("a real purpose token addressed to notification-service: 401 or 403 on every non-exempt route")
    void everyRouteRefusesAPurposeToken() throws Exception {
        final String token = purposeToken();

        assertEveryRoute(mockMvc, handlerMapping, "purpose token",
                request -> request.header("Authorization", "Bearer " + token),
                status -> status == 401 || status == 403);
    }

    @Test
    @DisplayName("an unknown API key, as 'ApiKey ipl_…' and as 'Bearer ipl_…': 401 on every non-exempt route")
    void everyRouteRefusesAnUnknownApiKey() throws Exception {
        assertEveryRoute(mockMvc, handlerMapping, "ApiKey ipl_",
                request -> request.header("Authorization", "ApiKey " + UNKNOWN_KEY), status -> status == 401);
        assertEveryRoute(mockMvc, handlerMapping, "Bearer ipl_",
                request -> request.header("Authorization", "Bearer " + UNKNOWN_KEY), status -> status == 401);
    }

    /**
     * Every {@link RequestMethod} is sent to the unmapped path. One of them never
     * reaches the {@code anyRequest()} rule, so it gets its own pinned answer
     * instead of AC1's, and never a 2xx: {@code TRACE}, which Spring Security's
     * {@code StrictHttpFirewall} rejects with 400 before any chain runs.
     * {@code OPTIONS} without preflight headers is checked like every other
     * method (backlog #0-199, ADR-0049): {@code buildCommonSecurity} no longer
     * permits it on every path, and its {@code CorsFilter} answers only a valid
     * preflight, which these requests are not.
     */
    @Test
    @DisplayName("a path no controller maps, every method: 401 anonymous, 401/403 purpose token, 401 unknown key")
    void anUnmappedPathRefusesTheThreeCallers() throws Exception {
        final Map<RequestMethod, IntPredicate> beforeAnyRequest = Map.of(
                RequestMethod.TRACE, status -> status == 400);
        final List<String> unmapped = new ArrayList<>();
        for (final RequestMethod method : RequestMethod.values()) {
            if (!beforeAnyRequest.containsKey(method)) {
                unmapped.add(method + " " + UNMAPPED_PATH);
            }
        }
        assertThat(unmapped).as("methods that reach the anyRequest() rule").hasSize(7)
                .contains("OPTIONS " + UNMAPPED_PATH);
        final String token = purposeToken();
        final Map<String, UnaryOperator<MockHttpServletRequestBuilder>> callers = Map.of(
                "anonymous", request -> request,
                "purpose token", request -> request.header("Authorization", "Bearer " + token),
                "ApiKey ipl_", request -> request.header("Authorization", "ApiKey " + UNKNOWN_KEY),
                "Bearer ipl_", request -> request.header("Authorization", "Bearer " + UNKNOWN_KEY));

        assertRoutes(mockMvc, unmapped, "anonymous", callers.get("anonymous"), status -> status == 401);
        assertRoutes(mockMvc, unmapped, "purpose token", callers.get("purpose token"),
                status -> status == 401 || status == 403);
        assertRoutes(mockMvc, unmapped, "ApiKey ipl_", callers.get("ApiKey ipl_"), status -> status == 401);
        assertRoutes(mockMvc, unmapped, "Bearer ipl_", callers.get("Bearer ipl_"), status -> status == 401);
        for (final Map.Entry<RequestMethod, IntPredicate> pinned : beforeAnyRequest.entrySet()) {
            for (final Map.Entry<String, UnaryOperator<MockHttpServletRequestBuilder>> caller : callers.entrySet()) {
                assertRoutes(mockMvc, List.of(pinned.getKey() + " " + UNMAPPED_PATH), caller.getKey(),
                        caller.getValue(), pinned.getValue());
            }
        }
    }

    @Test
    @DisplayName("POST /api/v1/slack/actions with no Slack signature: 401 from the real verifier, nothing processed")
    void slackActionsWithoutSignatureIs401FromTheRealVerifier() throws Exception {
        final int status = mockMvc.perform(post(SLACK_ACTIONS)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Slack-Request-Timestamp", String.valueOf(Instant.now().getEpochSecond()))
                        .content(SLACK_BODY))
                .andReturn().getResponse().getStatus();

        assertThat(status).as("status of an unsigned Slack request").isEqualTo(401);
        then(slackActionService).should(never()).processAction(any());
    }

    /**
     * The same request signed as Slack signs it (HMAC-SHA256 of
     * {@code v0:<timestamp>:<body>} with the test secret, the timestamp taken
     * now, inside the verifier's five minutes): the chain lets the route
     * through, and the unsigned request's 401 is the verifier's.
     */
    @Test
    @DisplayName("POST /api/v1/slack/actions correctly signed: 200 and the action is processed")
    void slackActionsCorrectlySignedIs200() throws Exception {
        final String timestamp = String.valueOf(Instant.now().getEpochSecond());

        final int status = mockMvc.perform(post(SLACK_ACTIONS)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Slack-Request-Timestamp", timestamp)
                        .header("X-Slack-Signature", sign(timestamp, SLACK_BODY))
                        .content(SLACK_BODY))
                .andReturn().getResponse().getStatus();

        assertThat(status).as("status of a correctly signed Slack request").isEqualTo(200);
        then(slackActionService).should().processAction("{\"type\":\"block_actions\"}");
    }

    /**
     * ADR-0048 rule 4 for a service whose one route is exempt: the exempt set
     * is exactly {@code /error} and the Slack route, and the enumeration finds
     * that route (and leaves it out of the walk), so an enumeration that finds
     * nothing fails here.
     */
    @Test
    @DisplayName("the exempt set is /error and the Slack route only, and the enumeration finds the Slack route")
    void exemptSetIsTheSlackRouteAndErrorOnly() {
        assertThat(EXEMPT.keySet()).as("exempt patterns").containsExactlyInAnyOrder("/error", SLACK_ACTIONS);
        assertThat(allRoutes(handlerMapping)).as("every route of the handler mapping")
                .contains("POST " + SLACK_ACTIONS)
                .doesNotContain("GET " + OpenRouteTestController.PATH);
        assertThat(routes(handlerMapping)).as("routes walked").doesNotContain("POST " + SLACK_ACTIONS);
    }

    /**
     * ADR-0048 rule 7: a valid user token gets past authentication, so the
     * 401s above come from refusing their callers, not from a context in which
     * no token validates. The unmapped path is the one {@code anyRequest()}
     * target; past the chain no handler maps it, so its answer is not 401.
     */
    @Test
    @DisplayName("a valid RESPONDER token is authenticated: not 401 on the unmapped path")
    void aValidUserTokenGetsPastAuthentication() throws Exception {
        final String token = jwtUtils.generateToken(UUID.randomUUID(), "acme-corp", "user@acme.com",
                List.of(SecurityRoles.ROLE_RESPONDER), List.of(), List.of());

        final int status = mockMvc.perform(get(UNMAPPED_PATH).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();

        assertThat(status).as("status for a valid RESPONDER token").isNotEqualTo(401);
    }

    /**
     * notification's own {@link SecurityConfig} answers a browser's CORS
     * preflight without a token, and nothing else about {@code OPTIONS} is
     * exempt (backlog #0-200, ADR-0049 rule 4): its {@code CorsFilter} answers
     * a valid preflight before authentication, and any other {@code OPTIONS} is
     * authenticated like every method. It shares the enclosing context, whose
     * {@code @ImportAutoConfiguration} supplies the shared
     * {@code corsConfigurationSource}; without that source the preflight would
     * get 403, and without {@code CorsFilter} in the chain 401.
     *
     * <p>Sent to {@link #UNMAPPED_PATH}: the service's one controller route is
     * the {@code permitAll()} Slack route, so no controller route is
     * authenticated, and the {@code anyRequest()} rule the unmapped path reaches
     * is the one a new route falls under. No handler maps it, so there is no
     * collaborator to check: the 200 and its {@code Access-Control-Allow-Origin}
     * can only be {@code CorsFilter}'s, as an anonymous caller past it gets 401.
     */
    @Nested
    @DisplayName("OPTIONS preflight")
    class CorsPreflight {

        /** The enclosing class's {@code security.cors.allowed-origins}. */
        private static final String ALLOWED_ORIGIN = "http://localhost:4200";

        @Test
        @DisplayName("a preflight from the allowed origin, no token: 200 with the origin")
        void allowedPreflightIs200WithAllowOrigin() throws Exception {
            final MockHttpServletResponse response = mockMvc.perform(options(UNMAPPED_PATH)
                            .header("Origin", ALLOWED_ORIGIN)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Authorization"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).as("status of an allowed preflight").isEqualTo(200);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).as("Access-Control-Allow-Origin")
                    .isEqualTo(ALLOWED_ORIGIN);
        }

        @Test
        @DisplayName("a bare OPTIONS with no principal: 401 like every method")
        void bareOptionsIs401() throws Exception {
            final int status = mockMvc.perform(options(UNMAPPED_PATH)).andReturn().getResponse().getStatus();

            assertThat(status).as("status of a bare anonymous OPTIONS").isEqualTo(401);
        }
    }

    /**
     * The walk itself goes red on an open route: the same {@link SecurityConfig}
     * plus a test-only route and a chain that permits it. Its own context, so
     * the outer one never holds either class
     * ({@link #exemptSetIsTheSlackRouteAndErrorOnly()} asserts it).
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
            "spring.application.name=notification-service"
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

    private String purposeToken() {
        return jwtUtils.generatePurposeToken(
                "some-service", TokenPurposes.API_KEY_INTROSPECTION, NOTIFICATION_SERVICE);
    }

    /** {@code v0=} and the hex HMAC-SHA256 of {@code v0:<timestamp>:<body>}, as Slack signs a request. */
    private static String sign(String timestamp, String body) throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "v0=" + HexFormat.of().formatHex(
                mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8)));
    }

    /** {@link #assertRoutes} over every walked route of the handler mapping. */
    private static void assertEveryRoute(MockMvc mockMvc, RequestMappingHandlerMapping handlerMapping,
                                         String caller, UnaryOperator<MockHttpServletRequestBuilder> auth,
                                         IntPredicate expected) throws Exception {
        assertRoutes(mockMvc, routes(handlerMapping), caller, auth, expected);
    }

    /**
     * Sends every route, with its path variables filled and a JSON body, and
     * fails naming each one whose status {@code expected} rejects.
     */
    private static void assertRoutes(MockMvc mockMvc, List<String> routes, String caller,
                                     UnaryOperator<MockHttpServletRequestBuilder> auth,
                                     IntPredicate expected) throws Exception {
        final List<String> misses = new ArrayList<>();
        for (final String route : routes) {
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

    /** Every walked {@code METHOD pattern}: {@link #allRoutes} outside {@link #EXEMPT}. */
    private static List<String> routes(RequestMappingHandlerMapping handlerMapping) {
        return allRoutes(handlerMapping).stream()
                .filter(route -> !isExempt(route.substring(route.indexOf(' ') + 1)))
                .toList();
    }

    /**
     * Every {@code METHOD pattern} of the handler mapping. A mapping with no
     * method condition answers every method, so it is listed with every
     * {@link RequestMethod}: a chain rule keyed on the method (a
     * {@code permitAll} on {@code POST} of the path) is seen too (ADR-0048 rule 2).
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
