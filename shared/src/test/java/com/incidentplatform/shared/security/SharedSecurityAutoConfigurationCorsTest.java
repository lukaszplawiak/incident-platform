package com.incidentplatform.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * What a request with method {@code OPTIONS} answers in a chain built from
 * {@link SharedSecurityAutoConfiguration#buildCommonSecurity} (backlog #0-199,
 * ADR-0049): a valid CORS preflight is answered by the chain's
 * {@link CorsFilter} before authorization, a foreign one is 403, and every
 * other {@code OPTIONS} is authenticated like any method, so an explicit
 * {@code @RequestMapping(method = OPTIONS)} handler never runs for an
 * anonymous caller.
 *
 * <p>The real auto-configuration and the real chain, sent through
 * {@link MockMvc} with the {@code springSecurityFilterChain} bean as its
 * filter; the authenticated caller carries a real JWT from {@link JwtUtils}
 * (ADR-0048 rule 7), so no {@code spring-security-test}. The user configuration
 * has {@code @EnableWebMvc}, so Spring MVC's beans, among them
 * {@code mvcHandlerMappingIntrospector} (a {@link CorsConfigurationSource}),
 * are registered before the auto-configuration's conditions are evaluated:
 * the order in which a type-based condition made the shared source back off.
 *
 * <p>The two test-only controllers are not mapped under {@code /error}, where
 * an {@code ERROR} dispatch would match a method-less mapping for
 * {@code OPTIONS}. Each counts its invocations in an
 * {@link InvocationRecorder} bean of its own context, so no state is shared
 * between tests.
 */
