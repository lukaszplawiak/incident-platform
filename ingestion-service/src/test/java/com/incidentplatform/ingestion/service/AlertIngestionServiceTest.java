package com.incidentplatform.ingestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.ingestion.normalizer.AlertNormalizer;
import com.incidentplatform.ingestion.normalizer.NormalizationException;
import com.incidentplatform.ingestion.normalizer.NormalizationResult;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.events.SourceType;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Previously had no test file at all. Added primarily to cover backlog
 * #24's fix — see {@link DeduplicationService#releaseDedupKey}'s Javadoc
 * for the full account of the bug being regression-tested here: a failed
 * Kafka publish left the dedup key in place, so a legitimate retry of the
 * same alert was wrongly rejected as a duplicate for the rest of the TTL
 * window. That fix lives in the wiring between this class and
 * {@link AlertKafkaProducer}/{@link DeduplicationService}, which a
 * mocked-repository-style unit test for either of those classes alone
 * couldn't exercise — it only shows up at the orchestration level, here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AlertIngestionService")
class AlertIngestionServiceTest {

    @Mock private AlertNormalizer normalizer;
    @Mock private DeduplicationService deduplicationService;
    @Mock private AlertKafkaProducer kafkaProducer;
    @Mock private DeadLetterPublisher deadLetterPublisher;
    @Mock private SendResult<String, String> sendResult;

    private AlertIngestionService service;

