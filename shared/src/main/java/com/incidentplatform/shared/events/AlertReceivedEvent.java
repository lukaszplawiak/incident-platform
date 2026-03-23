package com.incidentplatform.shared.events;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AlertReceivedEvent(

        @NotNull UUID eventId,

        @NotBlank String tenantId,

        @NotBlank String source,

        @NotNull SourceType sourceType,

        @NotBlank String severity,

        @NotBlank String title,

        String description,

        @NotNull Instant firedAt,

        @NotNull Instant receivedAt,

        Map<String, String> metadata

) implements AlertEvent {

    public AlertReceivedEvent {
        if (eventId == null) {
            eventId = UUID.randomUUID();
        }
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (title != null) {
            title = title.trim();
        }
    }

    public static AlertReceivedEvent from(
            String tenantId,
            String source,
            SourceType sourceType,
            String severity,
            String title,
            String description,
            Instant firedAt,
            Map<String, String> metadata) {

        return new AlertReceivedEvent(
                UUID.randomUUID(),
                tenantId,
                source,
                sourceType,
                severity,
                title,
                description,
                firedAt,
                Instant.now(),
                metadata
        );
    }
}