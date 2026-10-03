package com.incidentplatform.shared.kafka;

import com.incidentplatform.shared.security.TenantIds;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;

/**
 * Builds every Kafka record the platform produces for a tenant (backlog
 * #0-91): the tenant is a required argument, checked against
 * {@link TenantIds}, and written to the {@value TenantKafkaProducerInterceptor#TENANT_ID_HEADER}
 * header here and nowhere else.
 *
 * <h2>Why (backlog #0-91): one writer, from the record's own tenant</h2>
 * The tenant comes from authentication, travels in the data (the row's
 * {@code tenant_id}, the payload's {@code tenantId}), and the header is a copy
 * of the payload's, for code that reads a record without parsing it (the
 * record interceptor's MDC and metrics, dead-letter tooling). It used to have
 * two writers: senders that knew the tenant set it, and
 * {@link TenantKafkaProducerInterceptor} added it from the thread's
 * {@code TenantContext}; incident events had no header of their own at all and
 * got one only because their two senders happened to set that context first.
 * Now each sender passes the tenant of the payload it sends, and the
 * interceptor only checks.
 */
public final class TenantRecords {

    /** Marks a dead-letter record written without a tenant on purpose ({@link #withoutTenant}). */
    public static final String TENANT_UNRESOLVED_HEADER = "X-Tenant-Unresolved";

    private TenantRecords() {
    }

    /**
     * A record for {@code topic} carrying {@code tenantId} in its tenant
     * header.
     *
     * @param key the record's key, chosen by the sender for ordering (the
     *            tenant, or an incident id to keep one incident's events in order)
     * @throws com.incidentplatform.shared.security.InvalidTenantIdException if
     *         {@code tenantId} is not a valid tenant id (the message does not
     *         quote it)
     */
    public static ProducerRecord<String, String> forTenant(String topic, String key, String value,
                                                           String tenantId) {
        final ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        record.headers().add(new RecordHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                TenantIds.requireValid(tenantId).getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    /**
     * A dead-letter record whose failed record's tenant could not be resolved:
     * no tenant header, and the {@value #TENANT_UNRESOLVED_HEADER} marker that
     * says so on purpose. {@link TenantKafkaProducerInterceptor} counts every
     * other record without a tenant header, and this one only off a dead-letter
     * topic (found in review: it used to exempt any record whose topic ended in
     * {@code .dead-letter}, so a sender's bug there went uncounted).
     *
     * @throws IllegalArgumentException if {@code topic} is not a dead-letter topic
     */
    public static ProducerRecord<String, String> withoutTenant(String topic, String key, String value) {
        if (topic == null || !topic.endsWith(TenantKafkaProducerInterceptor.DEAD_LETTER_SUFFIX)) {
            throw new IllegalArgumentException("Only a dead-letter record may have no tenant");
        }
        final ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        record.headers().add(new RecordHeader(TENANT_UNRESOLVED_HEADER, "true".getBytes(StandardCharsets.UTF_8)));
        return record;
    }
}
