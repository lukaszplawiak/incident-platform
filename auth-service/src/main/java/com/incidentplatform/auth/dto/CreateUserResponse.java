package com.incidentplatform.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response from {@code POST /api/v1/users}.
 *
 * <h2>invite token removed</h2>
 * Previously this response contained {@code inviteToken} and
 * {@code inviteExpiresAt} — the admin had to manually forward the token to
 * the invited user via a separate channel (Slack, email, etc.).
 *
 * <p>With the Outbox Pattern, the invite token is sent directly to the
 * user's email address by {@code InviteEmailScheduler}. The token never
 * passes through the admin's HTTP client, browser devtools, or HTTP logs.
 *
 * <p>The admin can confirm the invite was sent by checking the
 * {@code status} field — {@code "INVITED"} means the outbox entry has been
 * written and the email will be dispatched within 30 seconds.
 */
public record CreateUserResponse(
        UUID userId,
        String tenantId,
        String email,
        List<String> roles,
        boolean active,
        Instant createdAt
) {}