package com.incidentplatform.ingestion.apikey;

import java.util.Optional;

/**
 * Asks auth-service which tenant an API key belongs to (backlog #0-16, the
 * #0-30 pattern: a narrow HTTP pull of data auth-service owns).
 *
 * <p>Two beans implement it: {@link ApiKeyIntrospectionClientImpl} does the
 * HTTP call behind Resilience4j, and {@link CachingApiKeyIntrospectionClient}
 * ({@code @Primary}) caches its answers — the same split as
 * notification-service's Slack workspace client, for the same reason (the
 * cache must call the Resilience4j proxy from another bean, or the annotations
 * never run).
 */
public interface ApiKeyIntrospectionClient {

    /**
     * @param keyHash lowercase hex SHA-256 of the raw key; the raw key is never sent
     * @return the key if it is active, empty if it is unknown, revoked or expired
     * @throws ApiKeyIntrospectionUnavailableException if auth-service could not
     *         answer — "unknown", which must never be read as "not active"
     */
    Optional<IntrospectedApiKey> introspect(String keyHash);
}
