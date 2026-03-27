package com.incidentplatform.shared.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ResolvedAlertNotification(

        @JsonProperty("eventId")
        @NotNull
        UUID eventId,

        @JsonProperty("tenantId")
        @NotBlank
        String tenantId,

        @JsonProperty("source")
        @NotBlank
        String source,

        @JsonProperty("alertFingerprint")
        @NotBlank
        String alertFingerprint,

        @JsonProperty("resolvedAt")
        @NotNull
        Instant resolvedAt,

        @JsonProperty("receivedAt")
        @NotNull
        Instant receivedAt

) {
    public ResolvedAlertNotification {
        if (eventId == null) eventId = UUID.randomUUID();
        if (receivedAt == null) receivedAt = Instant.now();
        if (resolvedAt == null) resolvedAt = Instant.now();
    }

    public static ResolvedAlertNotification of(String tenantId,
                                               String source,
                                               String alertFingerprint,
                                               Instant resolvedAt) {
        return new ResolvedAlertNotification(
                UUID.randomUUID(),
                tenantId,
                source,
                alertFingerprint,
                resolvedAt,
                Instant.now()
        );
    }
}