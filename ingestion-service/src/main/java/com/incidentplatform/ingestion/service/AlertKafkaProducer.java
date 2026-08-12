package com.incidentplatform.ingestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Fire-and-forget Kafka producer for ingested alerts.
 *
 * <h2>Fixed: real Kafka send failures were invisible — no metric, and a
 * misleadingly-named exception path in the caller</h2>
 * A genuine Kafka broker outage was previously only a single ERROR log
 * line inside {@code .whenComplete(...)} — no {@link Counter}, unlike
 * this same module's {@code DeduplicationService.redisErrorCounter} and
 * {@code RateLimitingService.redisErrorCounter} for the equivalent
 * "infrastructure dependency failed" case. Nobody would notice a Kafka
 * outage silently dropping alerts here except by reading logs after the
 * fact.
 *
 * <p>Separately, {@link AlertIngestionService} catches
 * {@link AlertPublishException} and routes it to the dead-letter topic
 * with a log message reading "Kafka publish failed" — but this
 * exception can only ever be thrown from the {@code catch
 * (JsonProcessingException e)} block below, i.e. a serialization
 * failure, never an actual Kafka send failure (those happen
 * asynchronously in {@code .whenComplete(...)} and were never
 * propagated to the caller at all). See
 * {@link AlertIngestionService}'s own comment at that catch block for
 * the corrected explanation.
 *
 * <p>This gap is intentionally NOT closed with a dead-letter-on-send-
 * failure mechanism here: {@code DeadLetterPublisher} itself publishes
 * to Kafka, so it cannot help when the underlying problem is "Kafka is
 * unreachable" — routing a Kafka-send failure to a Kafka-based DLQ is a
 * chicken-and-egg. A real guarantee against alert loss during a full
 * Kafka outage would need a durable, non-Kafka-dependent store (the
 * Transactional Outbox pattern already used in auth-service's
 * {@code AuthEmailOutbox}) — a significant addition given
 * ingestion-service has no database today, and deliberately out of
 * scope for this fix. Tracked separately as a larger backlog item.
 * What's fixed here is proportionate to that existing, deliberate
 * trade-off (see the comment on {@link #publishFiring} below): making
 * the failure observable and the code's own behavior honestly
 * documented, not silently misleading about a safety net that doesn't
 * structurally exist.
 * <p>Fixed separately (backlog #24): {@link #publishFiring} now returns
 * the underlying {@link java.util.concurrent.CompletableFuture} instead
 * of {@code void}, so {@code AlertIngestionService} can chain its own
 * compensating action on send failure — releasing the dedup key that
 * {@code DeduplicationService.isDuplicate} already set for this alert,
 * so a legitimate retry isn't wrongly rejected as a duplicate for the
 * rest of the TTL window. See {@code DeduplicationService
 * .releaseDedupKey}'s Javadoc for the full account. This class's own
 * {@code .whenComplete} below (metrics + logging) is unchanged and still
 * runs independently — the two concerns are chained separately by
 * design, not merged into one handler.
 */
@Component
public class AlertKafkaProducer {

    private static final Logger log =
            LoggerFactory.getLogger(AlertKafkaProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String alertsRawTopic;
    private final String alertsResolvedTopic;
    private final Counter kafkaPublishErrorCounter;

    public AlertKafkaProducer(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${kafka.topics.alerts-raw}") String alertsRawTopic,
            @Value("${kafka.topics.alerts-resolved}") String alertsResolvedTopic,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.alertsRawTopic = alertsRawTopic;
        this.alertsResolvedTopic = alertsResolvedTopic;

        this.kafkaPublishErrorCounter = Counter.builder("kafka.publish.errors")
                .description("Number of alerts/notifications that failed to publish " +
                        "to Kafka (fire-and-forget send failure) — these are LOST, " +
                        "not dead-lettered; see AlertKafkaProducer's Javadoc")
                .tag("service", "ingestion-service")
                .register(meterRegistry);
    }

    // Fire-and-forget: Kafka errors are logged but not propagated to the caller
    // synchronously — this method itself never blocks on or throws for a send
    // failure. Blocking on broker availability would cause HTTP 5xx during
    // Kafka outages — Alertmanager would retry aggressively, creating alert
    // floods. Trade-off: occasional alert loss vs. endpoint availability
    // under pressure. Send failures are counted (kafkaPublishErrorCounter)
    // and logged, but not retried or dead-lettered — see this class's
    // Javadoc for why a Kafka-based DLQ can't help when Kafka itself is the
    // thing that's unreachable.
    //
    // Returning the CompletableFuture below does NOT change any of this —
    // the HTTP request thread that called this method has already moved on
    // by the time the future completes; nothing here blocks it. It exists
    // solely so AlertIngestionService can attach its own additional
    // .whenComplete for the dedup-key release (backlog #24) without
    // duplicating this method's own send call.
    public CompletableFuture<SendResult<String, String>> publishFiring(UnifiedAlertDto alert) {
        try {
            final String payload = objectMapper.writeValueAsString(alert);

            // TenantKafkaProducerInterceptor still runs as a safety net but is not
            // the primary source of the X-Tenant-Id header here.
            //
            // TODO: Migrate to Envelope Pattern for a fully explicit messaging contract:
            //  Instead of setting tenantId only as a Kafka header, wrap every message in
            //  a typed KafkaEnvelope<T> carrying routing metadata (tenantId, eventType,
            //  correlationId, producedAt) alongside the domain payload. Benefits: tenantId
            //  is part of the schema (not just a header), end-to-end correlation ID
            //  tracing, easy replay with full context, consumers access metadata without
            //  deserializing the inner payload. Requires: new KafkaEnvelope<T> record in
            //  shared/, schema changes in all producers/consumers, and a consistent
            //  deserialization strategy. Justified when adding OpenTelemetry distributed
            //  tracing or when the number of producers/consumers grows significantly.
            final ProducerRecord<String, String> record = new ProducerRecord<>(
                    alertsRawTopic,
                    null,
                    alert.tenantId(),
                    payload
            );
            record.headers().add(new RecordHeader(
                    TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    alert.tenantId().getBytes(StandardCharsets.UTF_8)
            ));

            final var future = kafkaTemplate.send(record);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    kafkaPublishErrorCounter.increment();
                    log.error("Failed to publish firing alert to Kafka — " +
                                    "ALERT LOST (fire-and-forget, no retry): " +
                                    "topic={}, alertId={}, source={}, tenant={}",
                            alertsRawTopic, alert.alertId(),
                            alert.source(), alert.tenantId(), ex);
                } else {
                    log.debug("Firing alert published: topic={}, " +
                                    "partition={}, offset={}, alertId={}, " +
                                    "source={}, tenant={}",
                            alertsRawTopic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset(),
                            alert.alertId(), alert.source(),
                            alert.tenantId());
                }
            });

            return future;

        } catch (JsonProcessingException e) {
            throw new AlertPublishException(
                    String.format("Failed to serialize firing alert: alertId=%s",
                            alert.alertId()), e);
        }
    }

    // Same fire-and-forget design as publishFiring — see above for rationale.
    public void publishResolved(ResolvedAlertNotification notification) {
        try {
            final String payload = objectMapper.writeValueAsString(notification);

            final ProducerRecord<String, String> record = new ProducerRecord<>(
                    alertsResolvedTopic,
                    null,
                    notification.tenantId(),
                    payload
            );
            record.headers().add(new RecordHeader(
                    TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    notification.tenantId().getBytes(StandardCharsets.UTF_8)
            ));

            kafkaTemplate.send(record)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            kafkaPublishErrorCounter.increment();
                            log.error("Failed to publish resolved notification — " +
                                            "NOTIFICATION LOST (fire-and-forget, no retry): " +
                                            "topic={}, eventId={}, tenant={}",
                                    alertsResolvedTopic, notification.eventId(),
                                    notification.tenantId(), ex);
                        } else {
                            log.debug("Resolved notification published: topic={}, " +
                                            "partition={}, offset={}, eventId={}, tenant={}",
                                    alertsResolvedTopic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset(),
                                    notification.eventId(), notification.tenantId());
                        }
                    });

        } catch (JsonProcessingException e) {
            throw new AlertPublishException(
                    String.format("Failed to serialize resolved notification: " +
                            "eventId=%s", notification.eventId()), e);
        }
    }

    public static class AlertPublishException extends RuntimeException {
        public AlertPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}