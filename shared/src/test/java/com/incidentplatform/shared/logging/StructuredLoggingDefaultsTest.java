package com.incidentplatform.shared.logging;

import com.fasterxml.jackson.databind.JsonNode;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.kafka.TenantKafkaRecordInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What every service's log looks like (backlog #0-94, step 1), checked on a
 * real {@code SpringApplication}, so the default reaches Spring Boot's logging
 * system the way it does in a service: one JSON object per line, in ECS, every
 * value escaped, the platform's MDC keys as fields; plain text in the MDC
 * pattern when a developer switches it off.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("StructuredLoggingDefaults — JSON (ECS) logs for every service (backlog #0-94)")
class StructuredLoggingDefaultsTest {

    private static final Logger log = LoggerFactory.getLogger("com.incidentplatform.test.Logging");
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Each test re-initialises the JVM's one logging system; put back Logback's
     * own default afterwards, so no other test in shared logs in whatever format
     * the last one here left.
     */
    @AfterEach
    void restoreLogging() throws Exception {
        MDC.clear();
        LoggingSystem.get(getClass().getClassLoader()).cleanUp();
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        new ContextInitializer(context).autoConfig();
    }

    @Test
    @DisplayName("by default a line is one ECS object (log.level, service.name, ... nested), the MDC keys as top-level fields")
    void jsonByDefault(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext ignored = run("--spring.application.name=test-service")) {
            MDC.put("tenantId", "acme-corp");
            MDC.put("requestId", "req-1");
            MDC.put("userId", "user-1");
            MDC.put("kafkaMessageId", "msg-1");
            MDC.put(TenantKafkaRecordInterceptor.MDC_START_NANOS, "123456789");
            log.warn("delivery failed: reason={}", "SLACK_UNAVAILABLE");
        }

        final JsonNode line = onlyLineContaining(output, "delivery failed");
        assertThat(line.path("message").asText()).isEqualTo("delivery failed: reason=SLACK_UNAVAILABLE");
        assertThat(line.at("/log/level").asText()).isEqualTo("WARN");
        assertThat(line.at("/log/logger").asText()).isEqualTo("com.incidentplatform.test.Logging");
        assertThat(line.at("/service/name").asText()).isEqualTo("test-service");
        assertThat(line.has("@timestamp")).isTrue();
        assertThat(line.path("tenantId").asText()).isEqualTo("acme-corp");
        assertThat(line.path("requestId").asText()).isEqualTo("req-1");
        assertThat(line.path("userId").asText()).isEqualTo("user-1");
        assertThat(line.path("kafkaMessageId").asText()).isEqualTo("msg-1");
        // Every MDC key reaches the log as a field, so the set is pinned; the
        // interceptor's own timing key is kept out.
        // The exact ECS layout of Spring Boot 3.5: a Boot upgrade that adds a
        // field fails here on purpose, to be looked at.
        final List<String> fields = new java.util.ArrayList<>();
        line.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("@timestamp", "log", "process", "service", "message", "ecs",
                "tenantId", "requestId", "userId", "kafkaMessageId");
    }

    /** A deployment adding a log file must not get the unescaped default pattern there. */
    @Test
    @DisplayName("a log file, if a deployment adds one, is JSON (ECS) too")
    void logFileIsJsonToo(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("service.log");
        try (ConfigurableApplicationContext ignored = run(
                "--spring.application.name=test-service", "--logging.file.name=" + file)) {
            log.info("file line\nINFO forged");
        }

        final List<String> lines = Files.readAllLines(file).stream().filter(l -> l.contains("file line")).toList();
        assertThat(lines).hasSize(1);
        assertThat(json.readTree(lines.get(0)).path("message").asText()).isEqualTo("file line\nINFO forged");
    }

    /** The reason for #0-94: a value from outside could forge or split a line. */
    @Test
    @DisplayName("a CR/LF in the message, an argument, an MDC value or an exception stays inside one line")
    void valuesFromOutsideCannotForgeLines(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext ignored = run("--spring.application.name=test-service")) {
            MDC.put("requestId", "req-2\r\n{\"forged\":true}");
            log.error("bad header X-Event-Type={}", "Opened\n12:00:00.000 [other-tenant] INFO forged line",
                    new IllegalStateException("parser said:\r\nINFO forged too"));
        }

        final JsonNode line = onlyLineContaining(output, "bad header");
        assertThat(line.path("message").asText())
                .isEqualTo("bad header X-Event-Type=Opened\n12:00:00.000 [other-tenant] INFO forged line");
        assertThat(line.path("requestId").asText()).isEqualTo("req-2\r\n{\"forged\":true}");
        assertThat(line.at("/error/type").asText()).isEqualTo(IllegalStateException.class.getName());
        assertThat(line.at("/error/message").asText()).isEqualTo("parser said:\r\nINFO forged too");
        assertThat(line.at("/error/stack_trace").asText())
                .startsWith("java.lang.IllegalStateException: parser said:\r\nINFO forged too")
                .contains("\tat ");
        assertThat(output.getOut().lines().filter(l -> l.contains("forged"))).hasSize(1);
    }

    /**
     * A default can be overridden from anywhere, so the guard reads what Spring
     * Boot will use, whatever set it: here a service's own configuration, loaded
     * the way application.yml is (classpath root, by name), and a format set
     * through SPRING_APPLICATION_JSON, which no check of committed files reads.
     */
    @Test
    @DisplayName("a service whose logs would not be ECS refuses to start: application.yml, an argument, "
            + "SPRING_APPLICATION_JSON, logging.config")
    void anOverriddenFormatRefusesToStart() {
        for (final String[] args : List.of(
                new String[] {"--spring.config.name=structured-logging-override"},
                new String[] {"--logging.structured.format.console="},
                new String[] {"--spring.application.json={\"logging\":{\"structured\":{\"format\":{\"file\":\"gelf\"}}}}"},
                new String[] {"--logging.config=classpath:custom-logback.xml"})) {
            assertThatThrownBy(() -> run(args))
                    .as(String.join(" ", args))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("backlog #0-94")
                    .hasMessageContaining(StructuredLoggingGuard.PLAIN_TEXT_PROPERTY);
        }
    }

    @Test
    @DisplayName("switched to plain text on purpose (a developer's application-local.yml: empty format and the "
            + "plain-text switch), in the MDC pattern")
    void plainTextWhenSwitchedOff(CapturedOutput output) {
        try (ConfigurableApplicationContext ignored = run(
                "--spring.application.name=test-service", "--spring.config.name=structured-logging-plain")) {
            MDC.put("tenantId", "acme-corp");
            MDC.put("requestId", "req-3");
            log.info("plain line");
        }

        final List<String> lines = output.getOut().lines().filter(l -> l.contains("plain line")).toList();
        assertThat(lines).singleElement().satisfies(l -> assertThat(l)
                .contains("[acme-corp] [req-3] [no-user] INFO")
                .doesNotStartWith("{"));
    }

    private static ConfigurableApplicationContext run(String... args) {
        return new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(args);
    }

    private JsonNode onlyLineContaining(CapturedOutput output, String text) throws Exception {
        final List<String> lines = output.getOut().lines().filter(l -> l.contains(text)).toList();
        assertThat(lines).hasSize(1);
        return json.readTree(lines.get(0));
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }
}
