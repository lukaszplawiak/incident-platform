package com.incidentplatform.shared.kafka;

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
 * <h2>3. Per-tenant Micrometer metrics</h2>
 * Increments {@code kafka.records.received} counter tagged with
 * {@code topic} and {@code tenant}. Measures total processing time
 * via {@code kafka.record.processing.duration} Timer.
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
        final long producerTimestampMs = record.timestamp();
        final long lagMs = System.currentTimeMillis() - producerTimestampMs;
        final int payloadBytes = record.value() instanceof String s
                ? s.getBytes(StandardCharsets.UTF_8).length
                : -1;

        log.debug("KAFKA_RECORD_RECEIVED topic={} partition={} offset={} " +
                        "tenant={} payloadBytes={} producerTimestamp={} lagMs={}",
                record.topic(), record.partition(), record.offset(),
                tenant, payloadBytes,
                Instant.ofEpochMilli(producerTimestampMs), lagMs);

        // ── Metrics ───────────────────────────────────────────────────────
        receivedCounter(record.topic(), tenant).increment();

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

    private String extractTenantHeader(ConsumerRecord<K, V> record) {
        final Header header = record.headers()
                .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);
        if (header != null) {
            final String value = new String(header.value(), StandardCharsets.UTF_8);
            if (!value.isBlank()) {
                return value;
            }
        }
        // "unknown" placeholder — consumer's extractTenantId() resolves the real
        // value from the payload or routes to DLT after intercept() returns.
        return "unknown";
    }

    private String buildMessageId(ConsumerRecord<K, V> record) {
        return record.topic() + "-" + record.partition() + "-" + record.offset();
    }

    private Counter receivedCounter(String topic, String tenant) {
        return counterCache.computeIfAbsent(topic + ":" + tenant, k ->
                Counter.builder("kafka.records.received")
                        .description("Number of Kafka records received per topic and tenant")
                        .tag("topic", topic)
                        .tag("tenant", tenant)
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