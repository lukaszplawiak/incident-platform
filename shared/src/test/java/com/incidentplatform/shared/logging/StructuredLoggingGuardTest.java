package com.incidentplatform.shared.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every reason {@link StructuredLoggingGuard} refuses to start a service
 * (backlog #0-94), one at a time, on an environment built here rather than a
 * running application, so that each branch is shown to fire on its own.
 * {@code StructuredLoggingDefaultsTest} shows the guard doing so in a real
 * {@code SpringApplication}.
 */
@DisplayName("StructuredLoggingGuard — what stops a service whose logs would not be ECS (backlog #0-94)")
class StructuredLoggingGuardTest {

    private static final String CONSOLE = "logging.structured.format.console";
    private static final String FILE = "logging.structured.format.file";

    @Test
    @DisplayName("ECS on both outputs, in any case and padded, is fine")
    void ecsIsFine() {
        assertThat(problems(Map.of(CONSOLE, "ecs", FILE, "ecs"))).isEmpty();
        assertThat(problems(Map.of(CONSOLE, "ECS", FILE, " ecs "))).isEmpty();
    }

    @Test
    @DisplayName("an empty or other format, on the console or the file alone, is refused")
    void eachOutputOnItsOwn() {
        assertThat(problems(Map.of(CONSOLE, "", FILE, "ecs"))).containsExactly(CONSOLE + " is empty");
        assertThat(problems(Map.of(FILE, "ecs"))).containsExactly(CONSOLE + " is empty");
        assertThat(problems(Map.of(CONSOLE, "ecs", FILE, "logstash"))).containsExactly(FILE + " is not ecs");
    }

    @Test
    @DisplayName("a configured value is not quoted in the refusal: it comes from outside")
    void valueNotQuoted() {
        assertThat(problems(Map.of(CONSOLE, "x\r\nINFO forged", FILE, "ecs")))
                .singleElement().asString().doesNotContain("forged").doesNotContain("\n");
    }

    @Test
    @DisplayName("logging.config and logback.configurationFile are refused")
    void logbackConfigurationNamed() {
        assertThat(problems(Map.of(CONSOLE, "ecs", FILE, "ecs", "logging.config", "classpath:my.xml")))
                .containsExactly("logging.config names a Logback file of its own");
        assertThat(problems(Map.of(CONSOLE, "ecs", FILE, "ecs", "logback.configurationFile", "/etc/my.xml")))
                .containsExactly("logback.configurationFile names a Logback file of its own");
    }

    @Test
    @DisplayName("each Logback file Spring Boot would load, test ones included, is refused when on the classpath")
    void logbackFilesOnTheClasspath() {
        for (final String file : StructuredLoggingGuard.LOGBACK_FILES) {
            assertThat(StructuredLoggingGuard.problems(environment(Map.of(CONSOLE, "ecs", FILE, "ecs")),
                    file::equals))
                    .as(file)
                    .containsExactly(file + " is on the classpath");
        }
        assertThat(StructuredLoggingGuard.LOGBACK_FILES).contains("logback-test.xml", "logback-spring.xml");
    }

    @Test
    @DisplayName("the classpath is really searched (the context class loader, as Spring Boot's own lookup)")
    void classpathLookup(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logback.xml"), "<configuration/>");
        final Thread thread = Thread.currentThread();
        final ClassLoader original = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, original)) {
            thread.setContextClassLoader(loader);
            assertThat(StructuredLoggingGuard.onClasspath("logback.xml")).isTrue();
            assertThat(StructuredLoggingGuard.onClasspath("logback-spring.xml")).isFalse();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    @DisplayName("the JSON object's shape is the platform's: a renamed, added or included field is refused")
    void jsonReshaped() {
        for (final String key : new String[] {"logging.structured.json.rename.tenantId",
                "logging.structured.json.add.team", "logging.structured.json.include"}) {
            assertThat(problems(ecsWith(key, "x")))
                    .as(key)
                    .containsExactly("logging.structured.json is reshaped (1 setting(s) of its own)");
        }
    }

    @Test
    @DisplayName("an exclude of a service's own (tenantId hidden, the internal key let in) is refused")
    void excludeReplaced() {
        assertThat(problems(ecsWith("logging.structured.json.exclude", "tenantId")))
                .containsExactly("logging.structured.json.exclude is not the platform's");
        assertThat(problems(ECS)).isEmpty();
    }

    /** The switch must really switch the guard off, and only when true. */
    @Test
    @DisplayName("platform.logging.plain-text: true lets a bad environment start, with a WARN; false does not")
    void plainTextSwitch() {
        final Map<String, String> plain = new HashMap<>(Map.of(CONSOLE, "", FILE, "ecs"));
        final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(StructuredLoggingGuard.class);
        final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            plain.put(StructuredLoggingGuard.PLAIN_TEXT_PROPERTY, "true");
            new StructuredLoggingGuard().onApplicationEvent(prepared(environment(plain)));
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getFormattedMessage()).contains(StructuredLoggingGuard.PLAIN_TEXT_PROPERTY);
        });

        plain.put(StructuredLoggingGuard.PLAIN_TEXT_PROPERTY, "false");
        assertThatThrownBy(() -> new StructuredLoggingGuard().onApplicationEvent(prepared(environment(plain))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CONSOLE + " is empty");
    }

    @Test
    @DisplayName("the switch binds from either environment variable spelling (README names PLATFORM_LOGGING_PLAINTEXT)")
    void plainTextSwitchFromTheEnvironment() {
        for (final String variable : new String[] {"PLATFORM_LOGGING_PLAINTEXT", "PLATFORM_LOGGING_PLAIN_TEXT"}) {
            final ConfigurableEnvironment environment = environment(Map.of(CONSOLE, "", FILE, "ecs"));
            // In place of the real one, under its name: Spring Boot maps variable
            // names only for a source called systemEnvironment, as in a service.
            environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of(variable, "true")));
            ConfigurationPropertySources.attach(environment);
            assertThat(environment.getProperty(StructuredLoggingGuard.PLAIN_TEXT_PROPERTY, Boolean.class, false))
                    .as(variable).isTrue();
        }
    }

    private static ApplicationEnvironmentPreparedEvent prepared(ConfigurableEnvironment environment) {
        return new ApplicationEnvironmentPreparedEvent(new DefaultBootstrapContext(),
                new SpringApplication(), new String[0], environment);
    }

    private static java.util.List<String> problems(Map<String, String> properties) {
        return StructuredLoggingGuard.problems(environment(properties), file -> false);
    }

    /** The given properties over the platform's own default exclude, as in a service. */
    private static ConfigurableEnvironment environment(Map<String, String> properties) {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("platform",
                Map.of("logging.structured.json.exclude", StructuredLoggingDefaults.INTERNAL_MDC_KEYS)));
        environment.getPropertySources().addFirst(new MapPropertySource("test", new HashMap<>(properties)));
        return environment;
    }

    private static final Map<String, String> ECS = Map.of(CONSOLE, "ecs", FILE, "ecs");

    private static Map<String, String> ecsWith(String key, String value) {
        final Map<String, String> properties = new HashMap<>(ECS);
        properties.put(key, value);
        return properties;
    }
}
