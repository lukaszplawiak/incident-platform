package com.incidentplatform.oncall.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public record CreateOncallScheduleRequest(

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