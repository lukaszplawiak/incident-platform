package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kanał email — symulacja przez logi.
 *
 * Na produkcji:
 * - JavaMailSender (Spring Mail + SMTP)
 * - SendGrid SDK
 * - AWS SES SDK
 *
 * Symulacja pozwala pokazać architekturę bez zewnętrznych zależności.
 * Podmiana na prawdziwą implementację = zmiana tylko tej klasy.
 */
@Component
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger log =
            LoggerFactory.getLogger(EmailNotificationChannel.class);

    @Value("${notification.channels.email.enabled:true}")
    private boolean enabled;

    @Value("${notification.channels.email.from:alerts@incidentplatform.com}")
    private String fromAddress;

    @Override
    public String channelName() {
        return "EMAIL";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void send(NotificationRequest request) {
        // Na produkcji: mailSender.send(buildMimeMessage(request))
        log.info("""
                [EMAIL SIMULATION] Sending email:
                  From:    {}
                  To:      {}
                  Subject: {}
                  Body:    {}
                  Incident: {} | Severity: {} | Tenant: {}
                """,
                fromAddress,
                request.recipient(),
                request.subject(),
                request.message(),
                request.incidentId(),
                request.severity(),
                request.tenantId()
        );

        // Na produkcji: tu byłby rzeczywisty call do SMTP/API
    }
}