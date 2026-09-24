package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.SlackWorkspace;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe {@link SlackWorkspace} representation for the admin-facing
 * install/get endpoints. Never includes the bot token — only the internal
 * service-read endpoint ({@code GET /api/v1/internal/slack-workspace})
 * returns the decrypted token, and only to a {@code ROLE_SERVICE} caller.
 */
public record SlackWorkspaceDto(
        UUID id,
        String slackTeamId,
        UUID teamId,
        String defaultChannel,
        boolean broadcastEnabled,
        Instant installedAt,
        boolean active
) {
    public static SlackWorkspaceDto from(SlackWorkspace workspace) {
        return new SlackWorkspaceDto(
                workspace.getId(),
                workspace.getSlackTeamId(),
                workspace.getTeamId(),
                workspace.getDefaultChannel(),
                workspace.isBroadcastEnabled(),
                workspace.getInstalledAt(),
                workspace.isActive()
        );
    }
}
