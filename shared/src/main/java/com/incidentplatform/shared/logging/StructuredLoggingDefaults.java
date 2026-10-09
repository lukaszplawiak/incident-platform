package com.incidentplatform.shared.logging;

import com.incidentplatform.shared.kafka.TenantKafkaRecordInterceptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Every service logs one JSON object per line, in Elastic Common Schema
 * (backlog #0-94, step 1).
 *
 * <h2>Why</h2>
 * The services logged plain text (six with one pattern, auth-service with
 * Spring Boot's default), and no encoder escaped anything: a CR/LF in any
 * value from outside (a Kafka header, a payload field, a library's exception
 * message) forged or split lines, and the MDC keys were only positions in a
 * string. Spring Boot's own structured logging escapes every value, so log
 * injection is closed by the encoder rather than by each call site
 * remembering, and {@code tenantId}, {@code requestId}, {@code userId} and
 * {@code kafkaMessageId} become fields of the JSON object, next to ECS's
 * {@code @timestamp}, {@code log.level}, {@code log.logger}, {@code message},
 * {@code service.name} (from {@code spring.application.name}) and
 * {@code error.*}. Console and file: a deployment that adds a log file gets
 * JSON there too, not the unescaped default pattern.
 *
 * <h2>Why here, as a default</h2>
 * One setting for all seven services, the way {@code shared} holds the rest of
 * the platform's policy: a new service logs the same way without remembering
 * to. A property source can be overridden from anywhere (a service's
 * configuration, an environment variable, {@code SPRING_APPLICATION_JSON}, a
 * custom Logback file), so whether the result is still JSON is not left to
 * precedence: {@link StructuredLoggingGuard} refuses to start a service that
 * does not log ECS, unless {@code platform.logging.plain-text} switches it to
 * plain text on purpose, which only a developer's gitignored {@code
 * application-local.yml} does (README "Step 2"; CI fails on the switch in any
 * committed file). The plain text then uses {@link #PLAIN_TEXT_PATTERN}, which
 * keeps the MDC columns.
 *
 * <p>It runs after {@link ConfigDataEnvironmentPostProcessor}, so the property
 * sources of {@code application.yml} are already there when this one is added
 * after them, and before the logging system starts (Spring Boot initialises it
 * on the environment this has prepared).
 */
public class StructuredLoggingDefaults implements EnvironmentPostProcessor, Ordered {

    /** The property source's name, so a test or a diagnostic can find it. */
    public static final String PROPERTY_SOURCE_NAME = "platformStructuredLoggingDefaults";

    /**
     * MDC keys the platform keeps for its own bookkeeping, not for a reader.
     * Every other MDC key becomes a field of the JSON object, so a key put there
     * later is shipped too: {@code StructuredLoggingDefaultsTest} pins the set
     * that reaches the log.
     */
    static final String INTERNAL_MDC_KEYS = TenantKafkaRecordInterceptor.MDC_START_NANOS;

    /** The plain-text pattern for a developer who switches JSON off locally. */
    static final String PLAIN_TEXT_PATTERN = "%d{HH:mm:ss.SSS} [%X{tenantId:-no-tenant}] [%X{requestId:-no-req}] "
            + "[%X{userId:-no-user}] %-5level %logger{36} - %msg%n";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(
                "logging.structured.format.console", StructuredLoggingGuard.FORMAT,
                "logging.structured.format.file", StructuredLoggingGuard.FORMAT,
                "logging.structured.json.exclude", INTERNAL_MDC_KEYS,
                "logging.pattern.console", PLAIN_TEXT_PATTERN)));
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
