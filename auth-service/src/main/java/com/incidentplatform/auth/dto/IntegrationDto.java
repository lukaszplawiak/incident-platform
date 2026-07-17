package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.Integration;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe Integration representation for list/get endpoints.
 * Never includes the raw API key — only metadata.
 */
public record IntegrationDto(
        UUID id,
        String name,
        String source,
        UUID teamId,
        String teamName,
        String apiKeyPrefix,   // e.g. "ipl_abc1" — for identification only
        String description,
        Instant createdAt,
        boolean active
) {
    public static IntegrationDto from(Integration integration) {
        return new IntegrationDto(
                integration.getId(),
                integration.getName(),
                integration.getSource(),
                integration.getTeam() != null
                        ? integration.getTeam().getId() : null,
                integration.getTeam() != null
                        ? integration.getTeam().getName() : null,
                integration.getApiKey() != null
                        ? "ipl_" + integration.getApiKey().getKeyPrefix() : null,
                integration.getDescription(),
                integration.getCreatedAt(),
                integration.isActive()
        );
    }
}