package com.incidentplatform.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response from {@code POST /api/v1/users}.
 *
 * <h2>inviteToken field — temporary</h2>
 * The invite token is returned in the response body until email infrastructure
 * is implemented (see backlog: self-service password recovery / invite email).
 * Once email sending is available, this field will be removed and the token
 * will be sent directly to the user's email address instead.
 *
 * <p>The admin is responsible for securely forwarding this token to the new
 * user (e.g. via Slack DM or a secure channel). The token is single-use and
 * expires after {@code 72 hours}.
 */
public record CreateUserResponse(
        UUID userId,
        String tenantId,
        String email,
        List<String> roles,
        boolean active,
        Instant createdAt,

        /**
         * Raw invite token — share securely with the new user.
         * They will use it at {@code POST /api/v1/auth/accept-invite}
         * to set their password.
         * <p>This field will be removed once email sending is implemented.
         */
        String inviteToken,
        Instant inviteExpiresAt
) {}