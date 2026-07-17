package com.incidentplatform.oncall.dto;

import com.incidentplatform.oncall.validation.StartBeforeEnd;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

@StartBeforeEnd
public record CreateOncallScheduleRequest(

        /**
         * Team this schedule entry belongs to.
         * When provided, the entry is used for team-based on-call routing.
         * Null = tenant-wide schedule (no team filtering).
         */
        UUID teamId,

        @NotBlank(message = "userId is required")
        String userId,

        @NotBlank(message = "userName is required")
        String userName,

        @Email(message = "Valid email is required")
        @NotBlank(message = "email is required")
        String email,

        String phone,

        String slackUserId,

        @NotBlank(message = "role is required")
        @Pattern(
                regexp = "^(PRIMARY|SECONDARY|MANAGER)$",
                message = "role must be PRIMARY, SECONDARY or MANAGER"
        )
        String role,

        @NotNull(message = "startsAt is required")
        Instant startsAt,

        @NotNull(message = "endsAt is required")
        Instant endsAt,

        String notes
) {}