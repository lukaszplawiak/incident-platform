package com.incidentplatform.oncall.dto;

public record SlackUserLookupResponse(
        String userId,
        String userName,
        String tenantId,
        String slackUserId
) {}