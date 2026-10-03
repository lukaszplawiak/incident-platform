package com.incidentplatform.shared.kafka;

import com.incidentplatform.shared.security.TenantIds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Spring Kafka {@link RecordInterceptor} that provides three cross-cutting
 * concerns for every Kafka record processed by a {@code @KafkaListener}:
 *
 * <h2>1. MDC enrichment</h2>
 * Sets SLF4J MDC keys before the listener executes so every log line emitted
 * during record processing automatically carries:
 * <ul>
 *   <li>{@code tenantId} — from the {@code X-Tenant-Id} header (or
 *       {@code "unknown"} if absent).
 *   <li>{@code kafkaMessageId} — {@code topic-partition-offset}, a stable
 *       identifier equivalent to {@code requestId} for HTTP requests.
 * </ul>
 * MDC is cleared in {@link #success} and {@link #failure} so keys never
 * leak across records on the same listener thread.
 *
 * <h2>2. Structured observability logging</h2>
 * Logs a single {@code DEBUG} line per record at entry containing:
 * topic, partition, offset, tenant, payload size in bytes, producer
 * timestamp, and consumer lag (time between producer publish and now).
 *
 * <h2>3. Micrometer metrics</h2>
 * Increments {@code kafka.records.received} counter tagged with
 * {@code topic}. Measures total processing time
 * via {@code kafka.record.processing.duration} Timer.
 *
 * <h2>Changed (backlog #0-92): no {@code tenant} tag</h2>
 * The counter used to carry a {@code tenant} tag from the record's header,
 * read before the consumer resolves (and possibly refuses) the record's
 * tenant: a producer could open a new series per value, unbounded (found in
 * the end-to-end test of #0-92: a forged but well-formed header counted
 * under the tenant it claimed). Kafka has no ACLs yet (#0-66), so a value
 * from a record is not a label; per-tenant volume belongs to a metric
 * recorded after resolution, which no dashboard asked for.
 *
 * <h2>Why RecordInterceptor and not ConsumerInterceptor</h2>
 * {@link org.apache.kafka.clients.consumer.ConsumerInterceptor} runs on the
 * Kafka client poll thread. {@link RecordInterceptor} is called by Spring Kafka
 * on the same thread that executes the {@code @KafkaListener} method — making
 * MDC and timing work correctly.
 *
 * <h2>Spring Kafka API — intercept/success/failure</h2>
 * Spring Kafka 3.x replaced the single {@code afterRecord()} callback with
 * separate {@link #success} and {@link #failure} hooks, each receiving the
 * Kafka {@link Consumer}. MDC cleanup and timer recording are done in both
 * hooks so they always run regardless of outcome.
 *
 * <h2>OpenTelemetry / distributed tracing</h2>
 * TODO: When OpenTelemetry is added (micrometer-tracing-bridge-otel +
 *  opentelemetry-exporter-otlp), extract the W3C {@code traceparent} header
 *  in {@link #intercept} and start a child span so the trace ID propagates
 *  from HTTP request → Kafka publish → Kafka consume → DB save end-to-end.
 *  The {@code kafkaMessageId} MDC key serves the same correlation purpose
 *  until OpenTelemetry is wired in.
 */
public class TenantKafkaRecordInterceptor<K, V> implements RecordInterceptor<K, V> {

    private static final Logger log =
            LoggerFactory.getLogger(TenantKafkaRecordInterceptor.class);

    static final String MDC_TENANT_ID  = "tenantId";
    static final String MDC_MESSAGE_ID = "kafkaMessageId";

    /** MDC and metric value for a record without a tenant header (backlog #0-92). */
    static final String MISSING = "_missing";
    /** MDC and metric value for a record whose tenant header is not a valid tenant id (backlog #0-92). */
    static final String INVALID = "_invalid";

    private static final String MDC_START_NANOS = "_kafkaStartNanos";

    private final MeterRegistry meterRegistry;

    // ConcurrentHashMap — multiple listener threads may call intercept() concurrently
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final Map<String, Timer>   timerCache   = new ConcurrentHashMap<>();

    public TenantKafkaRecordInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ── RecordInterceptor — required abstract method ──────────────────────────

    /**
     * Called on the listener thread before the {@code @KafkaListener} method.
     * Sets MDC, logs observability event, starts processing timer.
     */
    @Override
    public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record,
                                          Consumer<K, V> consumer) {
        final String tenant    = extractTenantHeader(record);
        final String messageId = buildMessageId(record);

        // ── MDC enrichment ────────────────────────────────────────────────
        MDC.put(MDC_TENANT_ID,  tenant);
        MDC.put(MDC_MESSAGE_ID, messageId);
        MDC.put(MDC_START_NANOS, String.valueOf(System.nanoTime()));

        // ── Observability log ─────────────────────────────────────────────
        // Only when DEBUG is on: measuring the payload copies it, per record
        // (found in review).
        if (log.isDebugEnabled()) {
            final long producerTimestampMs = record.timestamp();
            final int payloadBytes = record.value() instanceof String s
                    ? s.getBytes(StandardCharsets.UTF_8).length
                    : -1;
            log.debug("KAFKA_RECORD_RECEIVED topic={} partition={} offset={} " +
                            "tenant={} payloadBytes={} producerTimestamp={} lagMs={}",
                    record.topic(), record.partition(), record.offset(),
                    tenant, payloadBytes,
                    Instant.ofEpochMilli(producerTimestampMs),
                    System.currentTimeMillis() - producerTimestampMs);
        }

        // ── Metrics ───────────────────────────────────────────────────────
        receivedCounter(record.topic()).increment();

        return record;
    }

    // ── RecordInterceptor — default hooks (cleanup) ───────────────────────────

    /**
     * Called after the listener exits normally. Records processing duration
     * and clears MDC.
     */
    @Override
    public void success(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        recordDurationAndClearMdc(record.topic());
    }

    /**
     * Called after the listener throws an exception. Records processing duration
     * and clears MDC — ensuring cleanup even on failure.
     */
    @Override
    public void failure(ConsumerRecord<K, V> record,
                        Exception exception,
                        Consumer<K, V> consumer) {
        recordDurationAndClearMdc(record.topic());
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void recordDurationAndClearMdc(String topic) {
        final String startNanosStr = MDC.get(MDC_START_NANOS);
        if (startNanosStr != null) {
            final long durationNanos =
                    System.nanoTime() - Long.parseLong(startNanosStr);
            processingTimer(topic).record(durationNanos, TimeUnit.NANOSECONDS);
        }
        MDC.remove(MDC_TENANT_ID);
        MDC.remove(MDC_MESSAGE_ID);
        MDC.remove(MDC_START_NANOS);
    }

    /**
     * The tenant for the MDC, read from the header before the consumer parses
     * the payload and resolves (or refuses) the record's tenant.
     *
     * <h2>Changed (backlog #0-92): only a valid tenant id goes in</h2>
     * The raw header used to go into the MDC (so into every log line of the
     * record's processing) and into a metric tag (removed, see the class
     * Javadoc). Now a header that is not a valid tenant id is
     * {@value #INVALID}, and a missing one {@value #MISSING}: neither is a
     * valid tenant id (an underscore is not allowed in one), so no real tenant
     * can be mistaken for them, which the earlier {@code "unknown"} could be.
     * The consumer then refuses an invalid header, or takes the payload's
     * tenant ({@code TenantKafkaRecordResolver}).
     */
    private String extractTenantHeader(ConsumerRecord<K, V> record) {
        final Header header = record.headers()
                .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);
        if (header == null) {
            return MISSING;
        }
        final String value = new String(header.value(), StandardCharsets.UTF_8);
        return TenantIds.isValid(value) ? value : INVALID;
    }

    private String buildMessageId(ConsumerRecord<K, V> record) {
        return record.topic() + "-" + record.partition() + "-" + record.offset();
    }

    private Counter receivedCounter(String topic) {
        return counterCache.computeIfAbsent(topic, k ->
                Counter.builder("kafka.records.received")
                        .description("Number of Kafka records received per topic")
                        .tag("topic", topic)
                        .register(meterRegistry));
    }

    private Timer processingTimer(String topic) {
        return timerCache.computeIfAbsent(topic, k ->
                Timer.builder("kafka.record.processing.duration")
                        .description("Total time to process a single Kafka record")
                        .tag("topic", topic)
                        .register(meterRegistry));
    }
}