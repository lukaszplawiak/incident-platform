package com.incidentplatform.ingestion_normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.SourceType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class GenericNormalizer extends BaseNormalizer {

    private static final String SOURCE = "generic";

    private static final Set<String> VALID_SEVERITIES =
            Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    @Override
    public NormalizationResult normalize(JsonNode rawPayload, String tenantId) {
        log.debug("Normalizing generic alert for tenant: {}", tenantId);

        final String title = getTextOrThrow(rawPayload, "title");
        final String severity = validateSeverity(
                getTextOrThrow(rawPayload, "severity").toUpperCase()
        );
        final String source = getText(rawPayload, "source", SOURCE);
        final SourceType sourceType = parseSourceType(
                getText(rawPayload, "sourceType", "OPS")
        );
        final String description = getText(rawPayload, "description", null);
        final Instant firedAt = parseInstant(getText(rawPayload, "firedAt", null));
        final Map<String, String> metadata = extractMetadata(rawPayload);

        log.info("Generic alert normalized: source={}, severity={}, tenant={}",
                source, severity, tenantId);

        return NormalizationResult.firingOnly(List.of(new UnifiedAlertDto(
                UUID.randomUUID(),
                tenantId,
                source,
                sourceType,
                severity,
                title,
                description,
                firedAt,
                metadata
        )));
    }

    @Override
    public String getSourceName() {
        return SOURCE;
    }

    private String validateSeverity(String severity) {
        if (!VALID_SEVERITIES.contains(severity)) {
            throw new NormalizationException(SOURCE,
                    String.format("Invalid severity '%s'. Must be one of: %s",
                            severity, VALID_SEVERITIES));
        }
        return severity;
    }

    private SourceType parseSourceType(String sourceType) {
        try {
            return SourceType.valueOf(sourceType.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown sourceType '{}', defaulting to OPS", sourceType);
            return SourceType.OPS;
        }
    }

    private Map<String, String> extractMetadata(JsonNode payload) {
        final JsonNode metadataNode = payload.get("metadata");
        if (metadataNode == null || !metadataNode.isObject()) return Map.of();

        final Map<String, String> metadata = new HashMap<>();

        metadataNode.properties().forEach(entry -> {
            if (metadata.size() < 20) {
                final String value = entry.getValue().asText();
                metadata.put(
                        entry.getKey(),
                        value.length() <= 500 ? value : value.substring(0, 500)
                );
            }
        });

        return metadata;
    }
}