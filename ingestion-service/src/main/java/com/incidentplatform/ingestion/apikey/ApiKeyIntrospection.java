package com.incidentplatform.ingestion.apikey;

/**
 * auth-service's answer about one API key (backlog #0-16), as one of three
 * cases instead of an {@code Optional}: since backlog #0-82 "not active" has
 * two meanings that the sender must hear differently.
 *
 * <ul>
 *   <li>{@link Active} — the key is valid: build the principal.</li>
 *   <li>{@link Inactive} — unknown, revoked, expired, a PERSONAL key, or a
 *       tenant suspended in full: a definite "no", 401.</li>
 *   <li>{@link Paused} — a valid TENANT key of a read-only tenant: alerts are
 *       paused, not dropped, so 503 + {@code Retry-After} — Alertmanager keeps
 *       a 5xx alert and retries it, and drops a 4xx one.</li>
 *   <li>{@link Suspended} — a valid TENANT key of a tenant suspended in full
 *       (backlog #0-82, step 2): refused (403), but not a wrong key, so not
 *       counted as a failed authentication of the sender's IP.</li>
 * </ul>
 *
 * <p>"auth-service could not answer" is none of these: it stays
 * {@link ApiKeyIntrospectionUnavailableException}.
 */
public sealed interface ApiKeyIntrospection {

    record Active(IntrospectedApiKey key) implements ApiKeyIntrospection {
        public Active {
            if (key == null) {
                throw new IllegalArgumentException("key is required");
            }
        }
    }

    record Inactive() implements ApiKeyIntrospection { }

    record Paused() implements ApiKeyIntrospection { }

    record Suspended() implements ApiKeyIntrospection { }
}
