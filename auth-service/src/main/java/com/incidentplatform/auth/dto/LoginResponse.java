package com.incidentplatform.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response from {@code POST /api/v1/auth/login}.
 *
 * <h2>Two-token model</h2>
 * <ul>
 *   <li>{@link #accessToken} — short-lived JWT (default PT15M) for API calls.
 *       Include in every request: {@code Authorization: Bearer <accessToken>}</li>
 *   <li>{@link #refreshToken} — long-lived opaque token (default P30D) for
 *       obtaining new access tokens without re-entering credentials.
 *       Send only to {@code POST /api/v1/auth/refresh}.
 *       Store securely: httpOnly cookie (web) or SecureStorage (mobile).</li>
 * </ul>
 *
 * <h2>Client responsibilities</h2>
 * The client should proactively refresh the access token before it expires
 * (e.g. when less than 2 minutes remain) to avoid request failures.
 * The {@link #accessExpiresAt} field provides the absolute expiry time.
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        UUID userId,
        String tenantId,
        String email,
        List<String> roles,
        Instant accessExpiresAt,
        Instant refreshExpiresAt
) {}