package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.service.IncidentCommandService;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import com.incidentplatform.shared.kafka.KafkaFailures;
import com.incidentplatform.shared.kafka.TenantKafkaRecordResolver;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Closes the loop on incident escalation: incident-service publishes
 * {@code IncidentEscalatedEvent} (via {@link com.incidentplatform.incident.service.IncidentEventPublisher}
 * for manual REST-driven escalation, and escalation-service publishes the same
 * event type for automatic timeout-driven escalation) — but until this consumer
 * existed, incident-service never read its own event back, so
 * {@code Incident.escalationLevel} only reflected manual changes and was never
 * updated by escalation-service's automatic {@code EscalationScheduler}.
 *
 * <p>This consumer listens to {@code incidents.lifecycle} for
 * {@code IncidentEscalatedEvent} specifically and calls
 * {@link Incident#recordEscalation(int)} — a pure attribute update that does
 * NOT go through {@link com.incidentplatform.incident.domain.IncidentFsm},
 * since escalation level is independent of the main lifecycle status.
 *
 * <p>Uses the same shared {@link TenantKafkaRecordResolver} (backlog #75)
 * as the other {@code incidents.lifecycle} consumers (escalation-service,
 * notification-service, postmortem-service) for consistency across the
 * platform — see that class's own Javadoc for the full account of the
 * duplication this replaced.
 *
 * <h2>Fixed (backlog #40): imprecise logging for an expected, self-healing
 * concurrency conflict</h2>
 * {@code Incident} has {@code @Version} — a REST-driven change to the
 * same incident (e.g. an engineer acknowledging it via the API) racing
 * against this consumer recording an escalation level can trigger an
 * optimistic lock conflict. Unlike {@code EscalationScheduler}'s version
 * of this problem (backlog #38, where retrying a concurrently-cancelled
 * task is pointless — there is nothing left to reconcile), retrying
 * here genuinely works: {@code recordEscalation(int)} only ever touches
 * the {@code escalationLevel} field, so re-reading the row on Kafka
 * redelivery and reapplying it correctly reconciles with whatever the
 * other writer changed in the meantime. The generic
 * {@code catch (Exception e)} below was therefore never <em>incorrect</em>
 * here the way it would have been for {@code EscalationScheduler} — but
 * it logged every failure identically as "Transient error... will be
 * redelivered" at ERROR level, giving no way to tell a routine,
 * self-healing concurrency conflict apart from a genuine problem (the
 * database actually being unreachable) from the logs alone. The new,
 * specific catch below doesn't change the retry behavior at all (still
 * no acknowledge, still relies on Kafka redelivery) — only the
 * diagnostic precision of what gets logged.
 *
 * <h2>Changed (backlog #0-96): the conflict is now actually retried</h2>
 * "No acknowledge, Kafka redelivers" did not hold: in {@code MANUAL_IMMEDIATE}
 * mode the next record's acknowledgement commits the offset past an
 * unacknowledged one. And this listener method was {@code @Transactional},
 * so its own acknowledgement committed the offset before the database commit,
 * where the {@code @Version} conflict is actually thrown — after this
 * {@code catch}. The write now runs and commits in
 * {@link IncidentCommandService#recordEscalationLevel} before the record is
 * acknowledged, and a conflict (a transient failure, {@code KafkaFailures})
 * is {@code nack}ed and read again.
 */
@Component
public class IncidentEscalationEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentEscalationEventConsumer.class);

    private final IncidentCommandService commandService;
    private final TenantKafkaRecordResolver tenantRecordResolver;
    private final DeadLetterPublisher deadLetterPublisher;

    public IncidentEscalationEventConsumer(IncidentCommandService commandService,
                                           TenantKafkaRecordResolver tenantRecordResolver,
                                           DeadLetterPublisher deadLetterPublisher) {
        this.commandService = commandService;
        this.tenantRecordResolver = tenantRecordResolver;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    @KafkaListener(
            topics = "${kafka.topics.incidents-lifecycle}",
            groupId = "incident-service-escalation-sync",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeIncidentEvent(ConsumerRecord<String, String> record,
                                     Acknowledgment acknowledgment) {
        log.debug("Received incident event: topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());

        try {
            final String eventType = extractEventType(record);
            if (eventType == null) {
                // Changed (backlog #0-96): this record was acknowledged and
                // dropped with only a log line; it is now kept like any other
                // record this consumer cannot read, under its tenant when the
                // record has a trustworthy one (not counted as a tenant
                // refusal: it is refused for its header).
                log.error("Missing {} header — routing to DLT: topic={}, partition={}, offset={}",
                        IncidentEventTypes.HEADER_NAME,
                        record.topic(), record.partition(), record.offset());
                deadLetterPublisher.deadLetterThenAcknowledge(record,
                        tenantRecordResolver.trustedTenantOrNull(record),
                        "missing " + IncidentEventTypes.HEADER_NAME + " header", acknowledgment);
                return;
            }

            if (!IncidentEventTypes.INCIDENT_ESCALATED.equals(eventType)) {
                // This consumer only cares about escalation — all other event
                // types on this topic are handled by IncidentKafkaConsumer's
                // own write path (this service is the producer of those, not
                // a self-consumer for them).
                acknowledgment.acknowledge();
                return;
            }

            final JsonNode event = tenantRecordResolver.parseJson(record.value());
            final String tenantId = tenantRecordResolver.extractTenantId(record, event);
            TenantContext.set(tenantId);

            handleEscalated(event, tenantId);

        } catch (IllegalArgumentException e) {
            // Poison pill — unparseable JSON, a refused tenant, bad UUID, or
            // missing required field. Retrying will never succeed.
            //
            // Fixed (backlog #0-92): it used to be acknowledged and dropped,
            // the only consumer that kept no copy; it now goes to the
            // dead-letter topic like every other consumer's, with its resolved
            // tenant or none; since backlog #0-96 the reason never quotes the
            // record (KafkaFailures.reason).
            final String error = KafkaFailures.reason(e);
            log.error("Poison pill in incident escalation event — routing to DLT: " +
                            "topic={}, partition={}, offset={}, tenant={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), TenantContext.getOrNull(), error);
            deadLetterPublisher.deadLetterThenAcknowledge(record, TenantContext.getOrNull(), error, acknowledgment);
            return;

        } catch (OptimisticLockingFailureException e) {
            // Fixed (backlog #40): a REST-driven change to this same
            // incident (e.g. an ACK) raced with recordEscalation() and won.
            // Not a genuine failure: recordEscalation() only touches
            // escalationLevel, so reading the record again re-reads the
            // now-current row and reapplies just the level on top of it.
            // Logged apart from the generic catch below so a routine,
            // self-healing conflict is not reported as an outage.
            // Backlog #0-96: nacked (the conflict is transient), no longer
            // left unacknowledged, which skipped it.
            log.info("Concurrent modification detected while recording " +
                            "escalation — reading the record again: " +
                            "topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
            deadLetterPublisher.redeliverLater(record, TenantContext.getOrNull(), acknowledgment, e);
            return;

        } catch (Exception e) {
            // Backlog #0-96: a transient failure (the database) is nacked and
            // read again; anything else is dead-lettered (KafkaFailures).
            deadLetterPublisher.redeliverIfTransientElseDeadLetter(
                    record, TenantContext.getOrNull(), e, acknowledgment);
            return;

        } finally {
            TenantContext.clear();
        }

        acknowledgment.acknowledge();
    }

    private void handleEscalated(JsonNode event, String tenantId) {
        final UUID incidentId = UUID.fromString(
                event.get("incidentId").asText());
        final int escalationLevel = event.path("escalationLevel").asInt(0);

        if (commandService.recordEscalationLevel(incidentId, tenantId, escalationLevel)) {
            log.info("Escalation level recorded: incidentId={}, " +
                            "level={}, tenant={}",
                    incidentId, escalationLevel, tenantId);
        } else {
            log.warn("Escalated incident not found locally — " +
                            "skipping: incidentId={}, tenant={}",
                    incidentId, tenantId);
        }
    }

    private String extractEventType(ConsumerRecord<?, ?> record) {
        final Header header = record.headers()
                .lastHeader(IncidentEventTypes.HEADER_NAME);
        if (header != null) {
            final String value = new String(header.value(), StandardCharsets.UTF_8);
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}