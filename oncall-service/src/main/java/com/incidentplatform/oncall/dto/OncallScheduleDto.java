package com.incidentplatform.oncall.dto;

import com.incidentplatform.oncall.domain.OncallSchedule;

import java.time.Instant;
import java.util.UUID;

/**
 * teamId was added after CreateOncallScheduleRequest and
 * CurrentOncallResponse already exposed it — a schedule created with a
 * team attached would not show that team anywhere when the entry was
 * later read back via GET /schedules, since this DTO silently dropped
 * the field. Field order mirrors OncallSchedule's declaration order
 * (id, tenantId, teamId, userId, ...).
 */
public record OncallScheduleDto(
        UUID id,
        String tenantId,
        UUID teamId,
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
                schedule.getTeamId(),
                schedule.getUserId(),
                schedule.getUserName(),
                schedule.getEmail(),
                schedule.getPhone(),
                schedule.getSlackUserId(),
                schedule.getRole().name(),
                schedule.getStartsAt(),
                schedule.getEndsAt(),
                schedule.getNotes(),
                schedule.getCreatedAt()
        );
    }
}