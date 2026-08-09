package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.config.AuthTokenCleanupProperties;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Cleans up expired and used rows from {@code auth_tokens} — invite,
 * password-reset, MFA session, and MFA setup-required tokens all live in
 * this one table.
 *
 * <h2>Fixed: {@code deleteExpiredAndUsed} was defined but never called</h2>
 * {@link AuthTokenRepository#deleteExpiredAndUsed} has existed since this
 * repository was written, but nothing in the codebase ever invoked it —
 * {@code auth_tokens} grew without bound. Not a functional bug (every
 * query that matters, e.g. {@code findValidByHashAndType}, already
 * correctly excludes expired/used rows), but a real, slowly worsening
 * operational one — this table accumulates a row for every login,
 * password reset, and MFA verification, forever, with nothing to prune
 * the now-useless ones.
 *
 * <p>{@code deleteExpiredAndUsed}'s own query deletes a row if it's been
 * used ({@code usedAt IS NOT NULL}, regardless of when) OR its
 * {@code expiresAt} is before the given threshold — passing
 * {@code Instant.now()} as that threshold cleans up everything already
 * naturally expired or consumed, with no extra grace-period configuration
 * needed.
 *
 * <h2>ShedLock</h2>
 * Prevents duplicate/overlapping cleanup runs across multiple
 * auth-service instances — same pattern as {@code AuthEmailScheduler}.
 */
@Component
@EnableConfigurationProperties(AuthTokenCleanupProperties.class)
public class AuthTokenCleanupScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(AuthTokenCleanupScheduler.class);

    private final AuthTokenRepository tokenRepository;

    public AuthTokenCleanupScheduler(AuthTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Scheduled(
            fixedDelayString = "${auth.token-cleanup.interval-ms:86400000}",
            initialDelayString = "60000"
    )
    @SchedulerLock(
            name = "auth-service:cleanupExpiredAndUsedTokens",
            lockAtMostFor = "4m",
            lockAtLeastFor = "10s"
    )
    @Transactional
    public void cleanupExpiredAndUsedTokens() {
        final int deleted = tokenRepository.deleteExpiredAndUsed(Instant.now());

        if (deleted > 0) {
            log.info("Auth token cleanup: deleted {} expired/used token(s)", deleted);
        } else {
            log.debug("Auth token cleanup: nothing to delete");
        }
    }
}