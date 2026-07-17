package com.incidentplatform.oncall.dto;

import com.incidentplatform.oncall.domain.OncallSchedule;

import java.time.Instant;
import java.util.UUID;

public record CurrentOncallResponse(
        String userId,
        String userName,
        String email,
        UUID teamId,
        String phone,
        String slackUserId,
        String role,
        Instant shiftEndsAt
) {
    public static CurrentOncallResponse from(OncallSchedule schedule) {
        return new CurrentOncallResponse(
                schedule.getUserId(),
                schedule.getUserName(),
                schedule.getEmail(),
                schedule.getTeamId(),
                schedule.getPhone(),
                schedule.getSlackUserId(),
                schedule.getRole().name(),
                schedule.getEndsAt()
        );
    }

    public boolean hasDm() {
        return slackUserId != null && !slackUserId.isBlank();
    }

    public boolean hasSms() {
        return phone != null && !phone.isBlank();
    }
}