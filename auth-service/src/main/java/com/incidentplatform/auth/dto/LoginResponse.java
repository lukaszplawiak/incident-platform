package com.incidentplatform.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LoginResponse(
        String token,
        UUID userId,
        String tenantId,
        String email,
        List<String> roles,
        Instant expiresAt
) {}