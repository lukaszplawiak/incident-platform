package com.incidentplatform.ingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PrometheusNormalizer")
class PrometheusNormalizerTest {

    private PrometheusNormalizer normalizer;
    private ObjectMapper objectMapper;
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        normalizer = new PrometheusNormalizer();
        objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(normalizer, "maxBatchSize", 500);
    }

    @Nested
    @DisplayName("Firing alerts")
    class FiringAlerts {

        @Test
        @DisplayName("should normalize single firing alert")
        void shouldNormalizeSingleFiringAlert() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "version": "4",
                      "status": "firing",
                      "alerts": [{
                        "status": "firing",
                        "labels": {
                          "alertname": "HighCpuUsage",
                          "severity": "critical",
                          "job": "node-exporter",
                          "instance": "prod-server-1:9100"
                        },
                        "annotations": {
                          "summary": "High CPU usage detected",
                          "description": "CPU above 95%"
                        },
                        "startsAt": "2024-01-15T10:30:00.000Z"
                      }]
                    }
                    """);

            final NormalizationResult result = normalizer.normalize(payload, TENANT_ID);

            assertThat(result.firingAlerts()).hasSize(1);
            assertThat(result.resolvedAlerts()).isEmpty();

            final var alert = result.firingAlerts().get(0);
            assertThat(alert.tenantId()).isEqualTo(TENANT_ID);
            assertThat(alert.source()).isEqualTo("prometheus");
            assertThat(alert.sourceType()).isEqualTo(SourceType.OPS);
            assertThat(alert.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(alert.title()).isEqualTo("High CPU usage detected");
            assertThat(alert.description()).isEqualTo("CPU above 95%");
            assertThat(alert.firedAt()).isNotNull();
            assertThat(alert.fingerprint())
                    .isEqualTo("prometheus:highcpuusage:prod-server-1:9100");
        }

        @Test
        @DisplayName("should use alertname as title when summary annotation missing")
        void shouldUseAlertnamAsTitleWhenSummaryMissing() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "alerts": [{
                        "status": "firing",
                        "labels": {
                          "alertname": "HighCpuUsage",
                          "severity": "warning",
                          "instance": "server-1:9100"
                        }
                      }]
                    }
                    """);
            assertThat(normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).title()).isEqualTo("HighCpuUsage");
        }

        @Test
        @DisplayName("should use job label as fallback when instance missing")
        void shouldUseJobAsFingerprintFallback() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "alerts": [{
                        "status": "firing",
                        "labels": {
                          "alertname": "HighCpuUsage",
                          "severity": "critical",
                          "job": "node-exporter"
                        }
                      }]
                    }
                    """);
            assertThat(normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).fingerprint())
                    .isEqualTo("prometheus:highcpuusage:node-exporter");
        }

        @Test
        @DisplayName("should use current time when startsAt is missing")
        void shouldUseCurrentTimeWhenStartsAtMissing() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "alerts": [{
                        "status": "firing",
                        "labels": {
                          "alertname": "TestAlert",
                          "severity": "high",
                          "instance": "server-1"
                        }
                      }]
                    }
                    """);
            assertThat(normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).firedAt()).isNotNull();
        }

        @Test
        @DisplayName("should exclude alertname and severity from metadata")
        void shouldExcludeAlertnamAndSeverityFromMetadata() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "alerts": [{
                        "status": "firing",
                        "labels": {
                          "alertname": "HighCpuUsage",
                          "severity": "critical",
                          "job": "node-exporter",
                          "instance": "server-1:9100"
                        }
                      }]
                    }
                    """);
            final var metadata = normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).metadata();
            assertThat(metadata).doesNotContainKey("alertname");
            assertThat(metadata).doesNotContainKey("severity");
            assertThat(metadata).containsKey("job");
            assertThat(metadata).containsKey("instance");
        }
    }

    @Nested
    @DisplayName("Resolved alerts")
    class ResolvedAlerts {

        @Test
        @DisplayName("should create ResolvedAlertNotification for resolved alert")
        void shouldCreateResolvedNotification() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "alerts": [{
                        "status": "resolved",
                        "labels": {
                          "alertname": "HighCpuUsage",
                          "severity": "critical",
                          "instance": "prod-server-1:9100"
                        },
                        "endsAt": "2024-01-15T11:00:00.000Z"
                      }]
                    }
                    """);
            final NormalizationResult result = normalizer.normalize(payload, TENANT_ID);
            assertThat(result.firingAlerts()).isEmpty();
            assertThat(result.resolvedAlerts()).hasSize(1);
            final var resolved = result.resolvedAlerts().get(0);
            assertThat(resolved.tenantId()).isEqualTo(TENANT_ID);
            assertThat(resolved.source()).isEqualTo("prometheus");
            assertThat(resolved.alertFingerprint())
                    .isEqualTo("prometheus:highcpuusage:prod-server-1:9100");
            assertThat(resolved.resolvedAt()).isNotNull();
        }

        @Test
        @DisplayName("should use same fingerprint for firing and resolved alert")
        void shouldUseSameFingerprintForFiringAndResolved() throws Exception {
            final String alertJson = """
                    {
                      "alerts": [{
                        "status": "%s",
                        "labels": {
                          "alertname": "HighCpuUsage",
                          "severity": "critical",
                          "instance": "server-1:9100"
                        }
                      }]
                    }
                    """;
            final NormalizationResult firing = normalizer.normalize(
                    objectMapper.readTree(String.format(alertJson, "firing")), TENANT_ID);
            final NormalizationResult resolved = normalizer.normalize(
                    objectMapper.readTree(String.format(alertJson, "resolved")), TENANT_ID);
            assertThat(firing.firingAlerts().get(0).fingerprint())
                    .isEqualTo(resolved.resolvedAlerts().get(0).alertFingerprint());
        }
    }

    @Nested
    @DisplayName("Severity mapping")
    class SeverityMapping {

        @Test
        @DisplayName("should map critical to CRITICAL")
        void shouldMapCritical() throws Exception {
            assertThat(normalizeSeverity("critical")).isEqualTo(Severity.CRITICAL);
        }

        @Test
        @DisplayName("should map high to HIGH")
        void shouldMapHigh() throws Exception {
            assertThat(normalizeSeverity("high")).isEqualTo(Severity.HIGH);
        }

        @Test
        @DisplayName("should map warning to MEDIUM")
        void shouldMapWarning() throws Exception {
            assertThat(normalizeSeverity("warning")).isEqualTo(Severity.MEDIUM);
        }

        @Test
        @DisplayName("should map info to LOW")
        void shouldMapInfo() throws Exception {
            assertThat(normalizeSeverity("info")).isEqualTo(Severity.LOW);
        }

        @Test
        @DisplayName("should map unknown severity to LOW")
        void shouldMapUnknownToLow() throws Exception {
            assertThat(normalizeSeverity("unknown-severity")).isEqualTo(Severity.LOW);
        }

        private Severity normalizeSeverity(String rawSeverity) throws Exception {
            final JsonNode payload = objectMapper.readTree(String.format("""
                    {
                      "alerts": [{
                        "status": "firing",
                        "labels": {
                          "alertname": "Test",
                          "severity": "%s",
                          "instance": "server-1"
                        }
                      }]
                    }
                    """, rawSeverity));
            return normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).severity();
        }
    }

    @Nested
    @DisplayName("Batch processing")
    class BatchProcessing {

        @Test
        @DisplayName("should process multiple alerts in batch")
        void shouldProcessMultipleAlerts() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "alerts": [
                        {
                          "status": "firing",
                          "labels": {
                            "alertname": "HighCpuUsage",
                            "severity": "critical",
                            "instance": "server-1:9100"
                          }
                        },
                        {
                          "status": "firing",
                          "labels": {
                            "alertname": "HighMemoryUsage",
                            "severity": "warning",
                            "instance": "server-2:9100"
                          }
                        }
                      ]
                    }
                    """);
            final NormalizationResult result = normalizer.normalize(payload, TENANT_ID);
            assertThat(result.firingAlerts()).hasSize(2);
            assertThat(result.resolvedAlerts()).isEmpty();
        }

        @Test
        @DisplayName("should process mixed firing and resolved alerts in batch")
        void shouldProcessMixedBatch() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "alerts": [
                        {
                          "status": "firing",
                          "labels": {
                            "alertname": "Alert1",
                            "severity": "critical",
                            "instance": "server-1"
                          }
                        },
                        {
                          "status": "resolved",
                          "labels": {
                            "alertname": "Alert2",
                            "severity": "warning",
                            "instance": "server-2"
                          }
                        }
                      ]
                    }
                    """);
            final NormalizationResult result = normalizer.normalize(payload, TENANT_ID);
            assertThat(result.firingAlerts()).hasSize(1);
            assertThat(result.resolvedAlerts()).hasSize(1);
            assertThat(result.totalProcessed()).isEqualTo(2);
            assertThat(result.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("should limit batch to maxBatchSize")
        void shouldLimitBatchSize() throws Exception {
            ReflectionTestUtils.setField(normalizer, "maxBatchSize", 2);
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "alerts": [
                        {
                          "status": "firing",
                          "labels": { "alertname": "Alert1", "severity": "critical", "instance": "server-1" }
                        },
                        {
                          "status": "firing",
                          "labels": { "alertname": "Alert2", "severity": "high", "instance": "server-2" }
                        },
                        {
                          "status": "firing",
                          "labels": { "alertname": "Alert3", "severity": "low", "instance": "server-3" }
                        }
                      ]
                    }
                    """);
            assertThat(normalizer.normalize(payload, TENANT_ID).firingAlerts()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("should throw NormalizationException when alerts array missing")
        void shouldThrowWhenAlertsArrayMissing() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    { "version": "4", "status": "firing" }
                    """);
            assertThatThrownBy(() -> normalizer.normalize(payload, TENANT_ID))
                    .isInstanceOf(NormalizationException.class)
                    .hasMessageContaining("alerts");
        }

        @Test
        @DisplayName("should throw NormalizationException when alerts array is empty")
        void shouldThrowWhenAlertsArrayEmpty() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    { "alerts": [] }
                    """);
            assertThatThrownBy(() -> normalizer.normalize(payload, TENANT_ID))
                    .isInstanceOf(NormalizationException.class);
        }

        @Test
        @DisplayName("should throw when alertname label is missing")
        void shouldThrowWhenAlertnameMissing() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "alerts": [{
                        "status": "firing",
                        "labels": { "severity": "critical", "instance": "server-1" }
                      }]
                    }
                    """);
            assertThatThrownBy(() -> normalizer.normalize(payload, TENANT_ID))
                    .isInstanceOf(NormalizationException.class)
                    .hasMessageContaining("alertname");
        }
    }

    @Nested
    @DisplayName("Fingerprint")
    class Fingerprint {

        @Test
        @DisplayName("should generate consistent fingerprint for same alert")
        void shouldGenerateConsistentFingerprint() throws Exception {
            final String alertJson = """
                    {
                      "alerts": [{
                        "status": "firing",
                        "labels": {
                          "alertname": "HighCpuUsage",
                          "severity": "critical",
                          "instance": "prod-server-1:9100"
                        }
                      }]
                    }
                    """;
            final NormalizationResult result1 = normalizer.normalize(
                    objectMapper.readTree(alertJson), TENANT_ID);
            final NormalizationResult result2 = normalizer.normalize(
                    objectMapper.readTree(alertJson), TENANT_ID);
            assertThat(result1.firingAlerts().get(0).fingerprint())
                    .isEqualTo(result2.firingAlerts().get(0).fingerprint());
        }

        @Test
        @DisplayName("should generate different fingerprints for different instances")
        void shouldGenerateDifferentFingerprintsForDifferentInstances() throws Exception {
            final JsonNode payload1 = objectMapper.readTree("""
                    {
                      "alerts": [{"status": "firing", "labels": {
                        "alertname": "HighCpuUsage", "severity": "critical", "instance": "server-1:9100"
                      }}]
                    }
                    """);
            final JsonNode payload2 = objectMapper.readTree("""
                    {
                      "alerts": [{"status": "firing", "labels": {
                        "alertname": "HighCpuUsage", "severity": "critical", "instance": "server-2:9100"
                      }}]
                    }
                    """);
            final String f1 = normalizer.normalize(payload1, TENANT_ID)
                    .firingAlerts().get(0).fingerprint();
            final String f2 = normalizer.normalize(payload2, TENANT_ID)
                    .firingAlerts().get(0).fingerprint();
            assertThat(f1).isNotEqualTo(f2);
        }

        @Test
        @DisplayName("fingerprint should be lowercase")
        void fingerprintShouldBeLowercase() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "alerts": [{"status": "firing", "labels": {
                        "alertname": "HighCpuUsage", "severity": "critical", "instance": "Server-1:9100"
                      }}]
                    }
                    """);
            final String fingerprint = normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).fingerprint();
            assertThat(fingerprint).isEqualTo(fingerprint.toLowerCase());
        }
    }

    @Test
    @DisplayName("getSourceName should return prometheus")
    void shouldReturnSourceName() {
        assertThat(normalizer.getSourceName()).isEqualTo("prometheus");
    }
}