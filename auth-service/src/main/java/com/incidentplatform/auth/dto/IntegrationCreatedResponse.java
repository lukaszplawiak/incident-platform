package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.Integration;

import java.time.Instant;
import java.util.UUID;

/**
 * Response from {@code POST /api/v1/integrations}.
 *
 * <p>Contains the raw API key in {@link #apiKey}. This is the ONLY time
 * the raw key is returned — it cannot be retrieved again. The client must
 * configure the external monitoring system with this key immediately.
 *
 * <h2>Alertmanager configuration example</h2>
 * <pre>
 * receivers:
 *   - name: incident-platform
 *     webhook_configs:
 *       - url: 'https://api.incident-platform.com/api/v1/alerts/prometheus'
 *         http_config:
 *           authorization:
 *             type: ApiKey
 *             credentials: '{apiKey}'
 * </pre>
 */
public record IntegrationCreatedResponse(
        UUID id,
        String name,
        String source,
        UUID teamId,
        String teamName,

        /**
         * Raw API key for the external system — shown ONCE, never stored.
         * Format: {@code ipl_<prefix8>.<random32>}
         */
        String apiKey,

        String description,
        Instant createdAt,
        String message
) {
    public static IntegrationCreatedResponse from(Integration integration,
                                                  String rawApiKey) {
        return new IntegrationCreatedResponse(
                integration.getId(),
                integration.getName(),
                integration.getSource(),
                integration.getTeam() != null
                        ? integration.getTeam().getId() : null,
                integration.getTeam() != null
                        ? integration.getTeam().getName() : null,
                rawApiKey,
                integration.getDescription(),
                integration.getCreatedAt(),
                "Configure your monitoring system with this API key. " +
                        "It will not be shown again."
        );
    }
}