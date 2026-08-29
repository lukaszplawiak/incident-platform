package com.incidentplatform.ingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.ingestion.config.IngestionProperties;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PrometheusNormalizer")
class PrometheusNormalizerTest {

    private PrometheusNormalizer normalizer;
    private ObjectMapper objectMapper;
    private static final String TENANT_ID = "test-tenant";
    private static final int MAX_PAYLOAD_BYTES = 1048576;

    @BeforeEach
    void setUp() {
        normalizer = new PrometheusNormalizer(
                new IngestionProperties(new IngestionProperties.Prometheus(500), MAX_PAYLOAD_BYTES, java.util.List.of()));
        objectMapper = new ObjectMapper();
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

            final NormalizationResult result = normalizer.normalize(payload, TENANT_ID, null);

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
            assertThat(normalizer.normalize(payload, TENANT_ID, null)
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
            assertThat(normalizer.normalize(payload, TENANT_ID, null)
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
            assertThat(normalizer.normalize(payload, TENANT_ID, null)
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
            final var metadata = normalizer.normalize(payload, TENANT_ID, null)
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
            final NormalizationResult result = normalizer.normalize(payload, TENANT_ID, null);
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
                    objectMapper.readTree(String.format(alertJson, "firing")), TENANT_ID, null);
            final NormalizationResult resolved = normalizer.normalize(
                    objectMapper.readTree(String.format(alertJson, "resolved")), TENANT_ID, null);
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
            return normalizer.normalize(payload, TENANT_ID, null)
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
            final NormalizationResult result = normalizer.normalize(payload, TENANT_ID, null);
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
            final NormalizationResult result = normalizer.normalize(payload, TENANT_ID, null);
            assertThat(result.firingAlerts()).hasSize(1);
            assertThat(result.resolvedAlerts()).hasSize(1);
            assertThat(result.totalProcessed()).isEqualTo(2);
            assertThat(result.isEmpty()).isFalse();
        }

        /**
         * Extended for backlog #26: previously only checked that
         * firingAlerts() was correctly capped at maxBatchSize — didn't
         * verify the normalizer honestly reports the true, pre-truncation
         * count anywhere, which is exactly what NormalizationResult
         * .totalReceived/.isTruncated exist for. See that record's
         * Javadoc for the full account of the silent-data-loss bug this
         * closes.
         */
        @Test
        @DisplayName("should limit batch to maxBatchSize, while still reporting " +
                "the true original count as totalReceived")
        void shouldLimitBatchSize() throws Exception {
            final PrometheusNormalizer smallBatchNormalizer = new PrometheusNormalizer(
                    new IngestionProperties(new IngestionProperties.Prometheus(2), MAX_PAYLOAD_BYTES, java.util.List.of()));

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
            final NormalizationResult result =
                    smallBatchNormalizer.normalize(payload, TENANT_ID, null);

            assertThat(result.firingAlerts()).hasSize(2);
            assertThat(result.totalReceived())
                    .as("totalReceived must reflect the true payload size (3), " +
                            "not the capped result size (2)")
                    .isEqualTo(3);
            assertThat(result.isTruncated()).isTrue();
        }

        @Test
        @DisplayName("should report isTruncated=false when the batch is within maxBatchSize")
        void shouldNotReportTruncatedWhenWithinLimit() throws Exception {
            final PrometheusNormalizer normalizerWithRoom = new PrometheusNormalizer(
                    new IngestionProperties(new IngestionProperties.Prometheus(10), MAX_PAYLOAD_BYTES, List.of()));

            final JsonNode payload = objectMapper.readTree("""
                    {
                      "alerts": [
                        {
                          "status": "firing",
                          "labels": { "alertname": "Alert1", "severity": "critical", "instance": "server-1" }
                        }
                      ]
                    }
                    """);
            final NormalizationResult result =
                    normalizerWithRoom.normalize(payload, TENANT_ID, null);

            assertThat(result.totalReceived()).isEqualTo(1);
            assertThat(result.isTruncated()).isFalse();
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
            assertThatThrownBy(() -> normalizer.normalize(payload, TENANT_ID, null))
                    .isInstanceOf(NormalizationException.class)
                    .hasMessageContaining("alerts");
        }

        @Test
        @DisplayName("should throw NormalizationException when alerts array is empty")
        void shouldThrowWhenAlertsArrayEmpty() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    { "alerts": [] }
                    """);
            assertThatThrownBy(() -> normalizer.normalize(payload, TENANT_ID, null))
                    .isInstanceOf(NormalizationException.class);
        }

        @Test
        @DisplayName("should collect a malformed alert as malformedAlerts instead of " +
                "throwing — backlog #69, no longer fails the whole batch")
        void shouldCollectMalformedAlertInsteadOfThrowing() throws Exception {
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "alerts": [{
                        "status": "firing",
                        "labels": { "severity": "critical", "instance": "server-1" }
                      }]
                    }
                    """);

            final NormalizationResult result = normalizer.normalize(payload, TENANT_ID, null);

            assertThat(result.firingAlerts()).isEmpty();
            assertThat(result.resolvedAlerts()).isEmpty();
            assertThat(result.malformedAlerts()).hasSize(1);
            assertThat(result.malformedAlerts().get(0).reason())
                    .contains("alertname");
            assertThat(result.malformedAlerts().get(0).rawAlert())
                    .isEqualTo(payload.get("alerts").get(0));
        }

        /**
         * The actual regression test for backlog #69 — the specific
         * scenario the fix targets: several alerts in one batch, one of
         * them malformed. Before this fix, the malformed one would have
         * made {@code normalize} throw, and {@code AlertIngestionService}
         * would have dead-lettered the ENTIRE batch as one unit —
         * discarding the two genuinely valid alerts alongside it. Now
         * they must be processed normally, isolated from the one that
         * failed.
         */
        @Test
        @DisplayName("should isolate one malformed alert from otherwise-valid " +
                "siblings in the same batch (backlog #69)")
        void shouldIsolateMalformedAlertFromValidSiblings() throws Exception {
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
                          "labels": { "severity": "critical", "instance": "server-2" }
                        },
                        {
                          "status": "resolved",
                          "labels": {
                            "alertname": "HighMemoryUsage",
                            "instance": "server-3:9100"
                          }
                        }
                      ]
                    }
                    """);

            final NormalizationResult result = normalizer.normalize(payload, TENANT_ID, null);

            assertThat(result.firingAlerts()).hasSize(1);
            assertThat(result.firingAlerts().get(0).source())
                    .isEqualTo("prometheus");
            assertThat(result.resolvedAlerts()).hasSize(1);
            assertThat(result.malformedAlerts()).hasSize(1);
            assertThat(result.malformedAlerts().get(0).reason())
                    .contains("alertname");

            // Confirms the fix documented on NormalizationResult.isTruncated:
            // a batch with a malformed alert but no batch-size truncation
            // must not report itself as truncated.
            assertThat(result.totalReceived()).isEqualTo(3);
            assertThat(result.totalProcessed()).isEqualTo(3);
            assertThat(result.isTruncated()).isFalse();
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
                    objectMapper.readTree(alertJson), TENANT_ID, null);
            final NormalizationResult result2 = normalizer.normalize(
                    objectMapper.readTree(alertJson), TENANT_ID, null);
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
            final String f1 = normalizer.normalize(payload1, TENANT_ID, null)
                    .firingAlerts().get(0).fingerprint();
            final String f2 = normalizer.normalize(payload2, TENANT_ID, null)
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
            final String fingerprint = normalizer.normalize(payload, TENANT_ID, null)
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