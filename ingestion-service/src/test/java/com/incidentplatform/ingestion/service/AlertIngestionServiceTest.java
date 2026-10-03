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
import com.incidentplatform.shared.kafka.DeadLetterNotStoredException;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import com.incidentplatform.shared.security.InvalidTenantIdException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

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

    /** A clock the test moves (the copies' deadline, backlog #0-96). */
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-03T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @BeforeEach
    void setUp() {
        given(normalizer.getSourceName()).willReturn(SOURCE);
        // A copy Kafka takes, unless a test says otherwise (backlog #0-96).
        lenient().when(deadLetterPublisher.publishAsync(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        service = new AlertIngestionService(
                List.of(normalizer), deduplicationService, kafkaProducer,
                deadLetterPublisher, clock);
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
        @DisplayName("releases the dedup key when the publish throws before sending, and lets the failure "
                + "through (found in review)")
        void releasesDedupKeyOnSynchronousFailure() {
            final UnifiedAlertDto alert = buildAlert();
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(NormalizationResult.firingOnly(List.of(alert)));
            given(deduplicationService.isDuplicate(alert)).willReturn(false);
            given(kafkaProducer.publishFiring(alert)).willThrow(new IllegalStateException("producer closed"));

            assertThatThrownBy(() -> service.ingest(SOURCE, rawPayload, TENANT_ID, null))
                    .isInstanceOf(IllegalStateException.class);

            then(deduplicationService).should().releaseDedupKey(alert);
        }

        @Test
        @DisplayName("refuses a tenant id that is not a slug before normalizing or setting a dedup key "
                + "(backlog #0-92, found in review)")
        void refusesInvalidTenantFirst() {
            assertThatThrownBy(() -> service.ingest(SOURCE, buildRawPayload(), "Acme\nforged", null))
                    .isInstanceOf(InvalidTenantIdException.class);

            then(normalizer).should(never()).normalize(any(), any(), any());
            then(deduplicationService).shouldHaveNoInteractions();
            then(kafkaProducer).shouldHaveNoInteractions();
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
            then(deadLetterPublisher).should().publishAsync(
                    eq(rawPayload), eq(SOURCE), eq(TENANT_ID), anyString());
            then(deadLetterPublisher).should().await(anyList(), any());
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
            then(deadLetterPublisher).should().publishAndWait(
                    eq(rawPayload), eq(SOURCE), eq(TENANT_ID), anyString());
            then(kafkaProducer).shouldHaveNoInteractions();
        }
    }

    /**
     * The actual regression coverage for backlog #69 — verifies
     * AlertIngestionService's side of the fix: each entry in
     * {@code NormalizationResult.malformedAlerts()} is dead-lettered
     * individually (same {@code DeadLetterPublisher} copy
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

            then(deadLetterPublisher).should().publishAsync(
                    eq(malformedRawAlert), eq(SOURCE), eq(TENANT_ID), anyString());
            then(deadLetterPublisher).should().await(anyList(), any());
            then(kafkaProducer).should().publishFiring(validAlert);
        }
    }

    /**
     * Backlog #0-96: a copy Kafka did not take fails the request (503 with
     * Retry-After in the controller), so the sender retries the payload; the
     * alert is no longer lost after an ERROR line. The copies of a request are
     * awaited together; when they are not stored, the dedup keys of the alerts
     * they were to keep are released (found in review).
     */
    @Nested
    @DisplayName("ingest — dead-letter copy not stored (backlog #0-96)")
    class DeadLetterNotStored {

        private final DeadLetterNotStoredException notStored =
                new DeadLetterNotStoredException("not acknowledged", new RuntimeException("broker down"));

        private void copiesNotStored() {
            willThrow(notStored).given(deadLetterPublisher).await(anyList(), any());
        }

        private UnifiedAlertDto unserializable(JsonNode rawPayload, UnifiedAlertDto... alerts) {
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(NormalizationResult.firingOnly(List.of(alerts)));
            for (final UnifiedAlertDto alert : alerts) {
                given(deduplicationService.isDuplicate(alert)).willReturn(false);
                given(kafkaProducer.publishFiring(alert)).willThrow(
                        new AlertKafkaProducer.AlertPublishException("Failed to serialize", new RuntimeException()));
            }
            return alerts[0];
        }

        @Test
        @DisplayName("a payload that cannot be normalized fails the request")
        void normalizationFailure() {
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willThrow(new NormalizationException(SOURCE, "Missing required field"));
            willThrow(notStored).given(deadLetterPublisher)
                    .publishAndWait(any(Object.class), anyString(), anyString(), anyString());

            assertThatThrownBy(() -> service.ingest(SOURCE, rawPayload, TENANT_ID, null)).isSameAs(notStored);
            then(kafkaProducer).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a malformed alert's copy not stored fails the request; its valid siblings were sent, "
                + "and the retry finds them as duplicates")
        void malformedAlert() {
            final UnifiedAlertDto validAlert = buildAlert();
            final JsonNode rawPayload = buildRawPayload();
            final NormalizationResult.MalformedAlert malformed = new NormalizationResult.MalformedAlert(
                    objectMapper.createObjectNode().put("status", "firing"), "Missing 'alertname' label");
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(new NormalizationResult(List.of(validAlert), List.of(), List.of(malformed), 2));
            given(deduplicationService.isDuplicate(validAlert)).willReturn(false);
            given(kafkaProducer.publishFiring(validAlert)).willReturn(CompletableFuture.completedFuture(sendResult));
            copiesNotStored();

            assertThatThrownBy(() -> service.ingest(SOURCE, rawPayload, TENANT_ID, null)).isSameAs(notStored);
            then(kafkaProducer).should().publishFiring(validAlert);
            then(deduplicationService).should(never()).releaseDedupKey(any());
        }

        @Test
        @DisplayName("a firing alert that cannot be serialized: its dedup key is released when its copy is not "
                + "stored, or the retry would be answered as a duplicate and the alert lost (found in review)")
        void firingSerializationFailureReleasesKey() {
            final JsonNode rawPayload = buildRawPayload();
            final UnifiedAlertDto alert = unserializable(rawPayload, buildAlert());
            copiesNotStored();

            assertThatThrownBy(() -> service.ingest(SOURCE, rawPayload, TENANT_ID, null)).isSameAs(notStored);
            then(deduplicationService).should().releaseDedupKey(alert);
        }

        @Test
        @DisplayName("a stored copy keeps the dedup key: the alert is kept in the dead-letter topic")
        void storedCopyKeepsKey() {
            final JsonNode rawPayload = buildRawPayload();
            unserializable(rawPayload, buildAlert());

            final IngestionSummary summary = service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            assertThat(summary.deadLetter()).isEqualTo(1);
            then(deduplicationService).should(never()).releaseDedupKey(any());
        }

        @Test
        @DisplayName("several alerts failing to serialize: the payload is copied once, every key released on "
                + "failure (found in review)")
        void rawPayloadCopiedOnce() {
            final JsonNode rawPayload = buildRawPayload();
            final UnifiedAlertDto first = buildAlert();
            final UnifiedAlertDto second = buildAlert();
            unserializable(rawPayload, first, second);
            copiesNotStored();

            assertThatThrownBy(() -> service.ingest(SOURCE, rawPayload, TENANT_ID, null)).isSameAs(notStored);
            then(deadLetterPublisher).should(times(1))
                    .publishAsync(eq(rawPayload), eq(SOURCE), eq(TENANT_ID), anyString());
            then(deduplicationService).should().releaseDedupKey(first);
            then(deduplicationService).should().releaseDedupKey(second);
        }

        @Test
        @DisplayName("a resolved notification that cannot be serialized fails the request when its copy is not "
                + "stored")
        void resolvedSerializationFailure() {
            final ResolvedAlertNotification notification = ResolvedAlertNotification.of(
                    TENANT_ID, SOURCE, "prometheus:highcpu:server-1", Instant.now());
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(new NormalizationResult(List.of(), List.of(notification), List.of(), 1));
            willThrow(new AlertKafkaProducer.AlertPublishException("Failed to serialize", new RuntimeException()))
                    .given(kafkaProducer).publishResolved(notification);
            copiesNotStored();

            assertThatThrownBy(() -> service.ingest(SOURCE, rawPayload, TENANT_ID, null)).isSameAs(notStored);
        }

        @Test
        @DisplayName("once a copy has failed, no further copy is started: the request fails at once, keys released "
                + "(found in the second review)")
        void stopsAfterFirstFailedCopy() {
            final JsonNode rawPayload = buildRawPayload();
            final NormalizationResult.MalformedAlert first = new NormalizationResult.MalformedAlert(
                    objectMapper.createObjectNode().put("n", 1), "Missing 'alertname' label");
            final NormalizationResult.MalformedAlert second = new NormalizationResult.MalformedAlert(
                    objectMapper.createObjectNode().put("n", 2), "Missing 'alertname' label");
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(new NormalizationResult(List.of(), List.of(), List.of(first, second), 2));
            given(deadLetterPublisher.publishAsync(eq(first.rawAlert()), anyString(), anyString(), anyString()))
                    .willReturn(CompletableFuture.failedFuture(notStored));

            assertThatThrownBy(() -> service.ingest(SOURCE, rawPayload, TENANT_ID, null)).isSameAs(notStored);
            then(deadLetterPublisher).should(never())
                    .publishAsync(eq(second.rawAlert()), anyString(), anyString(), anyString());
            then(deadLetterPublisher).should(never()).await(anyList(), any());
        }

        @Test
        @DisplayName("a firing alert whose copy fails at once has its dedup key released before the throw")
        void immediateFailureReleasesKey() {
            final JsonNode rawPayload = buildRawPayload();
            final UnifiedAlertDto alert = unserializable(rawPayload, buildAlert());
            given(deadLetterPublisher.publishAsync(eq(rawPayload), anyString(), anyString(), anyString()))
                    .willReturn(CompletableFuture.failedFuture(notStored));

            assertThatThrownBy(() -> service.ingest(SOURCE, rawPayload, TENANT_ID, null)).isSameAs(notStored);
            then(deduplicationService).should().releaseDedupKey(alert);
        }

        @Test
        @DisplayName("the copies' deadline counts from the wait, not from the first copy's start, so time spent "
                + "on the alerts in between is not taken from it (found in the second review)")
        void deadlineCountsFromTheWait() {
            final UnifiedAlertDto validAlert = buildAlert();
            final JsonNode rawPayload = buildRawPayload();
            final NormalizationResult.MalformedAlert malformed = new NormalizationResult.MalformedAlert(
                    objectMapper.createObjectNode().put("status", "firing"), "Missing 'alertname' label");
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(new NormalizationResult(List.of(validAlert), List.of(), List.of(malformed), 2));
            given(deduplicationService.isDuplicate(validAlert)).willReturn(false);
            given(kafkaProducer.publishFiring(validAlert)).willAnswer(invocation -> {
                now.set(now.get().plusSeconds(10)); // the alerts' own work, after the copy was started
                return CompletableFuture.completedFuture(sendResult);
            });
            final Instant afterAlerts = Instant.parse("2026-10-03T10:00:10Z");

            service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            final ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
            then(deadLetterPublisher).should().await(anyList(), from.capture());
            assertThat(from.getValue()).isEqualTo(afterAlerts);
        }

        @Test
        @DisplayName("nothing to copy: nothing awaited")
        void nothingToCopy() {
            final JsonNode rawPayload = buildRawPayload();
            given(normalizer.normalize(rawPayload, TENANT_ID, null))
                    .willReturn(NormalizationResult.firingOnly(List.of()));

            service.ingest(SOURCE, rawPayload, TENANT_ID, null);

            then(deadLetterPublisher).shouldHaveNoInteractions();
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