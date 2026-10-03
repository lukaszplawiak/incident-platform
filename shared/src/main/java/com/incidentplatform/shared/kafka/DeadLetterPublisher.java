package com.incidentplatform.shared.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.incidentplatform.shared.audit.AuditText;
import com.incidentplatform.shared.security.TenantIds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes unprocessable Kafka messages to a dead-letter topic.
 *
 * <p>Used when a message is permanently malformed (poison pill) and retrying
 * would never succeed. Instead of silently dropping the message or blocking
 * the partition indefinitely, it is forwarded to a dedicated dead-letter topic
 * for investigation and potential manual reprocessing.
 *
 * <p>This class is NOT a Spring {@code @Component} — it is instantiated by
 * each service's {@code KafkaConfig} with the dead-letter topic name specific
 * to that service. This avoids a shared {@code @Value} property name clash
 * across services that use different DLT topic names:
 * <ul>
 *   <li>ingestion-service → {@code alerts.dead-letter}
 *   <li>incident-service  → {@code incidents.dead-letter}
 * </ul>
 *
 * <p>DLT message format:
 * <pre>{@code
 * {
 *   "sourceService":   "incident-service",
 *   "sourceTopic":     "alerts.raw",
 *   "sourcePartition": 0,          // a consumed record's only
 *   "sourceOffset":    42,         // a consumed record's only
 *   "tenantId":        "acme-corp",
 *   "errorReason":     "Failed to deserialize ...",
 *   "failedAt":        "2026-06-07T12:00:00Z",
 *   "originalPayload": "<raw string from Kafka>"
 * }
 * }</pre>
 *
 * <h2>Changed (backlog #0-91/#0-92): the tenant and the reason</h2>
 * The tenant is the failed record's resolved tenant, or {@code null} when it
 * could not be resolved (consumers used to pass the string {@code "unknown"},
 * which is itself a valid tenant id). A valid one goes into the record's
 * {@code X-Tenant-Id} header (through {@link TenantRecords}, as every tenant
 * record does), the key and the {@code tenantId} field; anything else is
 * recorded as no tenant ({@code null} in the field, {@value #NO_TENANT} in the
 * key, no header), never quoted. The reason is written and logged through
 * {@link AuditText#error} (one line, at most 500 characters): a consumer's
 * reason may carry an exception message quoting the poison pill it rejects.
 *
 * <h2>Changed (backlog #0-96): nothing is let go before Kafka has the copy</h2>
 * Every send now waits for Kafka's acknowledgement. The fire-and-forget
 * {@code publish}, which logged "Message may be LOST" on a failed send after
 * the caller had already acknowledged its record, is gone. A consumer hands
 * a record it cannot process to {@link #deadLetterThenAcknowledge}, which
 * acknowledges it only once the copy is safe (the way {@code AuditEventConsumer}
 * already did, backlog #0-84), and a record it may process later to
 * {@link #redeliverLater}. Both replace a {@code return} without
 * acknowledging, which in {@code AckMode.MANUAL_IMMEDIATE} does not bring the
 * record back: the next record's acknowledgement commits the partition's
 * offset past it ({@code DeadLetterPublisherKafkaIntegrationTest} shows it on
 * a real broker). The price is at-least-once: a copy whose acknowledgement was
 * lost is sent again with the redelivered record; {@code sourceTopic},
 * {@code sourcePartition} and {@code sourceOffset} identify duplicates.
 *
 * <h2>Bounds (backlog #0-96, found in review)</h2>
 * <ul>
 *   <li><b>Time per copy.</b> A send waits at most {@link #DEAD_LETTER_TIMEOUT}
 *       in all, blocking for Kafka's metadata included: the template given to
 *       a service's publisher is {@link #deadLetterTemplate}, whose producer
 *       blocks at most {@link #DEAD_LETTER_MAX_BLOCK}, and the acknowledgement
 *       is awaited for what is left. So a poll of {@code max.poll.records}
 *       poison pills fits in {@code max.poll.interval.ms}, which each consumer
 *       checks at startup ({@link #requireFitsPollInterval}); a {@code nack}'s
 *       delay pauses the partition (spring-kafka's {@code pausedForNack}) and
 *       does not hold the poll thread.</li>
 *   <li><b>Time per failing record.</b> {@link #redeliverLater} gives up on a
 *       record that has been failing for longer than its deadline
 *       ({@code kafka.consumer.redelivery-deadline}, 30 minutes by default):
 *       it is dead-lettered, counted in {@value #GAVE_UP_COUNTER} and alerted
 *       ({@code KafkaRecordRedeliveryGaveUp}, critical). Without that, a
 *       record that keeps failing "transiently" (one that makes a query time
 *       out every time) held its partition, every tenant on it, for ever.</li>
 *   <li><b>Size of a copy.</b> The original payload is cut to
 *       {@link #MAX_ORIGINAL_PAYLOAD_BYTES} (UTF-8) and marked
 *       {@code originalPayloadTruncated}: a record near Kafka's size limit,
 *       escaped into the envelope, would otherwise exceed it, so its copy
 *       could never be stored and the record would be retried for ever.</li>
 *   <li><b>What is written.</b> An unexpected exception is recorded by its
 *       type and logged by its type and first frame only
 *       ({@link AuditText#unexpected}): a driver's or parser's message can
 *       quote the record or a row.</li>
 * </ul>
 * A copy Kafka does not take at all (Kafka down) is retried without a
 * deadline: nothing else can keep the record, and nothing else on the
 * platform moves while Kafka is down.
 */
public class DeadLetterPublisher implements DisposableBean {

    /** The key's tenant part for a record whose tenant could not be resolved (not a valid tenant id). */
    static final String NO_TENANT = "_none";

    /** How long a send waits in all, blocking for metadata included, before it counts as failed. */
    public static final Duration DEAD_LETTER_TIMEOUT = Duration.ofSeconds(5);

    /** How long the dead-letter producer may block for Kafka's metadata ({@link #deadLetterTemplate}). */
    public static final Duration DEAD_LETTER_MAX_BLOCK = Duration.ofSeconds(2);

    /** How long a record left for redelivery waits before the partition is read again from it. */
    public static final Duration REDELIVERY_DELAY = Duration.ofSeconds(5);

    /** How long a record may keep failing before it is dead-lettered instead of redelivered. */
    public static final Duration DEFAULT_REDELIVERY_DEADLINE = Duration.ofMinutes(30);

    /** The original payload's largest size in a copy, in UTF-8 bytes (escaped, at most 6x in the envelope). */
    static final int MAX_ORIGINAL_PAYLOAD_BYTES = 128 * 1024;

    /** Records a consumer left for redelivery, by reason (registered at zero). */
    public static final String REDELIVERY_COUNTER = "kafka.records.redelivery.requested";
    /** Records dead-lettered because they kept failing past their deadline (registered at zero). */
    public static final String GAVE_UP_COUNTER = "kafka.records.redelivery.gave_up";
    static final String REASON_TRANSIENT = "transient";
    static final String REASON_DEAD_LETTER_FAILED = "dead_letter_failed";

    private static final Logger log =
            LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String deadLetterTopic;
    private final String sourceService;
    private final Clock clock;
    private final RecordRedeliveries redeliveries;
    private final Counter transientRedeliveries;
    private final Counter deadLetterFailedRedeliveries;
    private final Counter gaveUp;

    /** For a service that consumes nothing (ingestion-service): the default redelivery deadline. */
    public DeadLetterPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper,
                               String deadLetterTopic,
                               String sourceService,
                               MeterRegistry meterRegistry) {
        this(kafkaTemplate, objectMapper, deadLetterTopic, sourceService, meterRegistry,
                DEFAULT_REDELIVERY_DEADLINE, Clock.systemUTC());
    }

    public DeadLetterPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper,
                               String deadLetterTopic,
                               String sourceService,
                               MeterRegistry meterRegistry,
                               Duration redeliveryDeadline) {
        this(kafkaTemplate, objectMapper, deadLetterTopic, sourceService, meterRegistry,
                redeliveryDeadline, Clock.systemUTC());
    }

    DeadLetterPublisher(KafkaTemplate<String, String> kafkaTemplate,
                        ObjectMapper objectMapper,
                        String deadLetterTopic,
                        String sourceService,
                        MeterRegistry meterRegistry,
                        Duration redeliveryDeadline,
                        Clock clock) {
        // Found in review: a record whose tenant could not be resolved goes out
        // through TenantRecords.withoutTenant, which takes only a dead-letter
        // topic; a topic named otherwise would fail every such record at run
        // time (and its consumer would retry it for ever), so it fails the
        // service's start instead.
        if (deadLetterTopic == null
                || !deadLetterTopic.endsWith(TenantKafkaProducerInterceptor.DEAD_LETTER_SUFFIX)) {
            throw new IllegalArgumentException("A dead-letter topic name must end in "
                    + TenantKafkaProducerInterceptor.DEAD_LETTER_SUFFIX + ": " + deadLetterTopic);
        }
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.deadLetterTopic = deadLetterTopic;
        this.sourceService = sourceService;
        this.clock = clock;
        this.redeliveries = new RecordRedeliveries(clock, redeliveryDeadline);
        this.transientRedeliveries = redeliveryCounter(meterRegistry, REASON_TRANSIENT);
        this.deadLetterFailedRedeliveries = redeliveryCounter(meterRegistry, REASON_DEAD_LETTER_FAILED);
        this.gaveUp = Counter.builder(GAVE_UP_COUNTER)
                .description("Records dead-lettered after failing for longer than their redelivery deadline "
                        + "(backlog #0-96)")
                .register(meterRegistry);
    }

    private static Counter redeliveryCounter(MeterRegistry meterRegistry, String reason) {
        return Counter.builder(REDELIVERY_COUNTER)
                .description("Records a Kafka consumer left unacknowledged to be read again (backlog #0-96)")
                .tag("reason", reason)
                .register(meterRegistry);
    }

    /**
     * The template a service's publisher sends with: the service's producer
     * settings (serializers, interceptors, acks) with {@code max.block.ms}
     * cut to {@link #DEAD_LETTER_MAX_BLOCK}, so a copy never waits longer
     * than {@link #DEAD_LETTER_TIMEOUT} in all, whatever the service's own
     * producer allows (escalation-service keeps Kafka's 60 s on purpose,
     * backlog #0-84). It has a producer of its own, closed by {@link #destroy}.
     */
    public static KafkaTemplate<String, String> deadLetterTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory,
                Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) DEAD_LETTER_MAX_BLOCK.toMillis()));
    }

    /**
     * Fails the consumer's start unless a whole poll of poison pills, each
     * waiting up to {@link #DEAD_LETTER_TIMEOUT} for its copy, takes at most
     * half of {@code max.poll.interval.ms} (the rest is left for the records'
     * own processing). Beyond it Kafka evicts the consumer from its group
     * mid-poll: a rebalance, the poll's records read again, their copies
     * stored twice (found in review: 10 records against 30 s).
     */
    public static void requireFitsPollInterval(int maxPollRecords, Duration maxPollInterval) {
        final Duration worstCase = DEAD_LETTER_TIMEOUT.multipliedBy(maxPollRecords);
        if (worstCase.compareTo(maxPollInterval.dividedBy(2)) > 0) {
            throw new IllegalStateException("max.poll.records (" + maxPollRecords + ") x " + DEAD_LETTER_TIMEOUT
                    + " for dead-letter copies (" + worstCase + ") exceeds half of max.poll.interval.ms ("
                    + maxPollInterval + "): raise the interval or lower max.poll.records (backlog #0-96)");
        }
    }

    /** Closes the dead-letter producer, when it is the publisher's own ({@link #deadLetterTemplate}). */
    @Override
    public void destroy() throws Exception {
        if (kafkaTemplate != null && kafkaTemplate.getProducerFactory() instanceof DisposableBean factory) {
            factory.destroy();
        }
    }

    /**
     * A consumed record that can never be processed: copied to the
     * dead-letter topic and acknowledged once Kafka has the copy. If Kafka
     * does not take it within {@link #DEAD_LETTER_TIMEOUT}, the record is
     * not acknowledged but {@code nack}ed and read again, so it is never
     * lost; consumer lag shows the stall.
     *
     * @param tenantId the record's resolved tenant, or {@code null} when it
     *                 could not be resolved
     * @return whether the copy was stored and the record acknowledged
     */
    public boolean deadLetterThenAcknowledge(ConsumerRecord<String, String> record,
                                             String tenantId,
                                             String errorReason,
                                             Acknowledgment acknowledgment) {
        final String group = KafkaUtils.getConsumerGroupId();
        try {
            send(record.value(), record.topic(), record.partition(), record.offset(), tenantId, errorReason);
        } catch (DeadLetterNotStoredException e) {
            // The cause is the producer's (Kafka's), not the record's: its
            // stack trace is safe to log, once; while Kafka stays down the
            // same record comes back every REDELIVERY_DELAY (found in review).
            if (redeliveries.isFailing(group, record)) {
                log.warn("Record still could not be dead-lettered — read again in {}: topic={}, partition={}, "
                                + "offset={}, cause={}", REDELIVERY_DELAY, record.topic(), record.partition(),
                        record.offset(), AuditText.unexpected(rootCause(e)));
            } else {
                log.error("Record could not be dead-lettered — not acknowledged, read again in {}: "
                                + "topic={}, partition={}, offset={}", REDELIVERY_DELAY,
                        record.topic(), record.partition(), record.offset(), e);
            }
            redeliveries.failed(group, record);
            deadLetterFailedRedeliveries.increment();
            acknowledgment.nack(REDELIVERY_DELAY);
            return false;
        }
        redeliveries.settled(group, record);
        acknowledgment.acknowledge();
        return true;
    }

    /**
     * A consumed record that failed for a reason a later attempt may not
     * repeat ({@link KafkaFailures#isTransient}): not acknowledged, and the
     * partition is read again from it after {@link #REDELIVERY_DELAY}. The
     * records after it wait: their order is kept.
     *
     * <p>Once the record has been failing for longer than its redelivery
     * deadline it is dead-lettered instead, so it cannot hold its partition
     * for ever (see the class Javadoc).
     *
     * @param tenantId the record's resolved tenant, or {@code null}
     * @return whether the record was dead-lettered (and acknowledged) instead
     */
    public boolean redeliverLater(ConsumerRecord<String, String> record,
                                  String tenantId,
                                  Acknowledgment acknowledgment,
                                  Throwable cause) {
        final String group = KafkaUtils.getConsumerGroupId();
        final Duration failingFor = redeliveries.failed(group, record);
        if (failingFor.compareTo(redeliveries.deadline()) > 0) {
            log.error("Record has been failing for {}, longer than its redelivery deadline {} — dead-lettering it: "
                            + "topic={}, partition={}, offset={}, tenant={}, cause={}", failingFor,
                    redeliveries.deadline(), record.topic(), record.partition(), record.offset(), tenantId,
                    KafkaFailures.describe(cause));
            if (deadLetterThenAcknowledge(record, tenantId, "still failing after " + redeliveries.deadline()
                    + ": " + AuditText.unexpected(cause), acknowledgment)) {
                gaveUp.increment();
                return true;
            }
            return false;
        }
        // WARN without a stack trace: during an outage this repeats every
        // REDELIVERY_DELAY per partition, and the lag alerts tell the story.
        log.warn("Transient failure processing record — not acknowledged, read again in {}: "
                        + "topic={}, partition={}, offset={}, failing for {}, cause={}", REDELIVERY_DELAY,
                record.topic(), record.partition(), record.offset(), failingFor, KafkaFailures.describe(cause));
        transientRedeliveries.increment();
        acknowledgment.nack(REDELIVERY_DELAY);
        return false;
    }

    /**
     * A consumer's last {@code catch}: a transient failure is left for
     * redelivery ({@link #redeliverLater}), anything else is a record that
     * fails the same way every time and is dead-lettered
     * ({@link #deadLetterThenAcknowledge}), as backlog #47 decided.
     *
     * <p>Recorded and logged by the exception's type (and first frame in the
     * log), never its message (found in review): a parser's, a driver's or a
     * constraint's message can quote the record or a database row.
     */
    public void redeliverIfTransientElseDeadLetter(ConsumerRecord<String, String> record,
                                                   String tenantId,
                                                   Exception failure,
                                                   Acknowledgment acknowledgment) {
        if (KafkaFailures.isTransient(failure)) {
            redeliverLater(record, tenantId, acknowledgment, failure);
            return;
        }
        log.error("Unexpected error processing record (not a transient failure) — dead-lettering it: "
                        + "topic={}, partition={}, offset={}, tenant={}, error={}",
                record.topic(), record.partition(), record.offset(), tenantId, KafkaFailures.describe(failure));
        deadLetterThenAcknowledge(record, tenantId, AuditText.unexpected(failure), acknowledgment);
    }

    /** The innermost cause (bounded: a cause chain can be circular). */
    static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < 32 && cause.getCause() != null && cause.getCause() != cause; depth++) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Copies a payload that did not come from a consumed record (an HTTP
     * request's alert in ingestion-service) to the dead-letter topic and
     * returns once Kafka has it.
     *
     * @throws DeadLetterNotStoredException if Kafka did not take the copy
     *         within {@link #DEAD_LETTER_TIMEOUT}
     */
    public void publishAndWait(Object originalPayload,
                               String sourceTopic,
                               String tenantId,
                               String errorReason) {
        await(List.of(publishAsync(originalPayload, sourceTopic, tenantId, errorReason)), clock.instant());
    }

    /**
     * Starts copying a payload to the dead-letter topic without waiting, for
     * a caller with several copies to make and one deadline for all
     * ({@link #await}; found in review: ingestion-service waited for each
     * malformed alert's copy in turn). The send itself blocks at most
     * {@link #DEAD_LETTER_MAX_BLOCK} with {@link #deadLetterTemplate}; a
     * send that fails at once is a failed future, never a throw.
     */
    public CompletableFuture<Void> publishAsync(Object originalPayload,
                                                String sourceTopic,
                                                String tenantId,
                                                String errorReason) {
        String serialized;
        try {
            serialized = originalPayload instanceof String s
                    ? s
                    : objectMapper.writeValueAsString(originalPayload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize originalPayload for DLT — publishing raw toString() instead: {}",
                    AuditText.unexpected(e));
            serialized = String.valueOf(originalPayload);
        }
        try {
            return startSend(serialized, sourceTopic, null, null, tenantId, errorReason);
        } catch (DeadLetterNotStoredException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Waits for copies started at {@code startedAt} until
     * {@link #DEAD_LETTER_TIMEOUT} after it, all of them together.
     *
     * @throws DeadLetterNotStoredException at the first copy Kafka did not
     *         take, or when the time is up
     */
    public void await(Collection<CompletableFuture<Void>> copies, Instant startedAt) {
        final Instant deadline = startedAt.plus(DEAD_LETTER_TIMEOUT);
        for (final CompletableFuture<Void> copy : copies) {
            final long left = Math.max(0, Duration.between(clock.instant(), deadline).toMillis());
            try {
                copy.get(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DeadLetterNotStoredException("Interrupted while publishing to " + deadLetterTopic, e);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof DeadLetterNotStoredException notStored
                        ? notStored
                        : new DeadLetterNotStoredException("Dead-letter record not acknowledged by Kafka", e);
            } catch (TimeoutException e) {
                throw new DeadLetterNotStoredException(
                        "Dead-letter record not acknowledged by Kafka within " + DEAD_LETTER_TIMEOUT, e);
            }
        }
    }

    private void send(String originalPayload,
                      String sourceTopic,
                      Integer sourcePartition,
                      Long sourceOffset,
                      String tenantId,
                      String errorReason) {
        final Instant started = clock.instant();
        await(List.of(startSend(originalPayload, sourceTopic, sourcePartition, sourceOffset, tenantId,
                errorReason)), started);
    }

    /** @throws DeadLetterNotStoredException if the send failed before a future existed */
    private CompletableFuture<Void> startSend(String originalPayload,
                                              String sourceTopic,
                                              Integer sourcePartition,
                                              Long sourceOffset,
                                              String tenantId,
                                              String errorReason) {
        final String tenant = TenantIds.isValid(tenantId) ? tenantId : null;
        final String reason = AuditText.error(errorReason);
        final String dltPayload = buildDltPayload(originalPayload, sourceTopic, sourcePartition, sourceOffset,
                tenant, reason);
        final CompletableFuture<SendResult<String, String>> sent;
        try {
            sent = kafkaTemplate.send(record(tenant, dltPayload));
        } catch (RuntimeException e) {
            // KafkaTemplate.send itself throws when the producer cannot get
            // metadata within max.block.ms.
            throw new DeadLetterNotStoredException("Dead-letter record not accepted by the producer", e);
        }
        return sent.thenAccept(result -> log.info("Message published to DLT: topic={}, partition={}, offset={}, "
                        + "sourceTopic={}, tenant={}, reason={}", deadLetterTopic,
                result.getRecordMetadata().partition(), result.getRecordMetadata().offset(), sourceTopic,
                tenant, reason));
    }

    /** Headed by the tenant only when there is a valid one (see the class Javadoc). */
    private ProducerRecord<String, String> record(String tenant, String dltPayload) {
        final String key = sourceService + ":" + (tenant != null ? tenant : NO_TENANT);
        return tenant != null
                ? TenantRecords.forTenant(deadLetterTopic, key, dltPayload, tenant)
                : TenantRecords.withoutTenant(deadLetterTopic, key, dltPayload);
    }

    private String buildDltPayload(String originalPayload,
                                   String sourceTopic,
                                   Integer sourcePartition,
                                   Long sourceOffset,
                                   String tenantId,
                                   String errorReason) {
        final ObjectNode node = objectMapper.createObjectNode();
        node.put("sourceService", sourceService);
        node.put("sourceTopic", sourceTopic);
        if (sourcePartition != null) {
            node.put("sourcePartition", sourcePartition);
            node.put("sourceOffset", sourceOffset);
        }
        node.put("tenantId", tenantId);
        node.put("errorReason", errorReason);
        node.put("failedAt", clock.instant().toString());
        final byte[] original = originalPayload == null
                ? new byte[0] : originalPayload.getBytes(StandardCharsets.UTF_8);
        if (original.length > MAX_ORIGINAL_PAYLOAD_BYTES) {
            node.put("originalPayload", truncateUtf8(original, MAX_ORIGINAL_PAYLOAD_BYTES));
            node.put("originalPayloadTruncated", true);
            node.put("originalPayloadBytes", original.length);
        } else {
            node.put("originalPayload", originalPayload);
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // Unreachable for a tree of strings and numbers; a deterministic
            // failure here would otherwise nack the record for ever (found in
            // review), so the copy goes without the payload instead.
            log.error("Dead-letter envelope could not be serialized — storing it without the original payload: {}",
                    AuditText.unexpected(e));
            node.remove("originalPayload");
            node.put("originalPayloadUnavailable", true);
            return node.toString();
        }
    }

    /** The longest prefix of {@code utf8} within {@code maxBytes}, never cutting a character. */
    static String truncateUtf8(byte[] utf8, int maxBytes) {
        int end = Math.min(maxBytes, utf8.length);
        // Step back over continuation bytes (10xxxxxx) to a character's start.
        while (end > 0 && end < utf8.length && (utf8[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(utf8, 0, end, StandardCharsets.UTF_8);
    }
}
