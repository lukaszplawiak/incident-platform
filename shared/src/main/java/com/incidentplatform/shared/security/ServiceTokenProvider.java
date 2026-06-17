package com.incidentplatform.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides cached JWT service tokens for inter-service authentication.
 *
 * <h2>Thread safety</h2>
 * Tokens are cached in an {@link AtomicReference} holding an immutable
 * {@link TokenHolder} record. This eliminates two thread-safety issues
 * present in a naive two-field {@code volatile} approach:
 *
 * <ul>
 *   <li><b>Torn read</b> — two separate {@code volatile} fields
 *       ({@code cachedToken} and {@code tokenExpiresAt}) can be seen in an
 *       inconsistent state by a reader thread between the two writes.
 *       {@code AtomicReference<TokenHolder>} makes the (token, expiresAt) pair
 *       an atomic unit — a reader always sees either both old or both new values.
 *   <li><b>Multiple refresh on cold start</b> — multiple threads can pass
 *       the initial null check before any token is generated. The
 *       {@code synchronized} {@link #refreshAndGet()} method with an internal
 *       double-check ensures only one token is ever generated per refresh cycle.
 * </ul>
 *
 * <p>The fast path ({@link #getToken()} reading a non-null, non-expired holder)
 * is lock-free — only the infrequent refresh path acquires the monitor.
 */
@Component
public class ServiceTokenProvider {

    private static final Logger log =
            LoggerFactory.getLogger(ServiceTokenProvider.class);

    private static final long REFRESH_BUFFER_SECONDS = 300L;

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
    private final long serviceExpirationMs;

    private final AtomicReference<TokenHolder> tokenRef = new AtomicReference<>();

    public ServiceTokenProvider(
            JwtUtils jwtUtils,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${jwt.service-expiration-ms:2592000000}") long serviceExpirationMs) {
        this.jwtUtils = jwtUtils;
        this.serviceName = serviceName;
        this.serviceExpirationMs = serviceExpirationMs;
    }

    /**
     * Returns a valid service JWT token, refreshing it if necessary.
     *
     * <p>Fast path (token valid): lock-free read from {@link AtomicReference}.
     * Slow path (missing or expiring token): delegates to {@link #refreshAndGet()}
     * which is {@code synchronized} to prevent concurrent token generation.
     */
    public String getToken() {
        final TokenHolder current = tokenRef.get();
        if (current != null && current.isValid()) {
            return current.token();
        }
        return refreshAndGet();
    }

    /**
     * Refreshes the cached token under a monitor lock.
     *
     * <p>Double-checks validity after acquiring the lock so that only the
     * first thread actually generates a new token — subsequent threads that
     * were waiting at the monitor entry will find a valid token and return
     * immediately.
     */
    private synchronized String refreshAndGet() {
        final TokenHolder current = tokenRef.get();
        if (current != null && current.isValid()) {
            return current.token();
        }

        final String token = jwtUtils.generateServiceToken(serviceName);
        final Instant expiresAt = Instant.now().plusMillis(serviceExpirationMs);
        final TokenHolder fresh = new TokenHolder(token, expiresAt);

        tokenRef.set(fresh);

        log.debug("Service token refreshed: service={}, expiresAt={}",
                serviceName, expiresAt);

        return token;
    }
}