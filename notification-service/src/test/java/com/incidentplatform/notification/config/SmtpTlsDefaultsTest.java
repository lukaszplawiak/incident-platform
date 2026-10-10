package com.incidentplatform.notification.config;

import com.incidentplatform.notification.support.ApplicationYml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backlog #0-124: notification-service must require STARTTLS before it sends
 * SMTP credentials. With only {@code starttls.enable}, a relay or attacker that
 * strips the STARTTLS capability makes JavaMail continue in plain text, AUTH
 * credentials and message included; {@code starttls.required} makes the send
 * fail instead. The dev mail catchers offer no TLS, so the compose stack and the
 * Kubernetes dev overlay override all three keys with environment variables:
 * the second test shows that override still reaches the new key.
 */
@DisplayName("notification-service SMTP TLS defaults (backlog #0-124)")
class SmtpTlsDefaultsTest {

    private static final String AUTH = "mail.smtp.auth";
    private static final String STARTTLS_ENABLE = "mail.smtp.starttls.enable";
    private static final String STARTTLS_REQUIRED = "mail.smtp.starttls.required";

    @Test
    @DisplayName("the shipped file requires STARTTLS, with auth and starttls.enable still on")
    void shippedFileRequiresStartTls() {
        final StandardEnvironment environment = new StandardEnvironment();
        ApplicationYml.only(environment);

        final Map<String, String> mail = mailProperties(environment);

        assertThat(mail.get(STARTTLS_REQUIRED)).isEqualTo("true");
        assertThat(mail.get(STARTTLS_ENABLE)).isEqualTo("true");
        assertThat(mail.get(AUTH)).isEqualTo("true");
    }

    @Test
    @DisplayName("the dev environment variables turn auth, starttls.enable and starttls.required off")
    void devOverridesTurnAllThreeOff() {
        final StandardEnvironment environment = new StandardEnvironment();
        ApplicationYml.only(environment);
        // Under its real name: Spring Boot maps variable names only for a source
        // called systemEnvironment, as in a running service.
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH", "false",
                        "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE", "false",
                        "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED", "false")));

        final Map<String, String> mail = mailProperties(environment);

        assertThat(mail.get(STARTTLS_REQUIRED)).isEqualTo("false");
        assertThat(mail.get(STARTTLS_ENABLE)).isEqualTo("false");
        assertThat(mail.get(AUTH)).isEqualTo("false");
    }

    private static Map<String, String> mailProperties(StandardEnvironment environment) {
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment)
                .bind("spring.mail.properties", Bindable.mapOf(String.class, String.class))
                .get();
    }
}