    private static final String SOURCE = "prometheus";
    private static final String TENANT_ID = "acme-corp";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        given(normalizer.getSourceName()).willReturn(SOURCE);
        service = new AlertIngestionService(
                List.of(normalizer), deduplicationService, kafkaProducer,
                deadLetterPublisher);
    }

    private JsonNode buildRawPayload() {
        return objectMapper.createObjectNode().put("status", "firing");
    }

    private UnifiedAlertDto buildAlert() {
        return new UnifiedAlertDto(
                UUID.randomUUID(), TENANT_ID, SOURCE,
                SourceType.OPS, Severity.CRITICAL,
                "High CPU usage", "CPU exceeded 95%",
                Instant.now(), "prometheus:highcpu:server-1",
                Map.of(), null);
    }

    @Nested
    @DisplayName("ingest — firing alerts")
    class FiringAlerts {

        @Test
        @DisplayName("publishes a new (non-duplicate) alert and counts it as processed")
        void publishesNewAlert() {
            final UnifiedAlertDto alert = buildAlert();
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(NormalizationResult.firingOnly(List.of(alert)));
            given(deduplicationService.isDuplicate(alert)).willReturn(false);
            given(kafkaProducer.publishFiring(alert))
                    .willReturn(CompletableFuture.completedFuture(sendResult));

            final IngestionSummary summary =
                    service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            assertThat(summary.processed()).isEqualTo(1);
            assertThat(summary.duplicates()).isEqualTo(0);
            assertThat(summary.deadLetter()).isEqualTo(0);
            then(kafkaProducer).should().publishFiring(alert);
        }

        @Test
        @DisplayName("reports truncated=false and received matching processed " +
                "when nothing was truncated")
        void reportsNotTruncatedInTheNormalCase() {
            final UnifiedAlertDto alert = buildAlert();
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(NormalizationResult.firingOnly(List.of(alert)));
            given(deduplicationService.isDuplicate(alert)).willReturn(false);
            given(kafkaProducer.publishFiring(alert))
                    .willReturn(CompletableFuture.completedFuture(sendResult));

            final IngestionSummary summary =
                    service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            assertThat(summary.truncated()).isFalse();
            assertThat(summary.received()).isEqualTo(1);
            assertThat(summary.isFullySuccessful()).isTrue();
        }

        /**
         * The actual regression test for backlog #26. Simulates what
         * PrometheusNormalizer does when a batch exceeds
         * ingestion.prometheus.max-batch-size: NormalizationResult's
         * firingAlerts/resolvedAlerts are already capped (only 1 alert
         * here), but totalReceived reports the true, larger original
         * count (5) — verifies AlertIngestionService surfaces this
         * honestly via IngestionSummary.received and .truncated, rather
         * than silently reporting received=1 as if the payload only ever
         * had 1 alert. See NormalizationResult's own Javadoc for the full
         * account of the bug being fixed.
         */
        @Test
        @DisplayName("reports truncated=true and the true original received count " +
                "when the normalizer capped the batch")
        void reportsTruncationWhenNormalizerCappedTheBatch() {
            final UnifiedAlertDto alert = buildAlert();
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(new NormalizationResult(
                            List.of(alert), List.of(), List.of(), 5));
            given(deduplicationService.isDuplicate(alert)).willReturn(false);
            given(kafkaProducer.publishFiring(alert))
                    .willReturn(CompletableFuture.completedFuture(sendResult));

            final IngestionSummary summary =
                    service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            assertThat(summary.received()).isEqualTo(5);
            assertThat(summary.processed()).isEqualTo(1);
            assertThat(summary.truncated()).isTrue();
            assertThat(summary.isFullySuccessful())
                    .as("a truncated batch must never report as fully successful")
                    .isFalse();
        }

        @Test
        @DisplayName("does not publish a duplicate alert, counts it as a duplicate")
        void doesNotPublishDuplicateAlert() {
            final UnifiedAlertDto alert = buildAlert();
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(NormalizationResult.firingOnly(List.of(alert)));
            given(deduplicationService.isDuplicate(alert)).willReturn(true);

            final IngestionSummary summary =
                    service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            assertThat(summary.duplicates()).isEqualTo(1);
            assertThat(summary.processed()).isEqualTo(0);
            then(kafkaProducer).should(never()).publishFiring(any());
        }

        /**
         * The actual regression test for backlog #24. Simulates a real
         * Kafka send failure (broker unreachable) surfacing asynchronously
         * via the future AlertKafkaProducer.publishFiring now returns —
         * verifies AlertIngestionService reacts by releasing the dedup key
         * DeduplicationService.isDuplicate already set, so this alert
         * won't be wrongly rejected as a duplicate if the same fingerprint
         * arrives again (e.g. Alertmanager's own retry) before the TTL
         * expires.
         */
        @Test
        @DisplayName("releases the dedup key when the Kafka publish fails asynchronously")
        void releasesDedupKeyOnPublishFailure() {
            final UnifiedAlertDto alert = buildAlert();
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(NormalizationResult.firingOnly(List.of(alert)));
            given(deduplicationService.isDuplicate(alert)).willReturn(false);
            given(kafkaProducer.publishFiring(alert))
                    .willReturn(CompletableFuture.failedFuture(
                            new RuntimeException("Broker unreachable")));

            service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            then(deduplicationService).should().releaseDedupKey(alert);
        }

        @Test
        @DisplayName("does NOT release the dedup key when the Kafka publish succeeds")
        void doesNotReleaseDedupKeyOnPublishSuccess() {
            final UnifiedAlertDto alert = buildAlert();
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(NormalizationResult.firingOnly(List.of(alert)));
            given(deduplicationService.isDuplicate(alert)).willReturn(false);
            given(kafkaProducer.publishFiring(alert))
                    .willReturn(CompletableFuture.completedFuture(sendResult));

            service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            then(deduplicationService).should(never()).releaseDedupKey(any());
        }

        @Test
        @DisplayName("routes to dead letter when serialization fails — " +
                "AlertPublishException from publishFiring itself")
        void routesToDeadLetterOnSerializationFailure() {
            final UnifiedAlertDto alert = buildAlert();
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(NormalizationResult.firingOnly(List.of(alert)));
            given(deduplicationService.isDuplicate(alert)).willReturn(false);
            given(kafkaProducer.publishFiring(alert))
                    .willThrow(new AlertKafkaProducer.AlertPublishException(
                            "Failed to serialize", new RuntimeException()));

            final IngestionSummary summary =
                    service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            assertThat(summary.deadLetter()).isEqualTo(1);
            then(deadLetterPublisher).should().publish(
                    eq(rawPayload), eq(SOURCE), eq(TENANT_ID), anyString());
        }
    }

    @Nested
    @DisplayName("ingest — resolved alerts")
    class ResolvedAlerts {

        @Test
        @DisplayName("publishes a resolved notification and counts it")
        void publishesResolvedNotification() {
            final ResolvedAlertNotification notification = ResolvedAlertNotification.of(
                    TENANT_ID, SOURCE, "prometheus:highcpu:server-1", Instant.now());
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(new NormalizationResult(
                            List.of(), List.of(notification), List.of(), 1));

            final IngestionSummary summary =
                    service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            assertThat(summary.resolved()).isEqualTo(1);
            then(kafkaProducer).should().publishResolved(notification);
            // Resolved alerts never go through deduplication — only firing ones do.
            then(deduplicationService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("ingest — normalization failure")
    class NormalizationFailure {

        @Test
        @DisplayName("routes the entire payload to dead letter when normalization throws")
        void routesEntirePayloadToDeadLetterOnNormalizationFailure() {
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willThrow(new NormalizationException(SOURCE, "Missing required field"));

            final IngestionSummary summary =
                    service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            assertThat(summary.deadLetter()).isEqualTo(1);
            assertThat(summary.processed()).isEqualTo(0);
            then(deadLetterPublisher).should().publish(
                    eq(rawPayload), eq(SOURCE), eq(TENANT_ID), anyString());
            then(kafkaProducer).shouldHaveNoInteractions();
        }
    }

    /**
     * The actual regression coverage for backlog #69 — verifies
     * AlertIngestionService's side of the fix: each entry in
     * {@code NormalizationResult.malformedAlerts()} is dead-lettered
     * individually (same {@code DeadLetterPublisher.publish} call
     * already used for serialization failures), counted into
     * {@code IngestionSummary.deadLetter}, and — critically — does not
     * prevent the rest of the batch's valid alerts from being processed
     * normally in the same call.
     */
    @Nested
    @DisplayName("ingest — malformed alerts within an otherwise-valid batch")
    class MalformedAlertsWithinBatch {

        @Test
        @DisplayName("dead-letters a malformed alert individually and still " +
                "processes the valid alerts in the same batch")
        void deadLettersMalformedAlertAndStillProcessesValidSiblings() {
            final UnifiedAlertDto validAlert = buildAlert();
            final JsonNode rawPayload = buildRawPayload();
            final JsonNode malformedRawAlert = objectMapper.createObjectNode()
                    .put("status", "firing");
            final NormalizationResult.MalformedAlert malformed =
                    new NormalizationResult.MalformedAlert(
                            malformedRawAlert, "Missing 'alertname' label");

            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(new NormalizationResult(
                            List.of(validAlert), List.of(), List.of(malformed), 2));
            given(deduplicationService.isDuplicate(validAlert)).willReturn(false);
            given(kafkaProducer.publishFiring(validAlert))
                    .willReturn(CompletableFuture.completedFuture(sendResult));

            final IngestionSummary summary =
                    service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            assertThat(summary.deadLetter()).isEqualTo(1);
            assertThat(summary.processed()).isEqualTo(1);
            assertThat(summary.received()).isEqualTo(2);
            assertThat(summary.truncated())
                    .as("a malformed alert alone must not report as truncated — " +
                            "that's a distinct concept (batch-size limiting)")
                    .isFalse();

            then(deadLetterPublisher).should().publish(
                    eq(malformedRawAlert), eq(SOURCE), eq(TENANT_ID), anyString());
            then(kafkaProducer).should().publishFiring(validAlert);
        }
    }

    @Nested
    @DisplayName("getAvailableSources")
    class GetAvailableSources {

        @Test
        @DisplayName("returns the registered normalizer's source name")
        void returnsRegisteredSourceNames() {
            assertThat(service.getAvailableSources()).containsExactly(SOURCE);
        }
    }
}