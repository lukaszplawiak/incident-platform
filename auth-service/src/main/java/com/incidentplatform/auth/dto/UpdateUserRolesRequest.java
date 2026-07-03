package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record UpdateUserRolesRequest(

        @NotEmpty(message = "at least one role is required")
        List<@Pattern(
                regexp = "^(ROLE_ADMIN|ROLE_RESPONDER)$",
                message = "role must be ROLE_ADMIN or ROLE_RESPONDER"
        ) String> roles

) {}