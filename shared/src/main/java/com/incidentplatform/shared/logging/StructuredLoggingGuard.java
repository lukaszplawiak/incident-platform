package com.incidentplatform.shared.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Refuses to start a service whose logs would not be JSON in ECS (backlog
 * #0-94, step 1), unless plain text is switched on on purpose.
 *
 * <h2>Why fail closed</h2>
 * {@link StructuredLoggingDefaults} is a default, and anything can override a
 * default: a service's {@code application.yml} or a {@code config/} copy of
 * it, an environment variable or a ConfigMap outside this repository, {@code
 * SPRING_APPLICATION_JSON}, a custom Logback file ({@code logging.config},
 * {@code logback.configurationFile}, a {@code logback*.xml} anywhere on the
 * classpath), or a reshaped JSON object ({@code logging.structured.json.*}:
 * a field renamed or excluded, the internal MDC key let in). Any of these brings back plain text with an
 * unescaped {@code %msg}, the log injection #0-94 closed, and a check of
 * committed files can recognise only some of their spellings. This reads the
 * environment as Spring Boot will use it, whatever set it, the way the
 * platform already refuses to start without a JWT secret. Plain text needs
 * {@code platform.logging.plain-text: true}, one name with no other meaning,
 * which a developer's gitignored {@code application-local.yml} sets and CI
 * fails on in tracked files ({@code check-structured-logging.sh}, a
 * convenience: this guard is what holds). Set anywhere else, through a
 * deployment's own environment, it still works, as whoever can set it can
 * also change the image; it is then logged as a warning at every start.
 *
 * <p>It runs on {@link ApplicationEnvironmentPreparedEvent}, after every
 * environment post-processor and right after {@link LoggingApplicationListener}
 * has set up logging, so the environment it reads is the one the logs follow,
 * and its refusal is logged in that format before the application stops.
 */
public class StructuredLoggingGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    /** The one log format a service may run with. */
    public static final String FORMAT = "ecs";

    /** The developer's switch to plain text; nothing committed may set it. */
    public static final String PLAIN_TEXT_PROPERTY = "platform.logging.plain-text";

    /**
     * Logback files Spring Boot would load instead of its own configuration,
     * from anywhere on the classpath: a dependency's jar shipping one stops
     * every service too, on purpose, as its pattern would replace the JSON
     * encoder all the same.
     */
    static final List<String> LOGBACK_FILES = List.of(
            "logback-test.groovy", "logback-test.xml", "logback.groovy", "logback.xml",
            "logback-spring.groovy", "logback-spring.xml");

    private static final Logger log = LoggerFactory.getLogger(StructuredLoggingGuard.class);

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        final ConfigurableEnvironment environment = event.getEnvironment();
        if (environment.getProperty(PLAIN_TEXT_PROPERTY, Boolean.class, false)) {
            // Meant for a developer's machine; anywhere else it should stand out.
            log.warn("{} is set: this service's log format is not checked, and with an empty "
                    + "logging.structured.format.console it logs plain text, values from outside unescaped "
                    + "(backlog #0-94). Only a developer's application-local.yml should set it.", PLAIN_TEXT_PROPERTY);
            return;
        }
        final List<String> problems = problems(environment, StructuredLoggingGuard::onClasspath);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Logs must be JSON (" + FORMAT + ") with every value escaped "
                    + "(backlog #0-94), but " + String.join("; ", problems) + ". Remove the setting, or, "
                    + "on a developer's machine only, set " + PLAIN_TEXT_PROPERTY + ": true in "
                    + "application-local.yml.");
        }
    }

    /**
     * What keeps this environment from logging ECS. A configured value is not
     * quoted: it comes from outside and would be printed in whatever format the
     * logs have at that moment, possibly the plain text being refused.
     */
    static List<String> problems(ConfigurableEnvironment environment, Predicate<String> onClasspath) {
        final List<String> problems = new ArrayList<>();
        for (final String output : List.of("console", "file")) {
            final String key = "logging.structured.format." + output;
            final String format = environment.getProperty(key, "");
            if (format.isBlank()) {
                problems.add(key + " is empty");
            } else if (!FORMAT.equals(format.trim().toLowerCase(Locale.ROOT))) {
                problems.add(key + " is not " + FORMAT);
            }
        }
        problems.addAll(jsonShapeProblems(environment));
        if (!environment.getProperty("logging.config", "").isBlank()) {
            problems.add("logging.config names a Logback file of its own");
        }
        if (!environment.getProperty("logback.configurationFile", "").isBlank()) {
            problems.add("logback.configurationFile names a Logback file of its own");
        }
        for (final String file : LOGBACK_FILES) {
            if (onClasspath.test(file)) {
                problems.add(file + " is on the classpath");
            }
        }
        return problems;
    }

    /**
     * The JSON object's shape is the platform's too: what the logs are queried
     * and alerted on (a {@code tenantId} renamed or excluded would vanish from
     * every line), and the internal MDC key kept out. Only the platform's own
     * {@code logging.structured.json.exclude} may be there, with its value.
     */
    private static List<String> jsonShapeProblems(ConfigurableEnvironment environment) {
        final List<String> problems = new ArrayList<>();
        final ConfigurationPropertyName json = ConfigurationPropertyName.of("logging.structured.json");
        final ConfigurationPropertyName exclude = ConfigurationPropertyName.of("logging.structured.json.exclude");
        final Set<String> others = new TreeSet<>();
        for (final ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (source instanceof IterableConfigurationPropertySource iterable) {
                iterable.stream()
                        .filter(json::isAncestorOf)
                        .filter(name -> !name.equals(exclude))
                        .forEach(name -> others.add(name.toString()));
            }
        }
        if (!others.isEmpty()) {
            problems.add("logging.structured.json is reshaped (" + others.size() + " setting(s) of its own)");
        }
        if (!StructuredLoggingDefaults.INTERNAL_MDC_KEYS.equals(
                environment.getProperty("logging.structured.json.exclude", ""))) {
            problems.add("logging.structured.json.exclude is not the platform's");
        }
        return problems;
    }

    static boolean onClasspath(String file) {
        return new ClassPathResource(file).exists();
    }

    @Override
    public int getOrder() {
        return LoggingApplicationListener.DEFAULT_ORDER + 1;
    }
}
