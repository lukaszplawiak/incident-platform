package com.incidentplatform.ingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.events.SourceType;
import com.incidentplatform.ingestion.config.IngestionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@EnableConfigurationProperties(IngestionProperties.class)
public class PrometheusNormalizer extends BaseNormalizer {

    private static final String SOURCE = "prometheus";
    private static final String STATUS_RESOLVED = "resolved";
    private static final String STATUS_FIRING = "firing";

    private final int maxBatchSize;

    public PrometheusNormalizer(
            IngestionProperties properties) {
        this.maxBatchSize = properties.prometheus().maxBatchSize();
    }

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

            // NormalizationException is unchecked — it propagates naturally
            // without a try-catch block. The removed catch { throw e; } was
            // a no-op that added noise and implied unfinished error handling.
            if (STATUS_RESOLVED.equals(status)) {
                resolvedAlerts.add(normalizeResolvedAlert(alert, tenantId, i));
            } else {
                firingAlerts.add(normalizeFiringAlert(alert, tenantId, i));
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

        final Severity severity = mapSeverity(rawSeverity);

        final String title = annotations != null
                ? getText(annotations, "summary", alertName)
                : alertName;
        final String description = annotations != null
                ? getText(annotations, "description", null)
                : null;
        final Instant firedAt = parseInstant(getText(alert, "startsAt", null));
        final Map<String, String> metadata = extractMetadata(labels);

        final String instance = getText(labels, "instance",
                getText(labels, "job", "unknown"));
        final String fingerprint = buildFingerprint(alertName, instance);

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
                fingerprint,
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
        final String instance = getText(labels, "instance",
                getText(labels, "job", "unknown"));
        final String fingerprint = buildFingerprint(alertName, instance);
        final Instant resolvedAt = parseInstant(getText(alert, "endsAt", null));

        log.debug("Prometheus resolved alert[{}] normalized: alertName={}, " +
                "fingerprint={}", index, alertName, fingerprint);

        return ResolvedAlertNotification.of(tenantId, SOURCE, fingerprint, resolvedAt);
    }

    private Severity mapSeverity(String rawSeverity) {
        if (rawSeverity == null) return Severity.LOW;
        return switch (rawSeverity.toLowerCase()) {
            case "critical" -> Severity.CRITICAL;
            case "high"     -> Severity.HIGH;
            case "warning"  -> Severity.MEDIUM;
            case "info"     -> Severity.LOW;
            default -> {
                log.warn("Unknown Prometheus severity: '{}', defaulting to LOW",
                        rawSeverity);
                yield Severity.LOW;
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