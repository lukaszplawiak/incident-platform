package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.service.IncidentCommandService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class IncidentKafkaConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentKafkaConsumer.class);

    private final IncidentCommandService commandService;
    private final ObjectMapper objectMapper;

    public IncidentKafkaConsumer(IncidentCommandService commandService,
                                 ObjectMapper objectMapper) {
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${kafka.topics.alerts-raw}",
            groupId = "incident-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeAlert(ConsumerRecord<String, String> record,
                             Acknowledgment acknowledgment) {
        log.debug("Received alert: topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());

        // Extract tenantId from this specific record's header.
        // We cannot use TenantContext here because it may contain a tenantId
        // from a different record in the same Kafka batch — this topic is
        // multi-tenant and each record carries its own tenantId header.
        final String tenantId = extractTenantId(record);
        TenantContext.set(tenantId);

        try {
            final UnifiedAlertDto alert = deserialize(record.value(),
                    UnifiedAlertDto.class);

            // Layer 4 — consumer-side severity prioritization.
            // CRITICAL alerts logged at higher priority for faster identification.
            //
            // TODO: Split into separate topics per severity (alerts.raw.critical,
            // alerts.raw.high etc.) when project moves to Kubernetes with multiple replicas.
            // Priority benefit is minimal with single instance.
            // With multiple replicas — separate consumer groups with different concurrency:
            // alerts.raw.critical → concurrency=5
            // alerts.raw.high     → concurrency=3
            // alerts.raw.medium   → concurrency=2
            // alerts.raw.low      → concurrency=1

            if (Severity.CRITICAL.equals(alert.severity())) {
                log.warn("CRITICAL alert received — high priority processing: " +
                                "alertId={}, fingerprint={}, tenant={}",
                        alert.alertId(), alert.fingerprint(), tenantId);
            } else {
                log.info("Processing firing alert: alertId={}, fingerprint={}, " +
                                "severity={}, source={}, tenant={}",
                        alert.alertId(), alert.fingerprint(),
                        alert.severity(), alert.source(), tenantId);
            }

            commandService.createFromAlert(alert, tenantId);
            acknowledgment.acknowledge();

            log.info("Alert processed successfully: alertId={}, tenant={}",
                    alert.alertId(), tenantId);

        } finally {
            TenantContext.clear();
        }
    }

    @KafkaListener(
            topics = "${kafka.topics.alerts-resolved}",
            groupId = "incident-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeResolvedAlert(ConsumerRecord<String, String> record,
                                     Acknowledgment acknowledgment) {
        log.debug("Received resolved alert: topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());

        final String tenantId = extractTenantId(record);
        TenantContext.set(tenantId);

        try {
            final ResolvedAlertNotification notification = deserialize(
                    record.value(), ResolvedAlertNotification.class);

            log.info("Processing resolved alert: fingerprint={}, source={}, tenant={}",
                    notification.alertFingerprint(), notification.source(), tenantId);

            commandService.autoResolve(notification, tenantId);
            acknowledgment.acknowledge();

            log.info("Resolved alert processed: fingerprint={}, tenant={}",
                    notification.alertFingerprint(), tenantId);

        } finally {
            TenantContext.clear();
        }
    }

    // Reads tenantId from the Kafka record header set by TenantKafkaProducerInterceptor.
    // Each record on a multi-tenant topic carries its own tenantId header —
    private String extractTenantId(ConsumerRecord<?, ?> record) {
        final Header header = record.headers()
                .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);
        if (header != null) {
            final String tenantId = new String(header.value(), StandardCharsets.UTF_8);
            if (!tenantId.isBlank()) {
                return tenantId;
            }
        }
        throw new IllegalStateException(
                "Missing tenantId header in Kafka record: topic=" + record.topic() +
                        ", partition=" + record.partition() +
                        ", offset=" + record.offset());
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to deserialize Kafka message to %s: %s",
                            type.getSimpleName(), e.getMessage()), e);
        }
    }
}