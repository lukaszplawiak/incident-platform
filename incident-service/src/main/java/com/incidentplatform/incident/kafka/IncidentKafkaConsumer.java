package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.service.IncidentCommandService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import com.incidentplatform.shared.kafka.TenantKafkaRecordResolver;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * <h2>Fixed (backlog #75): tenant/JSON resolution delegated to a shared bean</h2>
 * {@code parseJson}/{@code extractTenantId} used to be private methods here,
 * byte-for-byte identical to four other {@code @KafkaListener} consumers
 * across three other services — see {@link TenantKafkaRecordResolver}'s own
 * Javadoc for the full account. Both calls below now delegate to that
 * shared, injected bean instead.
 */
@Component
public class IncidentKafkaConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentKafkaConsumer.class);

    private final IncidentCommandService commandService;
    private final ObjectMapper objectMapper;
    private final DeadLetterPublisher deadLetterPublisher;
    private final TenantKafkaRecordResolver tenantRecordResolver;

    public IncidentKafkaConsumer(IncidentCommandService commandService,
                                 ObjectMapper objectMapper,
                                 DeadLetterPublisher deadLetterPublisher,
                                 TenantKafkaRecordResolver tenantRecordResolver) {
        this.commandService = commandService;
        this.objectMapper = objectMapper;
        this.deadLetterPublisher = deadLetterPublisher;
        this.tenantRecordResolver = tenantRecordResolver;
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

        // TenantContext is pre-initialised so that finally { TenantContext.clear() }
        // is always safe, even if extractTenantId() throws before setting it.
        TenantContext.set("unknown");

        try {
            final JsonNode raw = tenantRecordResolver.parseJson(record.value());
            final String tenantId = tenantRecordResolver.extractTenantId(record, raw);
            TenantContext.set(tenantId);

            final UnifiedAlertDto alert = objectMapper.treeToValue(raw, UnifiedAlertDto.class);

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

            log.info("Alert processed successfully: alertId={}, tenant={}",
                    alert.alertId(), tenantId);

        } catch (IllegalArgumentException e) {
            final String tenantId = TenantContext.getOrNull();
            log.error("Poison pill detected — routing to DLT: " +
                            "topic={}, partition={}, offset={}, tenant={}, error={}",
                    record.topic(), record.partition(), record.offset(),
                    tenantId, e.getMessage());

            deadLetterPublisher.publish(
                    record.value(),
                    record.topic(),
                    tenantId != null ? tenantId : "unknown",
                    e.getMessage());

        } catch (Exception e) {
            log.error("Transient error processing alert — message will be redelivered: " +
                            "topic={}, partition={}, offset={}, tenant={}, error={}",
                    record.topic(), record.partition(), record.offset(),
                    TenantContext.getOrNull(), e.getMessage(), e);
            return;

        } finally {
            TenantContext.clear();
        }

        acknowledgment.acknowledge();
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

        TenantContext.set("unknown");

        try {
            final JsonNode raw = tenantRecordResolver.parseJson(record.value());
            final String tenantId = tenantRecordResolver.extractTenantId(record, raw);
            TenantContext.set(tenantId);

            final ResolvedAlertNotification notification =
                    objectMapper.treeToValue(raw, ResolvedAlertNotification.class);

            log.info("Processing resolved alert: fingerprint={}, source={}, tenant={}",
                    notification.alertFingerprint(), notification.source(), tenantId);

            commandService.autoResolve(notification, tenantId);

            log.info("Resolved alert processed: fingerprint={}, tenant={}",
                    notification.alertFingerprint(), tenantId);

        } catch (IllegalArgumentException e) {
            // Poison pill — route to DLT and acknowledge to unblock partition.
            final String tenantId = TenantContext.getOrNull();
            log.error("Poison pill detected — routing to DLT: " +
                            "topic={}, partition={}, offset={}, tenant={}, error={}",
                    record.topic(), record.partition(), record.offset(),
                    tenantId, e.getMessage());

            deadLetterPublisher.publish(
                    record.value(),
                    record.topic(),
                    tenantId != null ? tenantId : "unknown",
                    e.getMessage());

        } catch (Exception e) {
            // Transient error — do NOT acknowledge, allow redelivery.
            log.error("Transient error processing resolved alert — will be redelivered: " +
                            "topic={}, partition={}, offset={}, tenant={}, error={}",
                    record.topic(), record.partition(), record.offset(),
                    TenantContext.getOrNull(), e.getMessage(), e);
            return;

        } finally {
            TenantContext.clear();
        }

        acknowledgment.acknowledge();
    }
}