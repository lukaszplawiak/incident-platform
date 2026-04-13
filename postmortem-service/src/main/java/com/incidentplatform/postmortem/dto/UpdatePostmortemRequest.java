package com.incidentplatform.postmortem.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdatePostmortemRequest(
        @NotBlank(message = "Content cannot be blank")
        String content
) {}