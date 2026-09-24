package com.incidentplatform.notification.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationChannelProperties.OperatorAlert (backlog #0-18)")
class NotificationChannelPropertiesTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("an absent operator-alert section is a valid, empty one — no email is sent")
    void absentSectionIsEmpty() {
        final NotificationChannelProperties properties = new NotificationChannelProperties(
                new NotificationChannelProperties.Channels(
                        new NotificationChannelProperties.Email(true, "alerts@test.com"),
                        new NotificationChannelProperties.Slack(true, "s", "http://localhost"),
                        new NotificationChannelProperties.Sms(true, "+1234567890")),
                null);

        assertThat(properties.operatorAlert().hasEmail()).isFalse();
    }

    @Test
    @DisplayName("blank and null are allowed (no email is sent)")
    void blankIsAllowed() {
        assertThat(validator.validate(new NotificationChannelProperties.OperatorAlert("", null))).isEmpty();
        assertThat(validator.validate(new NotificationChannelProperties.OperatorAlert(null, null))).isEmpty();
    }

    @Test
    @DisplayName("a valid address passes and a malformed one fails at startup, not on the first alert")
    void addressIsValidated() {
        assertThat(validator.validate(
                new NotificationChannelProperties.OperatorAlert("ops@platform.example", null))).isEmpty();
        assertThat(validator.validate(
                new NotificationChannelProperties.OperatorAlert("not-an-email", null))).isNotEmpty();
    }

    @Test
    @DisplayName("min-interval defaults to 15 minutes when absent")
    void minIntervalDefaults() {
        assertThat(new NotificationChannelProperties.OperatorAlert("ops@platform.example", null)
                .minInterval()).isEqualTo(java.time.Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("a zero or negative min-interval is rejected at startup — it would silently switch the limit off")
    void nonPositiveMinIntervalIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new NotificationChannelProperties.OperatorAlert(
                                "ops@platform.example", java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new NotificationChannelProperties.OperatorAlert(
                                "ops@platform.example", java.time.Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
