package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger log =
            LoggerFactory.getLogger(EmailNotificationChannel.class);

    @Value("${notification.channels.email.enabled:true}")
    private boolean enabled;

    @Value("${notification.channels.email.from:alerts@incidentplatform.com}")
    private String fromAddress;

    private final JavaMailSender mailSender;

    public EmailNotificationChannel(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

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
        try {
            final MimeMessage message = mailSender.createMimeMessage();
            final MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setTo(request.recipient());
            helper.setSubject(request.subject());
            helper.setText(buildHtmlBody(request), true);

            mailSender.send(message);

            log.info("Email sent: to={}, subject={}, incidentId={}",
                    request.recipient(), request.subject(),
                    request.incidentId());

        } catch (Exception e) {
            throw new NotificationException(
                    "EMAIL",
                    request.recipient(),
                    "Email sending failed: " + e.getMessage(),
                    e
            );
        }
    }

    private String buildHtmlBody(NotificationRequest request) {
        final String severityColor = switch (request.severity()) {
            case CRITICAL -> "#FF0000";
            case HIGH     -> "#FF6600";
            case MEDIUM   -> "#FFAA00";
            case LOW      -> "#00AA00";
        };

        return String.format("""
                <html>
                <body style="font-family: Arial, sans-serif;">
                    <h2 style="color: %s;">%s</h2>
                    <p>%s</p>
                    <hr/>
                    <p><strong>Incident ID:</strong> %s</p>
                    <p><strong>Severity:</strong>
                        <span style="color: %s;">%s</span>
                    </p>
                    <p><strong>Tenant:</strong> %s</p>
                </body>
                </html>
                """,
                severityColor,
                request.subject(),
                request.message(),
                request.incidentId(),
                severityColor,
                request.severity().name(),
                request.tenantId()
        );
    }
}