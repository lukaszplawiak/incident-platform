package com.incidentplatform.escalation.dto;

import java.util.UUID;

/**
 * On-call user details returned by oncall-service.
 * Maps to {@code CurrentOncallResponse} from oncall-service.
 */
public record OncallUserDto(
        String userId,
        String userName,
        String email,
        UUID teamId,
        String phone,
        String slackUserId,
        String role
) {}