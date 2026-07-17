package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/integrations}.
 */
public record CreateIntegrationRequest(

        @NotBlank(message = "name must not be blank")
        @Size(min = 2, max = 100, message = "name must be between 2 and 100 characters")
        String name,

        /**
         * Alert source — must match a registered AlertNormalizer.
         * Valid values: "prometheus", "wazuh", "generic".
         */
        @NotBlank(message = "source must not be blank")
        @Pattern(regexp = "^(prometheus|wazuh|generic)$",
                message = "source must be one of: prometheus, wazuh, generic")
        String source,

        /**
         * Team that receives alerts from this integration.
         * When null, escalation skips on-call routing (fail-loudly).
         */
        UUID teamId,

        @Size(max = 1000, message = "description must not exceed 1000 characters")
        String description

) {}