package com.incidentplatform.shared.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The consumer side of a record's tenant (backlog #0-92): the payload's tenant
 * is the record's, the header a copy that must be there and agree; nothing is
 * quoted.
 */
@DisplayName("TenantKafkaRecordResolver (backlog #0-92)")
class TenantKafkaRecordResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TenantKafkaRecordResolver resolver = new TenantKafkaRecordResolver(objectMapper, meters);

    private static ConsumerRecord<String, String> record(String payload, String header) {
        final ConsumerRecord<String, String> record = new ConsumerRecord<>("alerts.raw", 1, 7L, "k", payload);
        if (header != null) {
            record.headers().add(new RecordHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    header.getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    private String resolve(String payload, String header) {
        final ConsumerRecord<String, String> record = record(payload, header);
        return resolver.extractTenantId(record, resolver.parseJson(payload));
    }

    private double rejected(TenantResolutionException.Reason reason) {
        return meters.counter(TenantKafkaRecordResolver.REJECTED_COUNTER, "reason", reason.tag()).count();
    }

    @Test
    @DisplayName("every refusal reason's counter exists at zero before the first refusal")
    void countersRegisteredAtZero() {
        assertThat(meters.find(TenantKafkaRecordResolver.REJECTED_COUNTER).counters())
                .hasSize(TenantResolutionException.Reason.values().length)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    @DisplayName("header and payload agree: the tenant")
    void agreeing() {
        assertThat(resolve("{\"tenantId\":\"acme\"}", "acme")).isEqualTo("acme");
    }

    @Test
    @DisplayName("no header: refused and counted (header_missing), even with a valid payload tenant")
    void noHeader() {
        assertThatThrownBy(() -> resolve("{\"tenantId\":\"acme\"}", null))
                .isInstanceOfSatisfying(TenantResolutionException.class, e -> {
                    assertThat(e.reason()).isEqualTo(TenantResolutionException.Reason.HEADER_MISSING);
                    assertThat(e.getMessage()).doesNotContain("acme");
                });
        assertThat(rejected(TenantResolutionException.Reason.HEADER_MISSING)).isEqualTo(1.0);
        assertThat(meters.counter(TenantKafkaRecordResolver.REJECTED_COUNTER, "reason", "header_missing").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a header naming another tenant than the payload is refused and counted (mismatch)")
    void mismatch() {
        assertThatThrownBy(() -> resolve("{\"tenantId\":\"acme\"}", "globex"))
                .isInstanceOfSatisfying(TenantResolutionException.class, e -> {
                    assertThat(e.reason()).isEqualTo(TenantResolutionException.Reason.MISMATCH);
                    assertThat(e.getMessage()).doesNotContain("acme").doesNotContain("globex");
                });
        assertThat(rejected(TenantResolutionException.Reason.MISMATCH)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an invalid header is refused even when the payload is valid, never quoted")
    void invalidHeader() {
        assertThatThrownBy(() -> resolve("{\"tenantId\":\"acme\"}", "acme\nFAKE LOG LINE"))
                .isInstanceOfSatisfying(TenantResolutionException.class, e -> {
                    assertThat(e.reason()).isEqualTo(TenantResolutionException.Reason.INVALID);
                    assertThat(e.getMessage()).doesNotContain("FAKE");
                });
        assertThat(rejected(TenantResolutionException.Reason.INVALID)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a header differing from the payload only in letter case is refused, not normalised "
            + "(found in review)")
    void caseDifferentHeader() {
        assertThatThrownBy(() -> resolve("{\"tenantId\":\"tenant-a\"}", "Tenant-A"))
                .isInstanceOfSatisfying(TenantResolutionException.class, e ->
                        assertThat(e.reason()).isEqualTo(TenantResolutionException.Reason.INVALID));
        assertThatThrownBy(() -> resolve("{\"tenantId\":\"Tenant-A\"}", "tenant-a"))
                .isInstanceOfSatisfying(TenantResolutionException.class, e ->
                        assertThat(e.reason()).isEqualTo(TenantResolutionException.Reason.INVALID));
    }

    @Test
    @DisplayName("an invalid payload tenant is refused, whatever the header says")
    void invalidPayloadTenant() {
        assertThatThrownBy(() -> resolve("{\"tenantId\":\"Acme Corp\"}", "Acme Corp"))
                .isInstanceOfSatisfying(TenantResolutionException.class, e ->
                        assertThat(e.reason()).isEqualTo(TenantResolutionException.Reason.INVALID));
    }

    @Test
    @DisplayName("a payload without a tenant is refused even with a valid header (the payload is the truth)")
    void missingPayloadTenant() {
        assertThatThrownBy(() -> resolve("{\"title\":\"x\"}", "acme"))
                .isInstanceOfSatisfying(TenantResolutionException.class, e ->
                        assertThat(e.reason()).isEqualTo(TenantResolutionException.Reason.MISSING));
        assertThatThrownBy(() -> resolve("{\"tenantId\":42}", "acme"))
                .isInstanceOfSatisfying(TenantResolutionException.class, e ->
                        assertThat(e.reason()).isEqualTo(TenantResolutionException.Reason.MISSING));
        assertThat(rejected(TenantResolutionException.Reason.MISSING)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("unparseable JSON: an IllegalArgumentException that does not quote the payload, typed as such "
            + "so its message may go into a dead-letter reason (backlog #0-96)")
    void unparseable() {
        assertThatThrownBy(() -> resolver.parseJson("{\"tenantId\": \"acme\nFAKE"))
                .isInstanceOf(UnreadableRecordException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("FAKE").doesNotContain("acme"));
    }

    @Test
    @DisplayName("trustedTenantOrNull: the tenant by the same rule, else null — never thrown, never counted "
            + "(backlog #0-96)")
    void trustedTenantOrNull() {
        assertThat(resolver.trustedTenantOrNull(record("{\"tenantId\":\"acme\"}", "acme"))).isEqualTo("acme");
        assertThat(resolver.trustedTenantOrNull(record("{\"tenantId\":\"acme\"}", null))).isNull();
        assertThat(resolver.trustedTenantOrNull(record("{\"tenantId\":\"acme\"}", "globex"))).isNull();
        assertThat(resolver.trustedTenantOrNull(record("{\"tenantId\":\"Not A Slug\"}", "Not A Slug"))).isNull();
        assertThat(resolver.trustedTenantOrNull(record("{}", "acme"))).isNull();
        assertThat(resolver.trustedTenantOrNull(record("not json", "acme"))).isNull();
        assertThat(resolver.trustedTenantOrNull(record(null, "acme"))).isNull();
        assertThat(resolver.trustedTenantOrNull(record("", "acme"))).isNull();
        assertThat(resolver.trustedTenantOrNull(record("   ", "acme"))).isNull();
        assertThat(meters.find(TenantKafkaRecordResolver.REJECTED_COUNTER).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }
}
