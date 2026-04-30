package com.incidentplatform.ingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GenericNormalizer")
class GenericNormalizerTest {

    private GenericNormalizer normalizer;
    private ObjectMapper objectMapper;
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        normalizer = new GenericNormalizer();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("should normalize valid generic alert")
    void shouldNormalizeValidAlert() throws Exception {
        // given
        final JsonNode payload = objectMapper.readTree("""
                {
                  "source": "my-app",
                  "sourceType": "OPS",
                  "severity": "HIGH",
                  "title": "Database connection pool exhausted",
                  "description": "All 100 connections are in use",
                  "firedAt": "2024-01-15T10:30:00Z",
                  "metadata": {
                    "service": "payment-service",
                    "environment": "production"
                  }
                }
                """);

        // when
        final NormalizationResult result = normalizer.normalize(payload, TENANT_ID);

        // then
        assertThat(result.firingAlerts()).hasSize(1);
        assertThat(result.resolvedAlerts()).isEmpty();

        final var alert = result.firingAlerts().get(0);
        assertThat(alert.tenantId()).isEqualTo(TENANT_ID);
        assertThat(alert.source()).isEqualTo("my-app");
        assertThat(alert.sourceType()).isEqualTo(SourceType.OPS);
        assertThat(alert.severity()).isEqualTo(Severity.HIGH);
        assertThat(alert.title()).isEqualTo("Database connection pool exhausted");
        assertThat(alert.description()).isEqualTo("All 100 connections are in use");
        assertThat(alert.firedAt()).isNotNull();
        assertThat(alert.metadata()).containsEntry("service", "payment-service");
    }

    @Nested
    @DisplayName("Severity validation")
    class SeverityValidation {

        @Test
        @DisplayName("should accept CRITICAL severity")
        void shouldAcceptCritical() throws Exception {
            assertThat(normalizeSeverity("CRITICAL")).isEqualTo(Severity.CRITICAL);
        }

        @Test
        @DisplayName("should accept HIGH severity")
        void shouldAcceptHigh() throws Exception {
            assertThat(normalizeSeverity("HIGH")).isEqualTo(Severity.HIGH);
        }

        @Test
        @DisplayName("should accept MEDIUM severity")
        void shouldAcceptMedium() throws Exception {
            assertThat(normalizeSeverity("MEDIUM")).isEqualTo(Severity.MEDIUM);
        }

        @Test
        @DisplayName("should accept LOW severity")
        void shouldAcceptLow() throws Exception {
            assertThat(normalizeSeverity("LOW")).isEqualTo(Severity.LOW);
        }

        @Test
        @DisplayName("should accept lowercase severity and parse to enum")
        void shouldAcceptLowercaseSeverity() throws Exception {
            assertThat(normalizeSeverity("critical")).isEqualTo(Severity.CRITICAL);
            assertThat(normalizeSeverity("high")).isEqualTo(Severity.HIGH);
        }

        @Test
        @DisplayName("should throw NormalizationException for invalid severity")
        void shouldThrowForInvalidSeverity() throws Exception {
            // given
            final JsonNode payload = buildPayload("INVALID", "title", "OPS");

            // then
            assertThatThrownBy(() -> normalizer.normalize(payload, TENANT_ID))
                    .isInstanceOf(NormalizationException.class)
                    .hasMessageContaining("severity")
                    .hasMessageContaining("INVALID");
        }

        @Test
        @DisplayName("should throw for empty severity")
        void shouldThrowForEmptySeverity() throws Exception {
            // given
            final JsonNode payload = buildPayload("", "title", "OPS");

            // then
            assertThatThrownBy(() -> normalizer.normalize(payload, TENANT_ID))
                    .isInstanceOf(NormalizationException.class);
        }

        private Severity normalizeSeverity(String severity) throws Exception {
            return normalizer.normalize(
                            buildPayload(severity, "title", "OPS"), TENANT_ID)
                    .firingAlerts().get(0).severity();
        }
    }

    @Nested
    @DisplayName("SourceType parsing")
    class SourceTypeParsing {

        @Test
        @DisplayName("should parse OPS sourceType")
        void shouldParseOps() throws Exception {
            final JsonNode payload = buildPayload("HIGH", "title", "OPS");
            final var alert = normalizer.normalize(payload, TENANT_ID).firingAlerts().get(0);
            assertThat(alert.sourceType()).isEqualTo(SourceType.OPS);
        }

