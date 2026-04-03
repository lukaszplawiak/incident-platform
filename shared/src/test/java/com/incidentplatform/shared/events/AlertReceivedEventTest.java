package com.incidentplatform.shared.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AlertReceivedEvent")
class AlertReceivedEventTest {

    @Test
    @DisplayName("should create event with all fields using factory method")
    void shouldCreateEventUsingFactoryMethod() {
        // given
        final String tenantId = "acme-corp";
        final String source = "prometheus";
        final Instant firedAt = Instant.now().minusSeconds(60);

        // when
        final AlertReceivedEvent event = AlertReceivedEvent.from(
                tenantId, source, SourceType.OPS,
                "CRITICAL", "High CPU usage",
                "CPU exceeded 95%", firedAt,
                Map.of("instance", "server-1:9100")
        );

        // then
        assertThat(event.eventId()).isNotNull();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.source()).isEqualTo(source);
        assertThat(event.sourceType()).isEqualTo(SourceType.OPS);
        assertThat(event.severity()).isEqualTo("CRITICAL");
        assertThat(event.title()).isEqualTo("High CPU usage");
        assertThat(event.description()).isEqualTo("CPU exceeded 95%");
        assertThat(event.firedAt()).isEqualTo(firedAt);
        assertThat(event.receivedAt()).isNotNull();
        assertThat(event.metadata()).containsEntry("instance", "server-1:9100");
    }

    @Test
    @DisplayName("should generate eventId when null is provided")
    void shouldGenerateEventIdWhenNull() {
        // when
        final AlertReceivedEvent event = new AlertReceivedEvent(
                null, "tenant", "prometheus", SourceType.OPS,
                "HIGH", "title", null,
                Instant.now(), null, null
        );

        // then
        assertThat(event.eventId()).isNotNull();
    }

    @Test
    @DisplayName("should generate receivedAt when null is provided")
    void shouldGenerateReceivedAtWhenNull() {
        // when
        final AlertReceivedEvent event = new AlertReceivedEvent(
                UUID.randomUUID(), "tenant", "prometheus", SourceType.OPS,
                "HIGH", "title", null,
                Instant.now(), null, null
        );

        // then
        assertThat(event.receivedAt()).isNotNull();
    }

    @Test
    @DisplayName("should trim title whitespace")
    void shouldTrimTitle() {
        // when
        final AlertReceivedEvent event = new AlertReceivedEvent(
                UUID.randomUUID(), "tenant", "prometheus", SourceType.OPS,
                "HIGH", "  High CPU usage  ", null,
                Instant.now(), Instant.now(), null
        );

        // then
        assertThat(event.title()).isEqualTo("High CPU usage");
    }

    @Test
    @DisplayName("should implement AlertEvent sealed interface")
    void shouldImplementAlertEvent() {
        // when
        final AlertReceivedEvent event = AlertReceivedEvent.from(
                "tenant", "prometheus", SourceType.OPS,
                "HIGH", "title", null, Instant.now(), null
        );

        // then
        assertThat(event).isInstanceOf(AlertEvent.class);
    }

    @Test
    @DisplayName("should return tenantId from AlertEvent interface")
    void shouldReturnTenantIdFromInterface() {
        // given
        final AlertEvent event = AlertReceivedEvent.from(
                "acme-corp", "prometheus", SourceType.OPS,
                "HIGH", "title", null, Instant.now(), null
        );

        // then
        final String tenantId = switch (event) {
            case AlertReceivedEvent e -> e.tenantId();
        };
        assertThat(tenantId).isEqualTo("acme-corp");
    }

    @Test
    @DisplayName("should create two events with different eventIds")
    void shouldCreateUniqueEventIds() {
        // when
        final AlertReceivedEvent event1 = AlertReceivedEvent.from(
                "tenant", "prometheus", SourceType.OPS,
                "HIGH", "title", null, Instant.now(), null
        );
        final AlertReceivedEvent event2 = AlertReceivedEvent.from(
                "tenant", "prometheus", SourceType.OPS,
                "HIGH", "title", null, Instant.now(), null
        );

        // then
        assertThat(event1.eventId()).isNotEqualTo(event2.eventId());
    }
}