package com.incidentplatform.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides cached JWT service tokens for inter-service authentication,
 * one token per tenant.
 *
 * <h2>Fixed (backlog #0-11): tokens are per tenant and per target service</h2>
 * {@code getToken()} used to return one token for every call, with a
 * hard-coded {@code "system"} tenant, and {@link JwtAuthFilter} rejected
 * it anyway. The receiving service filters every query by the tenant in
 * the token, so a call made on behalf of tenant {@code acme} needs a token
 * whose signed {@code tenantId} claim is {@code acme}; and it is accepted
 * only by the service named in its {@code aud} claim (see
 * {@link ServiceNames}). {@link #getToken(String, String)} therefore mints
 * and caches one token per (tenant, audience); the no-argument method is
 * gone so that a caller cannot forget to say which tenant it acts for or
 * which service it calls (the compiler points at every call site).
 *
 * <h2>Thread safety</h2>
 * Tokens are cached in a {@link ConcurrentHashMap} holding immutable
 * {@link TokenHolder} records. The record makes the (token, expiresAt)
 * pair an atomic unit — a reader always sees either both old or both new
 * values, never a torn pair, which two separate {@code volatile} fields
 * could produce. The {@code synchronized} {@link #refreshAndGet} with an
 * internal double-check ensures one token is generated per tenant per
 * refresh cycle instead of one per thread that raced past the first check.
 *
 * <p>The fast path ({@link #getToken(String)} reading a non-expired
 * holder) is lock-free — only the infrequent refresh path takes the
 * monitor. Minting is a single HMAC signature, so serialising refreshes
 * across tenants costs nothing measurable.
 *
 * <h2>Bounded cache</h2>
 * The tenant id reaches this class from message payloads (Kafka
 * consumers), so the cache must not grow without limit if a bad producer
 * emits many distinct ids (the id is also validated, see
 * {@link JwtUtils#requireValidServiceTenantId}). At {@value #MAX_CACHED_TENANTS} entries expired
 * tokens are evicted first; if the cache is still full, the token is
 * returned <em>uncached</em> — correct, just re-minted on the next call.
 */
@Component
public class ServiceTokenProvider {

    private static final Logger log =
            LoggerFactory.getLogger(ServiceTokenProvider.class);

    private static final long REFRESH_BUFFER_SECONDS = 300L;

    /** Upper bound on cached tenants — see the class Javadoc. */
    static final int MAX_CACHED_TENANTS = 1_000;

    /** One cached token is valid for one tenant and one target service. */
    private record CacheKey(String tenantId, String audience) { }

    /**
     * Immutable holder for a token and its expiry.
     * Stored as a single atomic unit to prevent torn reads between
     * the token string and its associated expiry timestamp.
     */
    private record TokenHolder(String token, Instant expiresAt) {

        boolean isValid() {
            return Instant.now()
                    .isBefore(expiresAt.minusSeconds(REFRESH_BUFFER_SECONDS));
        }
    }

    private final JwtUtils jwtUtils;
    private final String serviceName;

    private final ConcurrentHashMap<CacheKey, TokenHolder> tokensByTenant =
            new ConcurrentHashMap<>();

    public ServiceTokenProvider(
            JwtUtils jwtUtils,
            @Value("${spring.application.name:unknown-service}") String serviceName) {
        this.jwtUtils    = jwtUtils;
        this.serviceName = serviceName;
    }

    /**
     * Returns a valid service JWT for the given tenant and target service,
     * refreshing it if necessary.
     *
     * <p>Fast path (token valid): lock-free read from the cache.
     * Slow path (missing or expiring token): delegates to
     * {@link #refreshAndGet} which is {@code synchronized} to prevent
     * concurrent token generation.
     *
     * @param tenantId tenant the call acts for; becomes the signed
     *                 {@code tenantId} claim of the token
     * @param audience the service being called, one of {@link ServiceNames};
     *                 becomes the {@code aud} claim
     * @throws IllegalArgumentException if either is blank, or if
     *         {@code tenantId} has whitespace/control characters or is too long
     */
    public String getToken(String tenantId, String audience) {
        JwtUtils.requireValidServiceTenantId(tenantId);
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException(
                    "audience must not be blank — a service token is valid " +
                            "for one target service");
        }

        final CacheKey key = new CacheKey(tenantId, audience);
        final TokenHolder current = tokensByTenant.get(key);
        if (current != null && current.isValid()) {
            return current.token();
        }
        return refreshAndGet(key);
    }

    /**
     * Refreshes the token under a monitor lock.
     *
     * <p>Double-checks validity after acquiring the lock so that only the
     * first thread actually generates a new token — subsequent threads that
     * were waiting at the monitor entry will find a valid token and return
     * immediately.
     */
    private synchronized String refreshAndGet(CacheKey key) {
        final TokenHolder current = tokensByTenant.get(key);
        if (current != null && current.isValid()) {
            return current.token();
        }

        final String token = jwtUtils.generateServiceToken(
                serviceName, key.tenantId(), key.audience());
        final Instant expiresAt = Instant.now()
                .plus(jwtUtils.getServiceTokenTtl());

        if (tokensByTenant.size() >= MAX_CACHED_TENANTS
                && !tokensByTenant.containsKey(key)) {
            tokensByTenant.values().removeIf(holder -> !holder.isValid());
        }

        if (tokensByTenant.size() < MAX_CACHED_TENANTS
                || tokensByTenant.containsKey(key)) {
            tokensByTenant.put(key, new TokenHolder(token, expiresAt));
        } else {
            log.warn("Service token cache full ({} entries) — returning an " +
                            "uncached token: service={}, audience={}, tenantId={}",
                    MAX_CACHED_TENANTS, serviceName, key.audience(), key.tenantId());
        }

        log.debug("Service token refreshed: service={}, audience={}, " +
                        "tenantId={}, expiresAt={}",
                serviceName, key.audience(), key.tenantId(), expiresAt);

        return token;
    }
}
