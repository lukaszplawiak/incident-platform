package com.incidentplatform.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the AlertManager ingestor token refresher.
 *
 * <p>Replaces two {@code @Value} injections in
 * {@link com.incidentplatform.ingestion.alertmanager.AlertManagerTokenRefresher}:
 * {@code alertmanager.token-file-path} and {@code alertmanager.token-refresh-enabled}.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * alertmanager:
 *   token-file-path: ${ALERTMANAGER_TOKEN_FILE_PATH:}
 *   token-refresh-enabled: ${ALERTMANAGER_TOKEN_REFRESH_ENABLED:true}
 * }</pre>
 */
@ConfigurationProperties(prefix = "alertmanager")
@Validated
public record AlertManagerProperties(

        /**
         * Path to the file where the service JWT is written so AlertManager
         * can read it. Empty string means file writing is skipped.
         */
        String tokenFilePath,

        /**
         * Whether the token refresh scheduler is active.
         * Set to false in environments where AlertManager is not used.
         */
        boolean tokenRefreshEnabled

) {}