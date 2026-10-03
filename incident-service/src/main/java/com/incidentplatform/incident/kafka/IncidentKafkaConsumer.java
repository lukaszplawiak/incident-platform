package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.service.IncidentCommandService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import com.incidentplatform.shared.kafka.KafkaFailures;
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

        // Backlog #0-91: no placeholder tenant is set before the record's own
        // ("unknown" used to be, a valid tenant id that reached the dead-letter
        // key and header); TenantContext.clear() in finally is safe either way.

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
            // Backlog #0-92: the tenant is the resolved one, or none (null)
            // when the record's tenant was refused. Changed (backlog #0-96,
            // found in review): the reason is a message only when the platform
            // wrote it content-free, else the exception's type and place
            // (KafkaFailures.reason) — one line was not enough, a parser's
            // message quotes the record's values.
            final String tenantId = TenantContext.getOrNull();
            final String error = KafkaFailures.reason(e);
            log.error("Poison pill detected — routing to DLT: " +
                            "topic={}, partition={}, offset={}, tenant={}, error={}",
                    record.topic(), record.partition(), record.offset(),
                    tenantId, error);

            // Backlog #0-96: acknowledged only once Kafka has the copy.
            deadLetterPublisher.deadLetterThenAcknowledge(record, tenantId, error, acknowledgment);
            return;

        } catch (Exception e) {
            // Changed (backlog #0-96): this returned without acknowledging
            // for any exception, as "will be redelivered" — it was not: the
            // next record's acknowledgement committed the offset past it. A
            // transient failure (the database) is now nacked and read again;
            // anything else fails the same way every time and is
            // dead-lettered (backlog #47's rule, now shared: KafkaFailures).
            deadLetterPublisher.redeliverIfTransientElseDeadLetter(
                    record, TenantContext.getOrNull(), e, acknowledgment);
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
            // Poison pill — route to DLT, then acknowledge to unblock the
            // partition. Backlog #0-92: the tenant is the resolved one, or none (null)
            // when the record's tenant was refused. Changed (backlog #0-96,
            // found in review): the reason is a message only when the platform
            // wrote it content-free, else the exception's type and place
            // (KafkaFailures.reason) — one line was not enough, a parser's
            // message quotes the record's values.
            final String tenantId = TenantContext.getOrNull();
            final String error = KafkaFailures.reason(e);
            log.error("Poison pill detected — routing to DLT: " +
                            "topic={}, partition={}, offset={}, tenant={}, error={}",
                    record.topic(), record.partition(), record.offset(),
                    tenantId, error);

            deadLetterPublisher.deadLetterThenAcknowledge(record, tenantId, error, acknowledgment);
            return;

        } catch (Exception e) {
            // Backlog #0-96: as in consumeAlert.
            deadLetterPublisher.redeliverIfTransientElseDeadLetter(
                    record, TenantContext.getOrNull(), e, acknowledgment);
            return;

        } finally {
            TenantContext.clear();
        }

        acknowledgment.acknowledge();
    }
}