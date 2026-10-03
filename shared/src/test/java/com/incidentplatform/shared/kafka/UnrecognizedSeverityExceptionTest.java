package com.incidentplatform.shared.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UnrecognizedSeverityException (backlog #0-92)")
class UnrecognizedSeverityExceptionTest {

    @Test
    @DisplayName("the message names the incident and operation, never the raw value from the payload")
    void messageDoesNotQuoteRawValue() {
        final UUID incidentId = UUID.randomUUID();

        final UnrecognizedSeverityException e = new UnrecognizedSeverityException(
                "CRITICAL\nFAKE LOG LINE", incidentId, "createFromAlert");

        assertThat(e.getMessage())
                .contains(incidentId.toString())
                .contains("createFromAlert")
                .doesNotContain("FAKE")
                .doesNotContain("CRITICAL");
        assertThat(e.getRawSeverity()).isEqualTo("CRITICAL\nFAKE LOG LINE");
    }
}
