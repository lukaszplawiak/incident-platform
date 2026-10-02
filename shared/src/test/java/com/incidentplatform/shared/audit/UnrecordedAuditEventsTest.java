package com.incidentplatform.shared.audit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UnrecordedAuditEvents (backlog #0-84)")
class UnrecordedAuditEventsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    @DisplayName("every declared type is registered at zero before any failure (found in review)")
    void registeredAtZero() {
        new UnrecordedAuditEvents(meters, "NOTIFICATION_SENT", "NOTIFICATION_FAILED");

        assertThat(meters.find(UnrecordedAuditEvents.COUNTER).counters()).hasSize(2)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    @DisplayName("increment counts per event type; an undeclared type is still counted")
    void counts() {
        final UnrecordedAuditEvents unrecorded = new UnrecordedAuditEvents(meters, "ESCALATION_FIRED");

        unrecorded.increment("ESCALATION_FIRED");
        unrecorded.increment("ESCALATION_FIRED");
        unrecorded.increment("OTHER");

        assertThat(meters.counter(UnrecordedAuditEvents.COUNTER, "event_type", "ESCALATION_FIRED").count())
                .isEqualTo(2.0);
        assertThat(meters.counter(UnrecordedAuditEvents.COUNTER, "event_type", "OTHER").count()).isEqualTo(1.0);
    }
}
