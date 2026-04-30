package com.incidentplatform.ingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.SourceType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class GenericNormalizer extends BaseNormalizer {

    private static final String SOURCE = "generic";

    @Override
    public NormalizationResult normalize(JsonNode rawPayload, String tenantId) {
        log.debug("Normalizing generic alert for tenant: {}", tenantId);

        final String title = getTextOrThrow(rawPayload, "title");

        final Severity severity = parseSeverity(
                getTextOrThrow(rawPayload, "severity")
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

        final String fingerprint = buildFingerprint(title);

        return NormalizationResult.firingOnly(List.of(new UnifiedAlertDto(
                UUID.randomUUID(),
                tenantId,
                source,
                sourceType,
                severity,
                title,
                description,
                firedAt,
                fingerprint,
                metadata
        )));
    }

    @Override
    public String getSourceName() {
        return SOURCE;
    }

    private Severity parseSeverity(String rawSeverity) {
        try {
            return Severity.fromString(rawSeverity);
        } catch (IllegalArgumentException e) {
            throw new NormalizationException(SOURCE,
                    String.format("Invalid severity '%s'. Allowed values: %s",
                            rawSeverity,
                            java.util.Arrays.toString(Severity.values())));
        }
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