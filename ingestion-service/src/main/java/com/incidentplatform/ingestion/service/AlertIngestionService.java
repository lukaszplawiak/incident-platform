package com.incidentplatform.ingestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.ingestion.normalizer.AlertNormalizer;
import com.incidentplatform.ingestion.normalizer.NormalizationException;
import com.incidentplatform.ingestion.normalizer.NormalizationResult;
import com.incidentplatform.ingestion.normalizer.UnknownSourceException;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AlertIngestionService {

    private static final Logger log =
            LoggerFactory.getLogger(AlertIngestionService.class);

    private final Map<String, AlertNormalizer> normalizersBySource;
    private final DeduplicationService deduplicationService;
    private final AlertKafkaProducer kafkaProducer;
    private final DeadLetterPublisher deadLetterPublisher;

    public AlertIngestionService(
            List<AlertNormalizer> normalizers,
            DeduplicationService deduplicationService,
            AlertKafkaProducer kafkaProducer,
            DeadLetterPublisher deadLetterPublisher) {

        this.normalizersBySource = normalizers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AlertNormalizer::getSourceName,
                        Function.identity()
                ));
        this.deduplicationService = deduplicationService;
        this.kafkaProducer = kafkaProducer;
        this.deadLetterPublisher = deadLetterPublisher;

        log.info("AlertIngestionService initialized with normalizers: {}",
                normalizersBySource.keySet());
    }

    /**
     * Ingests an alert payload from an external monitoring system.
     *
     * @param teamId  resolved from the Integration ApiKey via
     *                {@code ApiKeyLookupServiceImpl.resolveTeamId()}.
     *                Null when authenticated with JWT or Integration has no team.
     *                Propagated to every {@link com.incidentplatform.shared.dto.UnifiedAlertDto}
     *                so incident-service can set {@code Incident.team_id}.
     */
    public IngestionSummary ingest(String source,
                                   JsonNode rawPayload,
                                   String tenantId,
                                   UUID teamId) {
        log.info("Starting ingestion: source={}, tenant={}, teamId={}",
                source, tenantId, teamId);

        final AlertNormalizer normalizer = findNormalizer(source);

        int processed = 0;
        int duplicates = 0;
        int resolved = 0;
        int deadLetter = 0;

        NormalizationResult result;
        try {
            result = normalizer.normalize(rawPayload, tenantId, teamId);
        } catch (NormalizationException e) {
            log.error("Normalization failed for entire payload: source={}, " +
                    "tenant={}, reason={}", source, tenantId, e.getReason());
            deadLetterPublisher.publish(rawPayload, source, tenantId, e.getReason());
            return IngestionSummary.of(1, 0, 0, 0, 1);
        }

        final int received = result.firingAlerts().size()
                + result.resolvedAlerts().size();

        for (UnifiedAlertDto alert : result.firingAlerts()) {
            try {
                if (deduplicationService.isDuplicate(alert)) {
                    duplicates++;
                    continue;
                }
                kafkaProducer.publishFiring(alert);
                processed++;
            } catch (AlertKafkaProducer.AlertPublishException e) {
                // Despite the class name, this can only be thrown from a JSON
                // serialization failure inside AlertKafkaProducer — a genuinely
                // poison-pill scenario (this exact alert object will never
                // serialize, retrying won't help), which is why DLQ is the
                // right response here. It is NOT thrown for real Kafka send
                // failures (broker down, etc.) — those happen asynchronously
                // and never propagate to this catch block; see
                // AlertKafkaProducer's Javadoc for why and how those are
                // handled instead (a counter + log, no DLQ — a Kafka-based
                // DLQ can't help when Kafka itself is unreachable).
                log.error("Failed to serialize firing alert for Kafka — " +
                                "routing to DLQ: alertId={}, source={}, tenant={}",
                        alert.alertId(), source, tenantId, e);
                deadLetterPublisher.publish(
                        rawPayload, source, tenantId,
                        "Alert serialization failed: " + e.getMessage());
                deadLetter++;
            }
        }

        for (ResolvedAlertNotification notification : result.resolvedAlerts()) {
            try {
                kafkaProducer.publishResolved(notification);
                resolved++;
            } catch (AlertKafkaProducer.AlertPublishException e) {
                // Same caveat as above — serialization failure only, not a
                // real Kafka send failure.
                log.error("Failed to serialize resolved notification for Kafka — " +
                                "routing to DLQ: eventId={}, tenant={}",
                        notification.eventId(), tenantId, e);
                deadLetterPublisher.publish(
                        rawPayload, source, tenantId,
                        "Resolved notification serialization failed: " + e.getMessage());
                deadLetter++;
            }
        }

        final IngestionSummary summary = IngestionSummary.of(
                received, processed, duplicates, resolved, deadLetter);

        if (summary.hasDeadLetterAlerts()) {
            log.warn("Ingestion completed with DLQ alerts: source={}, tenant={}, " +
                    "summary={}", source, tenantId, summary);
        } else {
            log.info("Ingestion completed successfully: source={}, tenant={}, " +
                    "summary={}", source, tenantId, summary);
        }

        return summary;
    }

    public List<String> getAvailableSources() {
        return List.copyOf(normalizersBySource.keySet());
    }

    private AlertNormalizer findNormalizer(String source) {
        final AlertNormalizer normalizer = normalizersBySource.get(
                source.toLowerCase());
        if (normalizer == null) {
            throw new UnknownSourceException(source,
                    List.copyOf(normalizersBySource.keySet()));
        }
        return normalizer;
    }
}