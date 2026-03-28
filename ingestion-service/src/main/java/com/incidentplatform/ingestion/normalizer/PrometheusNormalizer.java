package com.incidentplatform.ingestion_normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.events.SourceType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PrometheusNormalizer extends BaseNormalizer {

    private static final String SOURCE = "prometheus";
    private static final String STATUS_RESOLVED = "resolved";
    private static final String STATUS_FIRING = "firing";

    @Value("${ingestion.prometheus.max-batch-size:500}")
    private int maxBatchSize;

    @Override
    public NormalizationResult normalize(JsonNode rawPayload, String tenantId) {
        log.debug("Normalizing Prometheus batch for tenant: {}", tenantId);

        final JsonNode alerts = rawPayload.get("alerts");
        if (alerts == null || !alerts.isArray() || alerts.isEmpty()) {
            throw new NormalizationException(SOURCE,
                    "Missing or empty 'alerts' array in payload");
        }

        final int totalAlerts = alerts.size();
        final int limit = Math.min(totalAlerts, maxBatchSize);

        if (totalAlerts > maxBatchSize) {
            log.warn("Prometheus batch size {} exceeds limit {}, " +
                            "processing only first {} alerts, tenant: {}",
                    totalAlerts, maxBatchSize, maxBatchSize, tenantId);
        }

        List<UnifiedAlertDto> firingAlerts = new ArrayList<>();
        List<ResolvedAlertNotification> resolvedAlerts = new ArrayList<>();

        for (int i = 0; i < limit; i++) {
            final JsonNode alert = alerts.get(i);
            final String status = getText(alert, "status", STATUS_FIRING);

            try {
                if (STATUS_RESOLVED.equals(status)) {
                    final ResolvedAlertNotification resolved =
                            normalizeResolvedAlert(alert, tenantId, i);
                    resolvedAlerts.add(resolved);
                } else {
                    final UnifiedAlertDto firing =
                            normalizeFiringAlert(alert, tenantId, i);
                    firingAlerts.add(firing);
                }
            } catch (NormalizationException e) {
                throw e;
            }
        }

        log.info("Prometheus batch normalized: total={}, firing={}, resolved={}, " +
                        "skipped={}, tenant={}",
                totalAlerts, firingAlerts.size(), resolvedAlerts.size(),
                totalAlerts - firingAlerts.size() - resolvedAlerts.size(),
                tenantId);

        return new NormalizationResult(firingAlerts, resolvedAlerts);
    }

    @Override
    public String getSourceName() {
        return SOURCE;
    }

    private UnifiedAlertDto normalizeFiringAlert(JsonNode alert,
                                                 String tenantId,
                                                 int index) {
        final JsonNode labels = alert.get("labels");
        if (isMissingOrNotObject(labels)) {
            throw new NormalizationException(SOURCE,
                    String.format("Missing 'labels' in alert at index %d", index));
        }

        final JsonNode annotations = alert.get("annotations");
        final String alertName = getTextOrThrow(labels, "alertname");
        final String rawSeverity = getText(labels, "severity", "warning");
        final String severity = mapSeverity(rawSeverity);
        final String title = annotations != null
                ? getText(annotations, "summary", alertName)
                : alertName;
        final String description = annotations != null
                ? getText(annotations, "description", null)
                : null;
        final Instant firedAt = parseInstant(getText(alert, "startsAt", null));
        final Map<String, String> metadata = extractMetadata(labels);

        log.debug("Prometheus firing alert[{}] normalized: alertName={}, severity={}",
                index, alertName, severity);

        return new UnifiedAlertDto(
                UUID.randomUUID(),
                tenantId,
                SOURCE,
                SourceType.OPS,
                severity,
                title,
                description,
                firedAt,
                metadata
        );
    }

    private ResolvedAlertNotification normalizeResolvedAlert(JsonNode alert,
                                                             String tenantId,
                                                             int index) {
        final JsonNode labels = alert.get("labels");
        if (isMissingOrNotObject(labels)) {
            throw new NormalizationException(SOURCE,
                    String.format("Missing 'labels' in resolved alert at index %d",
                            index));
        }

        final String alertName = getTextOrThrow(labels, "alertname");

        final String fingerprint = buildFingerprint(alertName);

        final Instant resolvedAt = parseInstant(getText(alert, "endsAt", null));

        log.debug("Prometheus resolved alert[{}] normalized: alertName={}, " +
                "fingerprint={}", index, alertName, fingerprint);

        return ResolvedAlertNotification.of(tenantId, SOURCE, fingerprint, resolvedAt);
    }

    private String mapSeverity(String rawSeverity) {
        if (rawSeverity == null) return "LOW";
        return switch (rawSeverity.toLowerCase()) {
            case "critical" -> "CRITICAL";
            case "high"     -> "HIGH";
            case "warning"  -> "MEDIUM";
            case "info"     -> "LOW";
            default -> {
                log.warn("Unknown Prometheus severity: '{}', defaulting to LOW",
                        rawSeverity);
                yield "LOW";
            }
        };
    }

    private Map<String, String> extractMetadata(JsonNode labels) {
        final Map<String, String> metadata = new HashMap<>();
        labels.properties().forEach(entry -> {
            if (!entry.getKey().equals("alertname")
                    && !entry.getKey().equals("severity")) {
                metadata.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return metadata;
    }
}