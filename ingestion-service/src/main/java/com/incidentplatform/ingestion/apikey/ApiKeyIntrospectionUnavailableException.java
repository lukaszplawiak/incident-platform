package com.incidentplatform.ingestion.apikey;

/**
 * auth-service could not say whether an API key is active (unreachable, 5xx,
 * open circuit, a rejected introspection token). Backlog #0-16: this is
 * answered with 503 and {@code Retry-After}, never 401 — an alert sender such
 * as Alertmanager drops a 4xx but retries a 5xx, so treating "don't know" as
 * "invalid" would silently lose the alert during an auth-service outage.
 */
public class ApiKeyIntrospectionUnavailableException extends RuntimeException {

    public ApiKeyIntrospectionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
