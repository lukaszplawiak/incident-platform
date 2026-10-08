package com.incidentplatform.notification.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The text a failed send records is built from the platform's reason and a
 * checked provider code only (backlog #0-93).
 */
@DisplayName("NotificationException")
class NotificationExceptionTest {

    @Test
    @DisplayName("the message is the reason's name and description, with the code when there is one")
    void recordedText() {
        final RuntimeException provider = new RuntimeException("550 rejected by relay smtp.internal:587");

        final NotificationException withoutCode = new NotificationException("EMAIL", "a@b.example",
                NotificationFailureReason.EMAIL_RECIPIENT_REJECTED, provider);
        assertThat(withoutCode.recordedText())
                .isEqualTo("EMAIL_RECIPIENT_REJECTED: The mail server rejected the recipient address")
                .isEqualTo(withoutCode.getMessage());
        assertThat(withoutCode.getCause()).isSameAs(provider);
        assertThat(withoutCode.detail()).isNull();

        final NotificationException withCode = new NotificationException("SLACK", "U1",
                NotificationFailureReason.SLACK_REJECTED, "channel_not_found", null);
        assertThat(withCode.recordedText()).isEqualTo("SLACK_REJECTED (channel_not_found): Slack refused the message");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bad Code", "line\nbreak", "quote\"", "", "x123456789x123456789x123456789x123456789"
            + "x123456789x123456789xxxxx"})
    @DisplayName("a provider code that is not a plain code of at most 64 characters is dropped")
    void oddCodeDropped(String code) {
        final NotificationException e = new NotificationException("SLACK", "U1",
                NotificationFailureReason.SLACK_REJECTED, code, null);

        assertThat(e.detail()).isNull();
        assertThat(e.getMessage()).isEqualTo("SLACK_REJECTED: Slack refused the message");
    }

    @Test
    @DisplayName("a reason is required")
    void reasonRequired() {
        assertThatThrownBy(() -> new NotificationException("SLACK", "U1", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("every reason's description is its own fixed text, on one line")
    void descriptionsArePlain() {
        for (final NotificationFailureReason reason : NotificationFailureReason.values()) {
            assertThat(reason.description()).as(reason.name()).isNotBlank().doesNotContain("\n");
        }
    }

    /**
     * Review: each flag pinned, as it decides whether a failure is logged at
     * ERROR (the platform's) or WARN (the tenant's to fix), and what a retry
     * (#0-32) would skip. Permanent: only what the tenant must change.
     */
    @Test
    @DisplayName("permanent only where the tenant has something to change")
    void permanence() {
        assertThat(java.util.Arrays.stream(NotificationFailureReason.values())
                .filter(NotificationFailureReason::permanent))
                .containsExactlyInAnyOrder(
                        NotificationFailureReason.EMAIL_RECIPIENT_REJECTED,
                        NotificationFailureReason.SLACK_REJECTED,
                        NotificationFailureReason.SLACK_WORKSPACE_MISSING,
                        NotificationFailureReason.SLACK_NOTHING_POSTED);
    }
}
