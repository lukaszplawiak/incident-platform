package com.incidentplatform.shared.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.incidentplatform.shared.events.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UnifiedAlertDto(

        @JsonProperty("alertId")
        @NotNull
        UUID alertId,

        @JsonProperty("tenantId")
        @NotBlank
        String tenantId,

        @JsonProperty("source")
        @NotBlank
        @Size(max = 100, message = "Source name must not exceed 100 characters")
        String source,

        @JsonProperty("sourceType")
        @NotNull
        SourceType sourceType,

        @JsonProperty("severity")
        @NotBlank
        String severity,

        @JsonProperty("title")
        @NotBlank
        @Size(max = 255, message = "Title must not exceed 255 characters")
        String title,

        @JsonProperty("description")
        @Size(max = 5000, message = "Description must not exceed 5000 characters")
        String description,

        @JsonProperty("firedAt")
        @NotNull
        Instant firedAt,

        @JsonProperty("fingerprint")
        @NotBlank
        String fingerprint,

        @JsonProperty("metadata")
        Map<String, String> metadata

) {
    public UnifiedAlertDto {
        if (alertId == null) alertId = UUID.randomUUID();
        if (title != null) title = title.trim();
        if (severity != null) severity = severity.toUpperCase();
        if (source != null) source = source.toLowerCase();

        if (metadata != null) {
            metadata = Collections.unmodifiableMap(Map.copyOf(metadata));
        }
    }

    public boolean isSecurityAlert() {
        return SourceType.SECURITY.equals(sourceType);
    }

    public boolean isCritical() {
        return "CRITICAL".equals(severity);
    }
}