        @Test
        @DisplayName("should parse SECURITY sourceType")
        void shouldParseSecurity() throws Exception {
            final JsonNode payload = buildPayload("HIGH", "title", "SECURITY");
            final var alert = normalizer.normalize(payload, TENANT_ID).firingAlerts().get(0);
            assertThat(alert.sourceType()).isEqualTo(SourceType.SECURITY);
        }

        @Test
        @DisplayName("should default to OPS for unknown sourceType")
        void shouldDefaultToOpsForUnknown() throws Exception {
            final JsonNode payload = buildPayload("HIGH", "title", "UNKNOWN");
            final var alert = normalizer.normalize(payload, TENANT_ID).firingAlerts().get(0);
            assertThat(alert.sourceType()).isEqualTo(SourceType.OPS);
        }

        @Test
        @DisplayName("should default to OPS when sourceType missing")
        void shouldDefaultToOpsWhenMissing() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "severity": "HIGH",
                      "title": "Test alert"
                    }
                    """);
            final var alert = normalizer.normalize(payload, TENANT_ID).firingAlerts().get(0);
            assertThat(alert.sourceType()).isEqualTo(SourceType.OPS);
        }
    }

    @Nested
    @DisplayName("Metadata limits")
    class MetadataLimits {

        @Test
        @DisplayName("should truncate metadata value exceeding 500 characters")
        void shouldTruncateLongMetadataValue() throws Exception {
            final String longValue = "x".repeat(600);
            final JsonNode payload = objectMapper.readTree(String.format("""
                    {
                      "severity": "HIGH",
                      "title": "Test",
                      "metadata": {
                        "longField": "%s"
                      }
                    }
                    """, longValue));
            final var metadata = normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).metadata();
            assertThat(metadata.get("longField")).hasSize(500);
        }

        @Test
        @DisplayName("should handle missing metadata section")
        void shouldHandleNullMetadataSection() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "severity": "HIGH",
                      "title": "Test alert"
                    }
                    """);
            final var metadata = normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).metadata();
            assertThat(metadata).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("Required fields")
    class RequiredFields {

        @Test
        @DisplayName("should throw when title is missing")
        void shouldThrowWhenTitleMissing() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "severity": "HIGH",
                      "sourceType": "OPS"
                    }
                    """);
            assertThatThrownBy(() -> normalizer.normalize(payload, TENANT_ID))
                    .isInstanceOf(NormalizationException.class)
                    .hasMessageContaining("title");
        }

        @Test
        @DisplayName("should throw when severity is missing")
        void shouldThrowWhenSeverityMissing() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "title": "Test alert",
                      "sourceType": "OPS"
                    }
                    """);
            assertThatThrownBy(() -> normalizer.normalize(payload, TENANT_ID))
                    .isInstanceOf(NormalizationException.class)
                    .hasMessageContaining("severity");
        }
    }

    @Test
    @DisplayName("should use generic as default source when missing")
    void shouldUseGenericAsDefaultSource() throws Exception {
        final JsonNode payload = objectMapper.readTree("""
                {
                  "severity": "HIGH",
                  "title": "Test alert"
                }
                """);
        final var alert = normalizer.normalize(payload, TENANT_ID).firingAlerts().get(0);
        assertThat(alert.source()).isEqualTo("generic");
    }

    @Test
    @DisplayName("fingerprint should be based on title")
    void fingerprintShouldBeBasedOnTitle() throws Exception {
        final JsonNode payload = buildPayload("HIGH",
                "Database connection pool exhausted", "OPS");
        final String fingerprint = normalizer.normalize(payload, TENANT_ID)
                .firingAlerts().get(0).fingerprint();
        assertThat(fingerprint).isEqualTo("generic:database connection pool exhausted");
    }

    @Test
    @DisplayName("getSourceName should return generic")
    void shouldReturnSourceName() {
        assertThat(normalizer.getSourceName()).isEqualTo("generic");
    }

    private JsonNode buildPayload(String severity, String title,
                                  String sourceType) throws Exception {
        return objectMapper.readTree(String.format("""
                {
                  "severity": "%s",
                  "title": "%s",
                  "sourceType": "%s"
                }
                """, severity, title, sourceType));
    }
}