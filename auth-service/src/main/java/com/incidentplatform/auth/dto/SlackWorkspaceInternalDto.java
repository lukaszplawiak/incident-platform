package com.incidentplatform.auth.dto;

import java.util.UUID;

/**
 * Response of {@code GET /api/v1/internal/slack-workspace} (backlog #0-21),
 * accepted only from a service token whose {@code aud} names auth-service
 * (backlog #0-30) — never exposed to the admin UI. Carries the decrypted
 * bot token: this is the one path in the system where it leaves
 * auth-service, over HTTPS to a caller that just proved it holds a service
 * token minted specifically to reach this endpoint.
 */
public record SlackWorkspaceInternalDto(
        String botToken,
        String defaultChannel,
        boolean broadcastEnabled,
        UUID teamId
) {}
