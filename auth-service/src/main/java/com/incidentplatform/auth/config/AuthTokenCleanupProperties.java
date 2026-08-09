package com.incidentplatform.auth.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the expired/used {@code auth_tokens} cleanup scheduler.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * auth:
 *   token-cleanup:
 *     interval-ms: ${AUTH_TOKEN_CLEANUP_INTERVAL_MS:86400000}
 * }</pre>
 */
@ConfigurationProperties(prefix = "auth.token-cleanup")
@Validated
public record AuthTokenCleanupProperties(

        /**
         * Fixed delay between cleanup runs (milliseconds). Kept as
         * {@code long} for use in {@code @Scheduled(fixedDelayString)} —
         * same reasoning as {@code InviteEmailProperties.schedulerIntervalMs}.
         * Default: 86 400 000 ms (24 hours) — this is pure housekeeping
         * with no latency requirement (expired/used tokens are already
         * correctly rejected by every query that matters; this only
         * controls how long the now-useless rows stick around).
         */
        @Positive(message = "auth.token-cleanup.interval-ms must be positive")
        long intervalMs

) {}