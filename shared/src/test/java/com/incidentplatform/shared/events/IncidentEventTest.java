package com.incidentplatform.shared.events;

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
        // when
        final IncidentOpenedEvent event = new IncidentOpenedEvent(
                INCIDENT_ID, TENANT_ID, UUID.randomUUID(),
                "High CPU", "CRITICAL", SourceType.OPS, Instant.now()
        );

        // then
        assertThat(event).isInstanceOf(IncidentEvent.class);
        assertThat(event.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(event.tenantId()).isEqualTo(TENANT_ID);
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("IncidentAcknowledgedEvent should set occurredAt when null")
    void acknowledgedEventShouldSetOccurredAt() {
        // when
        final IncidentAcknowledgedEvent event = new IncidentAcknowledgedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID, null
        );

        // then
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.acknowledgedBy()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("IncidentResolvedEvent should contain duration and resolution")
    void resolvedEventShouldContainDuration() {
        // when
        final IncidentResolvedEvent event = new IncidentResolvedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID,
                45L, "Restarted the service", Instant.now()
        );

        // then
        assertThat(event.durationMinutes()).isEqualTo(45L);
        assertThat(event.resolution()).isEqualTo("Restarted the service");
        assertThat(event.resolvedBy()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("IncidentEscalatedEvent should contain escalation level")
    void escalatedEventShouldContainLevel() {
        // when
        final IncidentEscalatedEvent event = new IncidentEscalatedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID,
                2, "CRITICAL", "High CPU", Instant.now()
        );

        // then
        assertThat(event.escalationLevel()).isEqualTo(2);
        assertThat(event.escalateTo()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("IncidentClosedEvent should contain postmortemId")
    void closedEventShouldContainPostmortemId() {
        // given
        final UUID postmortemId = UUID.randomUUID();

        // when
        final IncidentClosedEvent event = new IncidentClosedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID, postmortemId, Instant.now()
        );

        // then
        assertThat(event.postmortemId()).isEqualTo(postmortemId);
        assertThat(event.closedBy()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("pattern matching switch should handle all IncidentEvent subtypes")
    void patternMatchingShouldBeExhaustive() {
        // given
        final IncidentEvent opened = new IncidentOpenedEvent(
                INCIDENT_ID, TENANT_ID, UUID.randomUUID(),
                "title", "HIGH", SourceType.OPS, Instant.now());
        final IncidentEvent acknowledged = new IncidentAcknowledgedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID, Instant.now());
        final IncidentEvent resolved = new IncidentResolvedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID, 30L, null, Instant.now());
        final IncidentEvent escalated = new IncidentEscalatedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID, 1, "HIGH", "title", Instant.now());
        final IncidentEvent closed = new IncidentClosedEvent(
                INCIDENT_ID, TENANT_ID, USER_ID, UUID.randomUUID(), Instant.now());

        // when
        assertThat(describeEvent(opened)).isEqualTo("opened");
        assertThat(describeEvent(acknowledged)).isEqualTo("acknowledged");
        assertThat(describeEvent(resolved)).isEqualTo("resolved");
        assertThat(describeEvent(escalated)).isEqualTo("escalated");
        assertThat(describeEvent(closed)).isEqualTo("closed");
    }

    private String describeEvent(IncidentEvent event) {
        return switch (event) {
            case IncidentOpenedEvent e      -> "opened";
            case IncidentAcknowledgedEvent e -> "acknowledged";
            case IncidentResolvedEvent e    -> "resolved";
            case IncidentEscalatedEvent e   -> "escalated";
            case IncidentClosedEvent e      -> "closed";
        };
    }
}