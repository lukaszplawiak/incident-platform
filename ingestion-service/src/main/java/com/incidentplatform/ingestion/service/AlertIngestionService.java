package com.incidentplatform.ingestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.ingestion.normalizer.AlertNormalizer;
import com.incidentplatform.ingestion.normalizer.NormalizationException;
import com.incidentplatform.ingestion.normalizer.NormalizationResult;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
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

    public IngestionSummary ingest(String source,
                                   JsonNode rawPayload,
                                   String tenantId) {
        log.info("Starting ingestion: source={}, tenant={}", source, tenantId);

        final AlertNormalizer normalizer = findNormalizer(source);

        int processed = 0;
        int duplicates = 0;
        int resolved = 0;
        int deadLetter = 0;

        NormalizationResult result;
        try {
            result = normalizer.normalize(rawPayload, tenantId);
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
                log.error("Failed to publish firing alert: alertId={}, " +
                        "source={}, tenant={}", alert.alertId(), source, tenantId, e);
                deadLetterPublisher.publish(
                        rawPayload, source, tenantId,
                        "Kafka publish failed: " + e.getMessage());
                deadLetter++;
            }
        }

        for (ResolvedAlertNotification notification : result.resolvedAlerts()) {
            try {
                kafkaProducer.publishResolved(notification);
                resolved++;
            } catch (AlertKafkaProducer.AlertPublishException e) {
                log.error("Failed to publish resolved notification: eventId={}, " +
                        "tenant={}", notification.eventId(), tenantId, e);
                deadLetterPublisher.publish(
                        rawPayload, source, tenantId,
                        "Kafka resolved publish failed: " + e.getMessage());
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

    public static class UnknownSourceException extends RuntimeException {

        private final String source;
        private final List<String> availableSources;

        public UnknownSourceException(String source, List<String> availableSources) {
            super(String.format(
                    "Unknown alert source: '%s'. Available sources: %s",
                    source, availableSources));
            this.source = source;
            this.availableSources = availableSources;
        }

        public String getSource() { return source; }
        public List<String> getAvailableSources() { return availableSources; }
    }
}