package com.incidentplatform.shared.kafka;

import com.incidentplatform.shared.security.InvalidTenantIdException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantRecords (backlog #0-91)")
class TenantRecordsTest {

    @Test
    @DisplayName("builds the record with its key and value, and one tenant header")
    void buildsRecord() {
        final ProducerRecord<String, String> record =
                TenantRecords.forTenant("incidents.lifecycle", "incident-1", "{}", "acme");

        assertThat(record.topic()).isEqualTo("incidents.lifecycle");
        assertThat(record.key()).isEqualTo("incident-1");
        assertThat(record.value()).isEqualTo("{}");
        assertThat(record.headers().headers(TenantKafkaProducerInterceptor.TENANT_ID_HEADER))
                .singleElement()
                .satisfies(h -> assertThat(new String(h.value(), StandardCharsets.UTF_8)).isEqualTo("acme"));
    }

    @Test
    @DisplayName("refuses a record without a valid tenant")
    void refusesInvalidTenant() {
        assertThatThrownBy(() -> TenantRecords.forTenant("t", "k", "v", null))
                .isInstanceOf(InvalidTenantIdException.class);
        assertThatThrownBy(() -> TenantRecords.forTenant("t", "k", "v", "evil\nline"))
                .isInstanceOf(InvalidTenantIdException.class);
    }

    @Test
    @DisplayName("a record without a tenant only for a dead-letter topic, marked as such (found in review)")
    void withoutTenantOnlyForDeadLetter() {
        final ProducerRecord<String, String> record = TenantRecords.withoutTenant("alerts.dead-letter", "k", "v");

        assertThat(record.headers().lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER)).isNull();
        assertThat(record.headers().lastHeader(TenantRecords.TENANT_UNRESOLVED_HEADER)).isNotNull();
        assertThatThrownBy(() -> TenantRecords.withoutTenant("alerts.raw", "k", "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
