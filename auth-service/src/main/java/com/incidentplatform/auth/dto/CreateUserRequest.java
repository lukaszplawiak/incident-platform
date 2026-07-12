package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record CreateUserRequest(

        @NotBlank(message = "email is required")
        @Email(message = "must be a valid email address")
        String email,

        @NotEmpty(message = "at least one role is required")
        List<@Pattern(
                regexp = "^ROLE_[A-Z_]+$",
                message = "role must be a valid Role enum value (e.g. ROLE_ADMIN, ROLE_RESPONDER)"
        ) String> roles

) {}