package com.incidentplatform.incident.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentEventOutbox;
import com.incidentplatform.incident.domain.IncidentEventOutboxStatus;
import com.incidentplatform.incident.repository.IncidentEventOutboxRepository;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.events.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;

/**
 * Backlog #36. Previously had no test file at all — the direct
 * {@code IncidentEventKafkaSender.send(...)} call this class used to make
 * was also entirely untested. This is the core regression coverage for the
 * outbox fix itself: every {@code publishXxx} method must write a PENDING
 * {@link IncidentEventOutbox} row instead of touching Kafka at all — see
 * this class's own Javadoc for the full account of the phantom-event bug
 * being fixed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentEventPublisher")
class IncidentEventPublisherTest {

    @Mock private IncidentEventOutboxRepository outboxRepository;

    private IncidentEventPublisher publisher;
    private ObjectMapper objectMapper;

    private static final String TENANT_ID = "acme-corp";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        publisher = new IncidentEventPublisher(outboxRepository, objectMapper);
    }

    private Incident buildIncident() {
        return new Incident(
                TENANT_ID, "High CPU usage", "CPU exceeded 95%",
                Severity.CRITICAL, SourceType.OPS, "prometheus",
                "prometheus:highcpu:server-1", UUID.randomUUID(), Instant.now());
    }

    @Test
    @DisplayName("publishOpened writes a PENDING outbox row, never touches Kafka directly")
    void publishOpenedWritesOutboxRow() {
        final Incident incident = buildIncident();

        publisher.publishOpened(incident);

        final ArgumentCaptor<IncidentEventOutbox> captor =
                ArgumentCaptor.forClass(IncidentEventOutbox.class);
        then(outboxRepository).should().save(captor.capture());

        final IncidentEventOutbox entry = captor.getValue();
        assertThat(entry.getIncidentId()).isEqualTo(incident.getId());
        assertThat(entry.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(entry.getEventType()).isEqualTo(IncidentEventTypes.INCIDENT_OPENED);
        assertThat(entry.getStatus()).isEqualTo(IncidentEventOutboxStatus.PENDING);
        assertThat(entry.getPayload()).contains(incident.getId().toString());
    }

    @Test
    @DisplayName("publishAcknowledged writes a PENDING outbox row with the correct event type")
    void publishAcknowledgedWritesOutboxRow() {
        final Incident incident = buildIncident();
        final UUID acknowledgedBy = UUID.randomUUID();

        publisher.publishAcknowledged(incident, acknowledgedBy);

        final ArgumentCaptor<IncidentEventOutbox> captor =
                ArgumentCaptor.forClass(IncidentEventOutbox.class);
        then(outboxRepository).should().save(captor.capture());

        assertThat(captor.getValue().getEventType())
                .isEqualTo(IncidentEventTypes.INCIDENT_ACKNOWLEDGED);
        assertThat(captor.getValue().getPayload())
                .contains(acknowledgedBy.toString());
    }

    @Test
    @DisplayName("publishResolved writes a PENDING outbox row with the correct event type")
    void publishResolvedWritesOutboxRow() {
        final Incident incident = buildIncident();

        publisher.publishResolved(incident, UUID.randomUUID());

        final ArgumentCaptor<IncidentEventOutbox> captor =
                ArgumentCaptor.forClass(IncidentEventOutbox.class);
        then(outboxRepository).should().save(captor.capture());

        assertThat(captor.getValue().getEventType())
                .isEqualTo(IncidentEventTypes.INCIDENT_RESOLVED);
    }

    @Test
    @DisplayName("publishClosed writes a PENDING outbox row with the correct event type")
    void publishClosedWritesOutboxRow() {
        final Incident incident = buildIncident();

        publisher.publishClosed(incident, UUID.randomUUID(), null);

        final ArgumentCaptor<IncidentEventOutbox> captor =
                ArgumentCaptor.forClass(IncidentEventOutbox.class);
        then(outboxRepository).should().save(captor.capture());

        assertThat(captor.getValue().getEventType())
                .isEqualTo(IncidentEventTypes.INCIDENT_CLOSED);
    }

    @Test
    @DisplayName("publishEscalated writes a PENDING outbox row with the correct event type")
    void publishEscalatedWritesOutboxRow() {
        final Incident incident = buildIncident();

        publisher.publishEscalated(incident, UUID.randomUUID(), 2);

        final ArgumentCaptor<IncidentEventOutbox> captor =
                ArgumentCaptor.forClass(IncidentEventOutbox.class);
        then(outboxRepository).should().save(captor.capture());

        assertThat(captor.getValue().getEventType())
                .isEqualTo(IncidentEventTypes.INCIDENT_ESCALATED);
    }

    /**
     * Regression test for this class's documented behavior change: a
     * serialization failure used to be logged and swallowed (the business
     * transaction still committed, silently with no event ever published).
     * It now propagates, rolling back the transaction — see this class's
     * own Javadoc, "Serialization failure now rolls back the transaction",
     * for the full reasoning. Using a publisher wired with a broken
     * ObjectMapper (no JavaTimeModule, so it can't serialize Instant) to
     * force a real serialization failure rather than mocking one.
     */
    @Test
    @DisplayName("propagates a serialization failure instead of silently swallowing it")
    void propagatesSerializationFailure() {
        final ObjectMapper brokenMapper = new ObjectMapper(); // no JavaTimeModule
        final IncidentEventPublisher publisherWithBrokenMapper =
                new IncidentEventPublisher(outboxRepository, brokenMapper);

        assertThatThrownBy(() -> publisherWithBrokenMapper.publishOpened(buildIncident()))
                .isInstanceOf(IllegalStateException.class);

        then(outboxRepository).shouldHaveNoInteractions();
    }
}