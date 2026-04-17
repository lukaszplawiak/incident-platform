package com.incidentplatform.oncall.dto;

import com.incidentplatform.oncall.domain.OncallSchedule;

import java.time.Instant;
import java.util.UUID;

public record OncallScheduleDto(
        UUID id,
        String tenantId,
        String userId,
        String userName,
        String email,
        String phone,
        String slackUserId,
        String role,
        Instant startsAt,
        Instant endsAt,
        String notes,
        Instant createdAt
) {
    public static OncallScheduleDto from(OncallSchedule schedule) {
        return new OncallScheduleDto(
                schedule.getId(),
                schedule.getTenantId(),
                schedule.getUserId(),
                schedule.getUserName(),
                schedule.getEmail(),
                schedule.getPhone(),
                schedule.getSlackUserId(),
                schedule.getRole(),
                schedule.getStartsAt(),
                schedule.getEndsAt(),
                schedule.getNotes(),
                schedule.getCreatedAt()
        );
    }
}