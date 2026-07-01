package com.incidentplatform.auth.api;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Shared {@code @SpringBootApplication} for all {@code @WebMvcTest} classes
 * in the {@code auth.api} package.
 *
 * <h2>Why a shared class</h2>
 * Spring Boot's {@code @WebMvcTest} looks for a {@code @SpringBootConfiguration}
 * in the package hierarchy. Having one {@code TestApplication} per test class
 * causes {@code IllegalStateException: Found multiple @SpringBootConfiguration}
 * when tests run together — Spring finds all inner {@code TestApplication}
 * classes in the same package and cannot decide which to use.
 *
 * <p>A single top-level {@code TestApplication} in the package resolves the
 * conflict: every {@code @WebMvcTest} in this package shares the same
 * bootstrap configuration.
 *
 * <h2>scanBasePackages</h2>
 * Includes only the packages needed for the web and security layers —
 * excludes JPA, Flyway, and Kafka to keep the web slice fast.
 */
@SpringBootApplication(scanBasePackages = {
        "com.incidentplatform.auth.api",
        "com.incidentplatform.auth.config",
        "com.incidentplatform.shared.security",
        "com.incidentplatform.shared.exception",
        "com.incidentplatform.shared.observability"
})
public class AuthApiTestApplication {
}