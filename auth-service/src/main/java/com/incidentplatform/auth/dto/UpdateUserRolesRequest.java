package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record UpdateUserRolesRequest(

        @NotEmpty(message = "at least one role is required")
        List<@Pattern(
                regexp = "^ROLE_[A-Z_]+$",
                message = "role must be a valid Role enum value (e.g. ROLE_ADMIN, ROLE_RESPONDER)"
        ) String> roles

) {}