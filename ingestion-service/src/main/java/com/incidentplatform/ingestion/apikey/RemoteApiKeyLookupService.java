package com.incidentplatform.ingestion.apikey;

import com.incidentplatform.ingestion.api.ClientIpResolver;
import com.incidentplatform.ingestion.ratelimit.AuthFailureRateLimiter;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.ApiKeyAuthFilter.ApiKeyLookupResult;
import com.incidentplatform.shared.security.ApiKeyHashing;
import com.incidentplatform.shared.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * ingestion-service's {@link ApiKeyAuthFilter.ApiKeyLookupService}: Integration
 * API keys, checked by introspection into auth-service (backlog #0-16).
 * Replaces the shared no-op, under which every key got 401 here and the
 * {@code alerts:ingest} scope branch of {@code AlertIngestionController} could
 * never be reached.
 *
 * <h2>Order of checks</h2>
 * <ol>
 *   <li>Hash the key ({@link ApiKeyHashing}, the same function auth-service
 *       stores keys by).</li>
 *   <li>A key in the positive cache is authenticated at once — never throttled.</li>
 *   <li>A client IP with too many failed attempts gets {@code Throttled} (429),
 *       without a call to auth-service.</li>
 *   <li>Otherwise introspect (cached). Inactive → the failure is counted and the
 *       answer is {@code Invalid} (401). auth-service unreachable →
 *       {@code Unavailable} (503, so the sender retries), not counted as a
 *       failure: the client did nothing wrong.</li>
 * </ol>
 *
 * <h2>Principal</h2>
 * A {@link UserPrincipal} flagged as an API key, carrying the key's scopes and
 * no roles: least privilege, so the key reaches only what
 * {@code hasScope(ALERTS_INGEST)} allows. Its team goes in {@code teamIds}, which
 * the controller reads to route the alert. {@code userId} is the key id — there
 * is no user behind an Integration key.
 */
@Service
public class RemoteApiKeyLookupService implements ApiKeyAuthFilter.ApiKeyLookupService {

    /** Retry-After when auth-service could not be asked. */
    static final Duration UNAVAILABLE_RETRY_AFTER = Duration.ofSeconds(30);

    private final CachingApiKeyIntrospectionClient introspectionClient;
    private final AuthFailureRateLimiter authFailureRateLimiter;
    private final ClientIpResolver clientIpResolver;

    public RemoteApiKeyLookupService(CachingApiKeyIntrospectionClient introspectionClient,
                                     AuthFailureRateLimiter authFailureRateLimiter,
                                     ClientIpResolver clientIpResolver) {
        this.introspectionClient = introspectionClient;
        this.authFailureRateLimiter = authFailureRateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public ApiKeyLookupResult lookup(String rawKey, HttpServletRequest request) {
        final String keyHash = ApiKeyHashing.sha256Hex(rawKey);

        final Optional<IntrospectedApiKey> cached = introspectionClient.findCached(keyHash);
        if (cached.isPresent()) {
            return authenticated(cached.get());
        }

        final String clientIp = clientIpResolver.resolve(request);
        if (authFailureRateLimiter.isBlocked(clientIp)) {
            return new ApiKeyLookupResult.Throttled(authFailureRateLimiter.retryAfter());
        }

        final Optional<IntrospectedApiKey> key;
        try {
            key = introspectionClient.introspect(keyHash);
        } catch (ApiKeyIntrospectionUnavailableException e) {
            return new ApiKeyLookupResult.Unavailable(UNAVAILABLE_RETRY_AFTER);
        }
        if (key.isEmpty()) {
            authFailureRateLimiter.recordFailure(clientIp);
            return new ApiKeyLookupResult.Invalid();
        }
        return authenticated(key.get());
    }

    private static ApiKeyLookupResult authenticated(IntrospectedApiKey key) {
        return new ApiKeyLookupResult.Authenticated(new UserPrincipal(
                key.keyId(),
                key.tenantId(),
                "api-key:" + key.keyId(),
                List.of(),
                key.teamId() == null ? List.of() : List.of(key.teamId()),
                List.of(),
                true,
                key.scopes(),
                null));
    }
}
