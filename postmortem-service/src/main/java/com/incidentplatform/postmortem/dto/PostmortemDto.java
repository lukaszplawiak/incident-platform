package com.incidentplatform.postmortem.dto;

import com.incidentplatform.postmortem.domain.Postmortem;

import java.time.Instant;
import java.util.UUID;

public record PostmortemDto(
        UUID id,
        UUID incidentId,
        String tenantId,
        String incidentTitle,
        String incidentSeverity,
        Instant incidentOpenedAt,
        Instant incidentResolvedAt,
        Integer durationMinutes,
        String status,
        String content,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static PostmortemDto from(Postmortem postmortem) {
        return new PostmortemDto(
                postmortem.getId(),
                postmortem.getIncidentId(),
                postmortem.getTenantId(),
                postmortem.getIncidentTitle(),
                postmortem.getIncidentSeverity(),
                postmortem.getIncidentOpenedAt(),
                postmortem.getIncidentResolvedAt(),
                postmortem.getDurationMinutes(),
                postmortem.getStatus(),
                postmortem.getContent(),
                postmortem.getErrorMessage(),
                postmortem.getCreatedAt(),
                postmortem.getUpdatedAt()
        );
    }
}