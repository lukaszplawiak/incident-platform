package com.incidentplatform.shared.kafka;

import com.incidentplatform.shared.security.TenantIds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Checks that every record a service produces carries a valid
 * {@value #TENANT_ID_HEADER} header (backlog #0-91). It writes nothing.
 *
 * <h2>Changed (backlog #0-91): a check, not a second writer</h2>
 * This interceptor used to stamp the header from the thread's
 * {@code TenantContext} (and, after #0-88, to leave a header the sender had
 * set alone). A record's tenant then had two possible sources, the payload
 * the sender built and whatever thread-local happened to be set, and incident
 * events had a header only because their senders set that context first. Every
 * sender now builds its records through {@link TenantRecords}, from the
 * payload's tenant, so a record without the header, or with an invalid one, is
 * a sender's bug: it is counted in {@code kafka.records.produced.tenant.invalid}
 * (tag {@code reason}: {@code missing} or {@code invalid}, both registered at
 * zero so the first one shows; alert {@code KafkaRecordsWithoutTenant}) and
 * logged, without the header's value, and sent as it is (the consumer refuses
 * it, missing or invalid header alike).
 *
 * <p>A dead-letter record ({@value #DEAD_LETTER_SUFFIX} topics) may have no
 * tenant: one is written when the failed record's tenant could not be resolved
 * at all ({@link DeadLetterPublisher}). It is not counted when it says so
 * ({@link TenantRecords#withoutTenant}'s marker); the topic name alone no longer
 * exempts a record (found in review).
 *
 * <p>Kafka creates this class itself, not Spring, so its counters go to
 * Micrometer's global registry, to which Spring Boot adds its own registries
 * ({@code management.metrics.use-global-registry}, on by default).
 */
public class TenantKafkaProducerInterceptor<K, V>
        implements ProducerInterceptor<K, V> {

    private static final Logger log =
            LoggerFactory.getLogger(TenantKafkaProducerInterceptor.class);

    public static final String TENANT_ID_HEADER = "X-Tenant-Id";

    /** The platform's dead-letter topics all end so (alerts.dead-letter, incidents.dead-letter, ...). */
    static final String DEAD_LETTER_SUFFIX = ".dead-letter";

    static final String COUNTER = "kafka.records.produced.tenant.invalid";

    private static boolean unresolvedDeadLetter(ProducerRecord<?, ?> record) {
        return record.topic().endsWith(DEAD_LETTER_SUFFIX)
                && record.headers().lastHeader(TenantRecords.TENANT_UNRESOLVED_HEADER) != null;
    }

    private Counter missing;
    private Counter invalid;

    public TenantKafkaProducerInterceptor() {
        registerCounters();
    }

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        final Header header = record.headers().lastHeader(TENANT_ID_HEADER);
        if (header == null) {
            if (!unresolvedDeadLetter(record)) {
                missing.increment();
                log.warn("Kafka record to topic {} has no {} header: its sender does not build it "
                        + "through TenantRecords (backlog #0-91)", record.topic(), TENANT_ID_HEADER);
            }
            return record;
        }
        if (!TenantIds.isValid(new String(header.value(), StandardCharsets.UTF_8))) {
            invalid.increment();
            log.warn("Kafka record to topic {} has an invalid {} header (value not logged)",
                    record.topic(), TENANT_ID_HEADER);
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        if (exception != null) {
            log.error("Kafka producer failed to send message to topic: {}, partition: {}",
                    metadata != null ? metadata.topic() : "unknown",
                    metadata != null ? metadata.partition() : "unknown",
                    exception);
        } else {
            log.debug("Kafka message acknowledged, topic: {}, partition: {}, offset: {}",
                    metadata.topic(), metadata.partition(), metadata.offset());
        }
    }

    @Override
    public void close() {
        log.info("TenantKafkaProducerInterceptor closed");
    }

    @Override
    public void configure(Map<String, ?> configs) {
        log.info("TenantKafkaProducerInterceptor configured");
    }

    private void registerCounters() {
        missing = Counter.builder(COUNTER)
                .description("Records produced without a valid tenant header (backlog #0-91)")
                .tag("reason", "missing")
                .register(Metrics.globalRegistry);
        invalid = Counter.builder(COUNTER)
                .description("Records produced without a valid tenant header (backlog #0-91)")
                .tag("reason", "invalid")
                .register(Metrics.globalRegistry);
    }
}
