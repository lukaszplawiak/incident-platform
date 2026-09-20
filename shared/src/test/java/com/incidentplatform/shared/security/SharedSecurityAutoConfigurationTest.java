package com.incidentplatform.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring tests for the {@link TokenRevocationChecker} defaults in
 * {@link SharedSecurityAutoConfiguration}.
 *
 * <p>Runs the real auto-configuration in a servlet context rather than
 * calling the {@code @Bean} methods directly, because the behaviour that
 * matters is the {@code @ConditionalOnMissingBean} back-off — a direct
 * method call cannot prove that a service-supplied checker (auth-service's
 * Redis-backed one) replaces the default, or that a class needing the bean
 * (incident-service's {@code StompAuthChannelInterceptor}) can start.
 */
@DisplayName("SharedSecurityAutoConfiguration - token revocation wiring")
class SharedSecurityAutoConfigurationTest {

    private static final String REVOKED_JTI = "revoked-jti";

    private final WebApplicationContextRunner contextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            SharedSecurityAutoConfiguration.class))
                    .withUserConfiguration(SecurityTestConfig.class)
                    .withPropertyValues(
                            "security.cors.allowed-origins=http://localhost:4200",
                            "jwt.secret=" + "s".repeat(64),
                            "jwt.access-token-ttl=PT15M",
                            "jwt.service-token-ttl=PT1H",
                            "jwt.refresh-token-ttl=P30D");

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class SecurityTestConfig {

        @Bean
        JwtUtils jwtUtils(JwtProperties properties) {
            return new JwtUtils(properties);
        }

        @Bean
        UnauthorizedEntryPoint unauthorizedEntryPoint() {
            return new UnauthorizedEntryPoint(new ObjectMapper());
        }
    }

    /**
     * Stand-in for a class that constructor-injects the checker the way
     * incident-service's {@code StompAuthChannelInterceptor} does.
     */
    static class RevocationCheckerConsumer {
        final TokenRevocationChecker checker;

        RevocationCheckerConsumer(TokenRevocationChecker checker) {
            this.checker = checker;
        }
    }

    @Test
    @DisplayName("provides a no-op checker when the service defines none")
    void shouldProvideNoOpCheckerWhenNoneDefined() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TokenRevocationChecker.class);

            final TokenRevocationChecker checker =
                    context.getBean(TokenRevocationChecker.class);
            assertThat(checker.isRevoked(REVOKED_JTI)).isFalse();
        });
    }

    @Test
    @DisplayName("a class that constructor-injects the checker can start "
            + "(incident-service StompAuthChannelInterceptor regression)")
    void shouldStartConsumerThatRequiresTheChecker() {
        contextRunner
                .withBean(RevocationCheckerConsumer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RevocationCheckerConsumer.class).checker)
                            .isSameAs(context.getBean(TokenRevocationChecker.class));
                });
    }

    @Test
    @DisplayName("default JwtAuthFilter is built with the checker bean")
    void shouldBuildDefaultFilterWithTheCheckerBean() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JwtAuthFilter.class);

            assertThat(ReflectionTestUtils.getField(
                    context.getBean(JwtAuthFilter.class), "revocationChecker"))
                    .isSameAs(context.getBean(TokenRevocationChecker.class));
        });
    }

    @Test
    @DisplayName("backs off when the service supplies its own checker "
            + "(auth-service case)")
    void shouldBackOffWhenServiceSuppliesOwnChecker() {
        contextRunner
                .withBean("serviceChecker", TokenRevocationChecker.class,
                        () -> REVOKED_JTI::equals)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TokenRevocationChecker.class);

                    final TokenRevocationChecker checker =
                            context.getBean(TokenRevocationChecker.class);
                    assertThat(checker.isRevoked(REVOKED_JTI)).isTrue();
                    assertThat(checker.isRevoked("other-jti")).isFalse();

                    assertThat(ReflectionTestUtils.getField(
                            context.getBean(JwtAuthFilter.class), "revocationChecker"))
                            .isSameAs(checker);
                });
    }

    @Test
    @DisplayName("default JwtAuthFilter backs off when the service declares "
            + "its own filter")
    void shouldBackOffWhenServiceDeclaresOwnFilter() {
        final TokenRevocationChecker serviceChecker = REVOKED_JTI::equals;

        contextRunner
                .withBean("serviceChecker", TokenRevocationChecker.class,
                        () -> serviceChecker)
                .withBean("jwtAuthFilter", JwtAuthFilter.class,
                        () -> new JwtAuthFilter(
                                new JwtUtils(new JwtProperties(
                                        "s".repeat(64),
                                        java.time.Duration.ofMinutes(15),
                                        java.time.Duration.ofHours(1),
                                        java.time.Duration.ofDays(30))),
                                serviceChecker))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JwtAuthFilter.class);
                    assertThat(ReflectionTestUtils.getField(
                            context.getBean(JwtAuthFilter.class), "revocationChecker"))
                            .isSameAs(serviceChecker);
                });
    }
}
