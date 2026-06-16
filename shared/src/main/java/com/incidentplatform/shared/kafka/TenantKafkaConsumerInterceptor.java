package com.incidentplatform.shared.kafka;

import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Kafka consumer interceptor that validates the presence of the
 * {@code X-Tenant-Id} header on every incoming record for observability
 * purposes.
 *
 * <h2>What this interceptor does</h2>
 * <ul>
 *   <li>Logs a {@code WARN} for every record missing the {@code X-Tenant-Id}
 *       header — surfacing producer-side contract violations early, before the
 *       record reaches any consumer.
 *   <li>Logs a {@code DEBUG} confirmation when the header is present.
 *   <li>Always returns the full record batch unchanged — no records are
 *       filtered, rejected, or modified.
 * </ul>
 *
 * <h2>What this interceptor does NOT do</h2>
 * <ul>
 *   <li><b>Does not set {@code TenantContext} or MDC.</b> This interceptor
 *       runs on the Kafka client <em>poll thread</em>, not on the Spring
 *       listener thread that executes {@code @KafkaListener} methods.
 *       {@code ThreadLocal}-based state (MDC, {@code TenantContext}) set here
 *       would not be visible to the listener and would leak across unrelated
 *       records in the same poll batch.
 *       MDC enrichment is handled by {@link TenantKafkaRecordInterceptor},
 *       which runs on the correct listener thread.
 *   <li><b>Does not reject or route records.</b> Dropping records here would
 *       bypass the dead-letter topic mechanism and lose messages permanently.
 *       Records with a missing or invalid tenant are handled as poison pills
 *       by each individual consumer via {@code extractTenantId()}.
 * </ul>
 *
 * <h2>Division of responsibility</h2>
 * <table border="1">
 *   <tr><th>Component</th><th>Responsibility</th><th>Thread</th></tr>
 *   <tr>
 *     <td>{@code TenantKafkaConsumerInterceptor} (this class)</td>
 *     <td>Batch-level observability — WARN on missing header</td>
 *     <td>Kafka poll thread</td>
 *   </tr>
 *   <tr>
 *     <td>{@link TenantKafkaRecordInterceptor}</td>
 *     <td>MDC enrichment, structured log, per-tenant metrics, processing timer</td>
 *     <td>Spring listener thread</td>
 *   </tr>
 *   <tr>
 *     <td>Each {@code @KafkaListener} consumer</td>
 *     <td>Enforcement — header → payload fallback → poison pill via
 *         {@code extractTenantId()}</td>
 *     <td>Spring listener thread</td>
 *   </tr>
 * </table>
 *
 * <p>Registered via {@code spring.kafka.consumer.properties.interceptor.classes}
 * in each service's {@code application.yml}.
 */
public class TenantKafkaConsumerInterceptor<K, V>
        implements ConsumerInterceptor<K, V> {

    private static final Logger log =
            LoggerFactory.getLogger(TenantKafkaConsumerInterceptor.class);

    @Override
    public ConsumerRecords<K, V> onConsume(ConsumerRecords<K, V> records) {
        if (records.isEmpty()) {
            return records;
        }

        // Validate that every record in the batch has a tenant header.
        // TenantContext and MDC are NOT set here — see class Javadoc for explanation.
        for (var record : records) {
            final Header tenantHeader = record.headers()
                    .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);

            if (tenantHeader == null) {
                log.warn("Missing X-Tenant-Id header in Kafka record — " +
                                "record will be processed without tenant header validation. " +
                                "topic={}, partition={}, offset={}",
                        record.topic(), record.partition(), record.offset());
            } else {
                log.debug("Validated tenant header present: topic={}, " +
                                "partition={}, offset={}",
                        record.topic(), record.partition(), record.offset());
            }
        }

        return records;
    }

    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        log.debug("Kafka batch committed, partitions: {}", offsets.keySet());
    }

    @Override
    public void close() {
        log.info("TenantKafkaConsumerInterceptor closed");
    }

    @Override
    public void configure(Map<String, ?> configs) {
        log.info("TenantKafkaConsumerInterceptor configured");
    }
}