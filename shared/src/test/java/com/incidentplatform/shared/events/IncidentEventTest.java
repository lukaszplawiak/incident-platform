package com.incidentplatform.shared.events;

import com.incidentplatform.shared.domain.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IncidentEvent sealed hierarchy")
class IncidentEventTest {

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TENANT_ID = "acme-corp";

    @Test
    @DisplayName("IncidentOpenedEvent should implement IncidentEvent")
    void incidentOpenedEventShouldImplementInterface() {
        final IncidentOpenedEvent event = new IncidentOpenedEvent(
                INCIDENT_ID, TENANT_ID, UUID.randomUUID(),
                "prometheus:highcpu:prod-1",
                "High CPU", Severity.CRITICAL, SourceType.OPS, Instant.now()
        );

        assertThat(event).isInstanceOf(IncidentEvent.class);
        assertThat(event.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(event.tenantId()).isEqualTo(TENANT_ID);
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("IncidentAcknowledgedEvent should set occurredAt when null")
    void acknowledgedEventShouldSetOccurredAt() {
        final IncidentAcknowledgedEvent event = new IncidentAcknowledgedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID, null
        );

        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.acknowledgedBy()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("IncidentResolvedEvent should contain duration and resolution")
    void resolvedEventShouldContainDuration() {
        final IncidentResolvedEvent event = new IncidentResolvedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID,
                "prometheus:highcpu:prod-1",
                45L, "Restarted the service", "High CPU usage on prod-server-1", Severity.CRITICAL, Instant.now()
        );

        assertThat(event.durationMinutes()).isEqualTo(45L);
        assertThat(event.resolution()).isEqualTo("Restarted the service");
        assertThat(event.resolvedBy()).isEqualTo(USER_ID);
        assertThat(event.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("IncidentEscalatedEvent should contain escalation level")
    void escalatedEventShouldContainLevel() {
        final IncidentEscalatedEvent event = new IncidentEscalatedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID,
                2, Severity.CRITICAL, "High CPU", Instant.now()
        );

        assertThat(event.escalationLevel()).isEqualTo(2);
        assertThat(event.escalateTo()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("IncidentClosedEvent should contain postmortemId")
    void closedEventShouldContainPostmortemId() {
        final UUID postmortemId = UUID.randomUUID();
        final IncidentClosedEvent event = new IncidentClosedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID, postmortemId, Instant.now()
        );

        assertThat(event.postmortemId()).isEqualTo(postmortemId);
        assertThat(event.closedBy()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("pattern matching switch should handle all IncidentEvent subtypes")
    void patternMatchingShouldBeExhaustive() {
        final IncidentEvent opened = new IncidentOpenedEvent(
                INCIDENT_ID, TENANT_ID, UUID.randomUUID(),
                "prometheus:test:prod-1",
                "title", Severity.HIGH, SourceType.OPS, Instant.now());
        final IncidentEvent acknowledged = new IncidentAcknowledgedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID, Instant.now());
        final IncidentEvent resolved = new IncidentResolvedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID,
                "prometheus:test:prod-1",
                30L, null, "title", Severity.HIGH, Instant.now());
        final IncidentEvent escalated = new IncidentEscalatedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID,
                1, Severity.HIGH, "title", Instant.now());
        final IncidentEvent closed = new IncidentClosedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID, UUID.randomUUID(), Instant.now());

        assertThat(describeEvent(opened)).isEqualTo("opened");
        assertThat(describeEvent(acknowledged)).isEqualTo("acknowledged");
        assertThat(describeEvent(resolved)).isEqualTo("resolved");
        assertThat(describeEvent(escalated)).isEqualTo("escalated");
        assertThat(describeEvent(closed)).isEqualTo("closed");
    }

    private String describeEvent(IncidentEvent event) {
        return switch (event) {
            case IncidentOpenedEvent e       -> "opened";
            case IncidentAcknowledgedEvent e -> "acknowledged";
            case IncidentResolvedEvent e     -> "resolved";
            case IncidentEscalatedEvent e    -> "escalated";
            case IncidentClosedEvent e       -> "closed";
        };
    }
}