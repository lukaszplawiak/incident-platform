package com.incidentplatform.shared.dto;

import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.SourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UnifiedAlertDto")
class UnifiedAlertDtoTest {

    @Test
    @DisplayName("should normalize source to lowercase")
    void shouldNormalizeSourceToLowercase() {
        // when
        final UnifiedAlertDto dto = buildDto("Prometheus", Severity.CRITICAL, "title");

        // then
        assertThat(dto.source()).isEqualTo("prometheus");
    }

    @Test
    @DisplayName("should trim title whitespace")
    void shouldTrimTitle() {
        // when
        final UnifiedAlertDto dto = buildDto("prometheus", Severity.HIGH, "  High CPU  ");

        // then
        assertThat(dto.title()).isEqualTo("High CPU");
    }

    @Test
    @DisplayName("should generate alertId when null")
    void shouldGenerateAlertIdWhenNull() {
        // when
        final UnifiedAlertDto dto = new UnifiedAlertDto(
                null, "tenant", "prometheus", SourceType.OPS,
                Severity.HIGH, "title", null, Instant.now(),
                "prometheus:title:unknown", null
        );

        // then
        assertThat(dto.alertId()).isNotNull();
    }

    @Test
    @DisplayName("should make metadata immutable via defensive copy")
    void shouldMakeMetadataImmutable() {
        // given
        final Map<String, String> mutableMetadata = new HashMap<>();
        mutableMetadata.put("key", "value");

        final UnifiedAlertDto dto = new UnifiedAlertDto(
                UUID.randomUUID(), "tenant", "prometheus", SourceType.OPS,
                Severity.HIGH, "title", null, Instant.now(),
                "prometheus:title:unknown", mutableMetadata
        );

        // when
        mutableMetadata.put("newKey", "newValue");

        // then
        assertThat(dto.metadata()).doesNotContainKey("newKey");
    }

    @Test
    @DisplayName("should throw when trying to modify metadata")
    void shouldThrowWhenModifyingMetadata() {
        // given
        final UnifiedAlertDto dto = buildDto("prometheus", Severity.HIGH, "title");

        // then
        assertThatThrownBy(() -> dto.metadata().put("key", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("isSecurityAlert should return true for SECURITY source type")
    void isSecurityAlertShouldReturnTrueForSecurity() {
        // given
        final UnifiedAlertDto dto = new UnifiedAlertDto(
                UUID.randomUUID(), "tenant", "wazuh", SourceType.SECURITY,
                Severity.HIGH, "Brute force", null, Instant.now(),
                "wazuh:5551:003", Map.of()
        );

        // then
        assertThat(dto.isSecurityAlert()).isTrue();
        assertThat(dto.isCritical()).isFalse();
    }

    @Test
    @DisplayName("isSecurityAlert should return false for OPS source type")
    void isSecurityAlertShouldReturnFalseForOps() {
        // given
        final UnifiedAlertDto dto = buildDto("prometheus", Severity.HIGH, "title");

        // then
        assertThat(dto.isSecurityAlert()).isFalse();
    }

    @Test
    @DisplayName("isCritical should return true for CRITICAL severity")
    void isCriticalShouldReturnTrue() {
        // given
        final UnifiedAlertDto dto = buildDto("prometheus", Severity.CRITICAL, "title");

        // then
        assertThat(dto.isCritical()).isTrue();
    }

    @Test
    @DisplayName("isCritical should return false for non-CRITICAL severity")
    void isCriticalShouldReturnFalse() {
        // given
        final UnifiedAlertDto dto = buildDto("prometheus", Severity.HIGH, "title");

        // then
        assertThat(dto.isCritical()).isFalse();
    }

    @Test
    @DisplayName("two DTOs with same data should be equal (record equality)")
    void dtosShouldBeEqualWhenSameData() {
        // given
        final UUID id = UUID.randomUUID();
        final Instant now = Instant.now();

        final UnifiedAlertDto dto1 = new UnifiedAlertDto(
                id, "tenant", "prometheus", SourceType.OPS,
                Severity.HIGH, "title", null, now,
                "prometheus:title:server", Map.of()
        );
        final UnifiedAlertDto dto2 = new UnifiedAlertDto(
                id, "tenant", "prometheus", SourceType.OPS,
                Severity.HIGH, "title", null, now,
                "prometheus:title:server", Map.of()
        );

        // then
        assertThat(dto1).isEqualTo(dto2);
        assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
    }

    @Test
    @DisplayName("Severity.fromString should parse case-insensitive")
    void severityFromStringShouldBeCaseInsensitive() {
        assertThat(Severity.fromString("critical")).isEqualTo(Severity.CRITICAL);
        assertThat(Severity.fromString("HIGH")).isEqualTo(Severity.HIGH);
        assertThat(Severity.fromString("Medium")).isEqualTo(Severity.MEDIUM);
        assertThat(Severity.fromString("low")).isEqualTo(Severity.LOW);
    }

    @Test
    @DisplayName("Severity.fromString should throw for unknown value")
    void severityFromStringShouldThrowForUnknown() {
        assertThatThrownBy(() -> Severity.fromString("BLOCKER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BLOCKER");
    }

    @Test
    @DisplayName("Severity.isHigherThan should compare weights correctly")
    void severityIsHigherThanShouldCompareWeights() {
        assertThat(Severity.CRITICAL.isHigherThan(Severity.HIGH)).isTrue();
        assertThat(Severity.HIGH.isHigherThan(Severity.MEDIUM)).isTrue();
        assertThat(Severity.LOW.isHigherThan(Severity.CRITICAL)).isFalse();
        assertThat(Severity.HIGH.isHigherThan(Severity.HIGH)).isFalse();
    }

    private UnifiedAlertDto buildDto(String source, Severity severity, String title) {
        return new UnifiedAlertDto(
                UUID.randomUUID(), "test-tenant", source, SourceType.OPS,
                severity, title, null, Instant.now(),
                "prometheus:title:unknown", Map.of()
        );
    }
}