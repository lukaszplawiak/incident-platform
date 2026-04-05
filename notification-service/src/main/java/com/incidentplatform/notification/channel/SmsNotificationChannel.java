package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kanał SMS — symulacja przez logi.
 *
 * Na produkcji:
 * - Twilio SDK (com.twilio.sdk:twilio)
 * - AWS SNS SDK
 *
 * SMS używamy tylko dla eskalacji (CRITICAL/HIGH) —
 * drogi kanał, nie wysyłamy wszystkiego.
 */
@Component
public class SmsNotificationChannel implements NotificationChannel {

    private static final Logger log =
            LoggerFactory.getLogger(SmsNotificationChannel.class);

    @Value("${notification.channels.sms.enabled:true}")
    private boolean enabled;

    @Value("${notification.channels.sms.from-number:+1234567890}")
    private String fromNumber;

    @Override
    public String channelName() {
        return "SMS";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void send(NotificationRequest request) {
        // Na produkcji: twilioClient.messages.create(to, from, body)
        log.info("""
                [SMS SIMULATION] Sending SMS:
                  From: {}
                  To:   {}
                  Text: {}
                  Incident: {} | Severity: {} | Tenant: {}
                """,
                fromNumber,
                request.recipient(),
                request.message() != null
                        ? request.message().substring(0, Math.min(160, request.message().length()))
                        : "",
                request.incidentId(),
                request.severity(),
                request.tenantId()
        );
    }
}