package com.incidentplatform.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response from {@code POST /api/v1/auth/refresh}.
 *
 * <p>Contains a new access token and a new refresh token (rotation).
 * The old refresh token is invalidated immediately — the client must
 * replace it with {@link #refreshToken} before the next refresh call.
 */
public record RefreshResponse(
        String accessToken,
        String refreshToken,
        UUID userId,
        String tenantId,
        String email,
        List<String> roles,
        Instant accessExpiresAt,
        Instant refreshExpiresAt
) {}