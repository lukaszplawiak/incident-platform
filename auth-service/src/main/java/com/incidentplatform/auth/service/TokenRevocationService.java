package com.incidentplatform.auth.service;

import com.incidentplatform.shared.security.TokenRevocationChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Redis-backed implementation of {@link TokenRevocationChecker}.
 *
 * <h2>Why token revocation is necessary</h2>
 * JWTs are stateless — once issued, they are valid until expiry regardless
 * of server-side events. Without revocation:
 * <ul>
 *   <li>An employee who leaves the company retains access for up to 24h</li>
 *   <li>A compromised account cannot be immediately locked out</li>
 *   <li>A user has no way to invalidate their own sessions</li>
 * </ul>
 *
 * <h2>Implementation — jti revocation list in Redis</h2>
 * Key: {@code auth:revoked:{jti}} → value: {@code "1"}, TTL = remaining token lifetime.
 * TTL handles automatic cleanup — no scheduled job needed.
 * Lookup is O(1) Redis {@code hasKey} — typically < 1ms per request.
 *
 * <h2>Fail-open</h2>
 * If Redis is unavailable, {@link #isRevoked} returns {@code false}.
 * A Redis outage must not lock out all users. Security monitoring should
 * alert on Redis failures within minutes.
 */
@Service
public class TokenRevocationService implements TokenRevocationChecker {

    private static final Logger log =
            LoggerFactory.getLogger(TokenRevocationService.class);

    private static final String REVOKED_PREFIX = "auth:revoked:";

    private final StringRedisTemplate redis;

    public TokenRevocationService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Revokes a token by storing its jti in Redis until natural expiry.
     * Called on logout — the token is immediately unusable after this call.
     *
     * @param jti       the JWT ID claim — unique identifier of the token
     * @param expiresAt the token's natural expiration — sets Redis TTL so
     *                  the revocation entry cleans itself up automatically
     */
    public void revoke(String jti, Date expiresAt) {
        final Duration ttl = Duration.between(Instant.now(),
                expiresAt.toInstant());

        if (ttl.isNegative() || ttl.isZero()) {
            log.debug("Token already expired, skipping revocation: jti={}", jti);
            return;
        }

        try {
            redis.opsForValue().set(revokedKey(jti), "1", ttl);
            log.info("Token revoked: jti={}, ttl={}", jti, ttl);
        } catch (Exception e) {
            log.error("Redis unavailable during token revocation: jti={}, error={}",
                    jti, e.getMessage());
            // Not re-throwing — logout succeeds even if Redis is down.
            // Token expires naturally via JWT expiry.
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks {@code auth:revoked:{jti}} in Redis.
     * Returns {@code false} (fail-open) if Redis is unavailable.
     */
    @Override
    public boolean isRevoked(String jti) {
        try {
            // Use get() != null instead of hasKey() to avoid ClassCastException
            // in Spring Data Redis 3.x + Lettuce 6.x.
            return redis.opsForValue().get(revokedKey(jti)) != null;
        } catch (Exception e) {
            log.error("Redis unavailable during revocation check — " +
                    "failing open: jti={}, error={}", jti, e.getMessage());
            return false;
        }
    }

    private String revokedKey(String jti) {
        return REVOKED_PREFIX + jti;
    }
}