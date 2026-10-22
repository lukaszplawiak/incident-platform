package com.incidentplatform.incident.domain;

import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.SourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Incident")
class IncidentTest {

    private static Incident newIncident() {
        return new Incident("acme", "High CPU usage", "CPU exceeded 95%", Severity.HIGH, SourceType.OPS,
                "prometheus", "prometheus:highcpu:" + UUID.randomUUID(), UUID.randomUUID(), Instant.now());
    }

    @Nested
    @DisplayName("recordEscalation (backlog #0-118)")
    class RecordEscalation {

        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        @DisplayName("raises the level of a new incident")
        void raisesFromZero(int level) {
            final Incident incident = newIncident();

            assertThat(incident.recordEscalation(level)).isTrue();

            assertThat(incident.getEscalationLevel()).isEqualTo(level);
        }

        @Test
        @DisplayName("raises 1 to 2 and moves updatedAt")
        void raisesOneToTwo() {
            final Incident incident = newIncident();
            incident.recordEscalation(1);
            final Instant before = incident.getUpdatedAt();

            assertThat(incident.recordEscalation(2)).isTrue();

            assertThat(incident.getEscalationLevel()).isEqualTo(2);
            assertThat(incident.getUpdatedAt()).isAfterOrEqualTo(before);
        }

        @Test
        @DisplayName("a lower level read after a higher one does not lower it, nor move updatedAt")
        void neverLowers() {
            final Incident incident = newIncident();
            incident.recordEscalation(2);
            final Instant before = incident.getUpdatedAt();

            assertThat(incident.recordEscalation(1)).isFalse();

            assertThat(incident.getEscalationLevel()).isEqualTo(2);
            assertThat(incident.getUpdatedAt()).isSameAs(before);
        }

        @Test
        @DisplayName("the same level again changes nothing")
        void sameLevelIsNoChange() {
            final Incident incident = newIncident();
            incident.recordEscalation(2);
            final Instant before = incident.getUpdatedAt();

            assertThat(incident.recordEscalation(2)).isFalse();

            assertThat(incident.getEscalationLevel()).isEqualTo(2);
            assertThat(incident.getUpdatedAt()).isSameAs(before);
        }
    }
}