@DisplayName("SharedSecurityAutoConfiguration - CORS and OPTIONS (backlog #0-199)")
class SharedSecurityAutoConfigurationCorsTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:4200";
    private static final String FOREIGN_ORIGIN = "https://evil.example";
    private static final String CUSTOM_ORIGIN = "https://custom.example";

    private static final String ACAO = "Access-Control-Allow-Origin";

    private final WebApplicationContextRunner contextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            SharedSecurityAutoConfiguration.class))
                    .withUserConfiguration(CorsTestConfig.class)
                    .withPropertyValues(
                            "security.cors.allowed-origins=" + ALLOWED_ORIGIN,
                            "jwt.secret=" + "s".repeat(64),
                            "jwt.access-token-ttl=PT15M",
                            "jwt.service-token-ttl=PT1H",
                            "jwt.refresh-token-ttl=P30D");

    /** Spring MVC and Spring Security as a service has them, plus the test-only controllers. */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({InvocationRecorder.class, MethodlessController.class, ExplicitOptionsController.class})
    static class CorsTestConfig {

        @Bean
        JwtUtils jwtUtils(JwtProperties properties) {
            return new JwtUtils(properties);
        }

        /** The 401 body carries an Instant, so the mapper has the JSR-310 module, as Boot's has. */
        @Bean
        UnauthorizedEntryPoint unauthorizedEntryPoint(ObjectMapper objectMapper) {
            return new UnauthorizedEntryPoint(objectMapper);
        }

        /** Every service has one (JacksonAutoConfiguration); the ApiKeyAuthFilter bean needs it. */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    /** A service's own chain from buildCommonSecurity that names its own CORS source (AC8). */
    @Configuration
    static class CustomSourceChainConfig {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                JwtAuthFilter jwtAuthFilter,
                                                UnauthorizedEntryPoint unauthorizedEntryPoint) throws Exception {
            final CorsConfigurationSource custom = sourceAllowing(CUSTOM_ORIGIN);
            return SharedSecurityAutoConfiguration.buildCommonSecurity(http, jwtAuthFilter, unauthorizedEntryPoint)
                    .cors(cors -> cors.configurationSource(custom))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(SharedSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                            .anyRequest().access(SharedSecurityAutoConfiguration.authenticatedExceptPurposeTokens()))
                    .build();
        }
    }

    /** How many times each test-only handler ran, in this context. */
    static class InvocationRecorder {
        final AtomicInteger methodless = new AtomicInteger();
        final AtomicInteger explicitOptions = new AtomicInteger();
    }

    /** A mapping with no method condition: Spring MVC answers an OPTIONS to it itself. */
    @RestController
    static class MethodlessController {

        static final String PATH = "/api/v1/test-only/any";

        private final InvocationRecorder recorder;

        MethodlessController(InvocationRecorder recorder) {
            this.recorder = recorder;
        }

        @RequestMapping(PATH)
        String any() {
            recorder.methodless.incrementAndGet();
            return "any";
        }
    }

    /** An explicit OPTIONS handler: the mapping an anonymous OPTIONS reached before #0-199. */
    @RestController
    static class ExplicitOptionsController {

        static final String PATH = "/api/v1/test-only/options";

        private final InvocationRecorder recorder;

        ExplicitOptionsController(InvocationRecorder recorder) {
            this.recorder = recorder;
        }

        @RequestMapping(path = PATH, method = RequestMethod.OPTIONS)
        ResponseEntity<Void> options() {
            recorder.explicitOptions.incrementAndGet();
            return ResponseEntity.noContent().build();
        }
    }

    @Test
    @DisplayName("AC1: defaultSecurityFilterChain has a CorsFilter ahead of JwtAuthFilter and AuthorizationFilter")
    void defaultChainHasCorsFilterBeforeAuthorization() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            final SecurityFilterChain chain = context.getBean("defaultSecurityFilterChain", SecurityFilterChain.class);
            final int cors = indexOf(chain, CorsFilter.class);
            assertThat(cors).as("CorsFilter in the chain").isNotNegative();
            assertThat(cors).as("before JwtAuthFilter").isLessThan(indexOf(chain, JwtAuthFilter.class));
            assertThat(cors).as("before AuthorizationFilter").isLessThan(indexOf(chain, AuthorizationFilter.class));
        });
    }

    @Test
    @DisplayName("AC16/AC2: with Spring MVC registered first, the bean named corsConfigurationSource is the shared one "
            + "and an allowed preflight gets 200")
    void sharedSourceIsTheNamedBeanEvenWithMvcFirst() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("mvcHandlerMappingIntrospector"))
                    .as("Spring MVC's introspector, itself a CorsConfigurationSource")
                    .isInstanceOf(CorsConfigurationSource.class);
            assertThat(context.getBean("corsConfigurationSource"))
                    .isInstanceOf(UrlBasedCorsConfigurationSource.class);

            final MvcResult result = perform(context, preflight(MethodlessController.PATH, ALLOWED_ORIGIN));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getHeader(ACAO)).isEqualTo(ALLOWED_ORIGIN);
        });
    }

    @Test
    @DisplayName("AC2/AC3: a preflight from an allowed origin, no token, gets 200 with the allowed origin; "
            + "no handler runs")
    void allowedPreflightIs200WithoutTokenAndInvokesNoHandler() {
        contextRunner.run(context -> {
            for (final String path : List.of(MethodlessController.PATH, ExplicitOptionsController.PATH)) {
                final MvcResult result = perform(context, preflight(path, ALLOWED_ORIGIN));
                assertThat(result.getResponse().getStatus()).as("status on %s", path).isEqualTo(200);
                assertThat(result.getResponse().getHeader(ACAO)).as("ACAO on %s", path).isEqualTo(ALLOWED_ORIGIN);
            }
            assertNoHandlerRan(context);
        });
    }

    @Test
    @DisplayName("AC4: a preflight from a foreign origin gets 403; no handler runs")
    void foreignPreflightIs403() {
        contextRunner.run(context -> {
            for (final String path : List.of(MethodlessController.PATH, ExplicitOptionsController.PATH)) {
                final MvcResult result = perform(context, preflight(path, FOREIGN_ORIGIN));
                assertThat(result.getResponse().getStatus()).as("status on %s", path).isEqualTo(403);
                assertThat(result.getResponse().getHeader(ACAO)).as("ACAO on %s", path).isNull();
            }
            assertNoHandlerRan(context);
        });
    }

    @Test
    @DisplayName("AC5: a bare anonymous OPTIONS gets 401 on the method-less and on the explicit OPTIONS mapping; "
            + "no handler runs")
    void bareAnonymousOptionsIs401OnBothMappings() {
        contextRunner.run(context -> {
            assertThat(perform(context, options(MethodlessController.PATH)).getResponse().getStatus())
                    .as("method-less mapping").isEqualTo(401);
            assertThat(perform(context, options(ExplicitOptionsController.PATH)).getResponse().getStatus())
                    .as("explicit OPTIONS mapping").isEqualTo(401);
            assertNoHandlerRan(context);
        });
    }

    @Test
    @DisplayName("AC5: an OPTIONS with an Origin but no Access-Control-Request-Method is no preflight: "
            + "401 from an allowed origin, 403 from a foreign one; no handler runs")
    void optionsWithOriginButNoRequestMethodIsNotAPreflight() {
        contextRunner.run(context -> {
            for (final String path : List.of(MethodlessController.PATH, ExplicitOptionsController.PATH)) {
                assertThat(perform(context, options(path).header("Origin", ALLOWED_ORIGIN))
                        .getResponse().getStatus()).as("allowed origin on %s", path).isEqualTo(401);
                assertThat(perform(context, options(path).header("Origin", FOREIGN_ORIGIN))
                        .getResponse().getStatus()).as("foreign origin on %s", path).isEqualTo(403);
            }
            assertNoHandlerRan(context);
        });
    }

    @Test
    @DisplayName("AC6: an authenticated caller's bare OPTIONS reaches the explicit OPTIONS handler")
    void authenticatedOptionsReachesExplicitHandler() {
        contextRunner.run(context -> {
            final MvcResult result = perform(context, options(ExplicitOptionsController.PATH)
                    .header("Authorization", "Bearer " + userToken(context)));

            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(context.getBean(InvocationRecorder.class).explicitOptions).hasValue(1);
        });
    }

    @Test
    @DisplayName("AC6b: an authenticated caller's bare OPTIONS on the method-less mapping gets Spring MVC's 200 "
            + "with Allow; the handler does not run")
    void authenticatedOptionsOnMethodlessMappingIsAnsweredByMvc() {
        contextRunner.run(context -> {
            final MvcResult result = perform(context, options(MethodlessController.PATH)
                    .header("Authorization", "Bearer " + userToken(context)));

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getHeader("Allow")).contains("GET", "POST", "OPTIONS");
            assertThat(context.getBean(InvocationRecorder.class).methodless).hasValue(0);
        });
    }

    @Test
    @DisplayName("AC7: TRACE gets 400 from StrictHttpFirewall")
    void traceIs400FromFirewall() {
        contextRunner.run(context -> {
            assertThat(perform(context, request(HttpMethod.TRACE, MethodlessController.PATH))
                    .getResponse().getStatus()).isEqualTo(400);
            assertNoHandlerRan(context);
        });
    }

    @Test
    @DisplayName("AC8: a service's own .cors(configurationSource(custom)) on a chain from buildCommonSecurity wins")
    void serviceSourceOnTheChainWins() {
        contextRunner.withUserConfiguration(CustomSourceChainConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("defaultSecurityFilterChain");

            final MvcResult custom = perform(context, preflight(MethodlessController.PATH, CUSTOM_ORIGIN));
            assertThat(custom.getResponse().getStatus()).as("the custom source's origin").isEqualTo(200);
            assertThat(custom.getResponse().getHeader(ACAO)).isEqualTo(CUSTOM_ORIGIN);
            assertThat(perform(context, preflight(MethodlessController.PATH, ALLOWED_ORIGIN))
                    .getResponse().getStatus()).as("the shared default's origin").isEqualTo(403);
        });
    }

    @Test
    @DisplayName("AC16: a service's own bean named corsConfigurationSource replaces the shared one")
    void serviceBeanNamedCorsConfigurationSourceReplacesShared() {
        final CorsConfigurationSource custom = sourceAllowing(CUSTOM_ORIGIN);
        contextRunner.withBean("corsConfigurationSource", CorsConfigurationSource.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("corsConfigurationSource")).isSameAs(custom);

                    assertThat(perform(context, preflight(MethodlessController.PATH, CUSTOM_ORIGIN))
                            .getResponse().getStatus()).as("the service's origin").isEqualTo(200);
                    assertThat(perform(context, preflight(MethodlessController.PATH, ALLOWED_ORIGIN))
                            .getResponse().getStatus()).as("the shared default's origin").isEqualTo(403);
                });
    }

    /** A real preflight: an Origin and an Access-Control-Request-Method, no Authorization. */
    private static MockHttpServletRequestBuilder preflight(String path, String origin) {
        return options(path)
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST");
    }

    private static MvcResult perform(AssertableWebApplicationContext context,
                                     MockHttpServletRequestBuilder request) throws Exception {
        final MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext())
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        return mockMvc.perform(request).andReturn();
    }

    private static String userToken(AssertableWebApplicationContext context) {
        return context.getBean(JwtUtils.class).generateToken(UUID.randomUUID(), "acme-corp", "user@acme.com",
                List.of(SecurityRoles.ROLE_RESPONDER), List.of(), List.of());
    }

    private static void assertNoHandlerRan(AssertableWebApplicationContext context) {
        final InvocationRecorder recorder = context.getBean(InvocationRecorder.class);
        assertThat(recorder.methodless).as("method-less handler invocations").hasValue(0);
        assertThat(recorder.explicitOptions).as("explicit OPTIONS handler invocations").hasValue(0);
    }

    private static CorsConfigurationSource sourceAllowing(String origin) {
        final CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        config.setAllowedMethods(List.of("GET", "POST"));
        config.setAllowedHeaders(List.of("*"));
        final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static int indexOf(SecurityFilterChain chain, Class<?> type) {
        for (int i = 0; i < chain.getFilters().size(); i++) {
            if (type.isInstance(chain.getFilters().get(i))) {
                return i;
            }
        }
        return -1;
    }
}
