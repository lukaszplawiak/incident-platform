package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger log =
            LoggerFactory.getLogger(EmailNotificationChannel.class);

    private final boolean enabled;
    private final String fromAddress;
    private final JavaMailSender mailSender;

    public EmailNotificationChannel(
            JavaMailSender mailSender,
            NotificationChannelProperties properties) {
        this.mailSender  = mailSender;
        this.enabled     = properties.channels().email().enabled();
        this.fromAddress = properties.channels().email().from();
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

    /**
     * Builds the HTML email body.
     *
     * <h2>Fixed: unescaped HTML injection via subject/message</h2>
     * {@code request.subject()}/{@code request.message()} ultimately
     * originate from external alert sources (Prometheus, Wazuh — see
     * ingestion-service's normalizers) — free-text fields this platform
     * does not control. Previously interpolated directly into raw HTML via
     * {@code String.format}, so a malicious or malformed alert title/
     * description containing HTML markup would be embedded verbatim in
     * the email body. Most modern mail clients strip {@code <script>} and
     * disable JS execution in rendered HTML email by policy, so classic
     * XSS wasn't the primary concern here — but unescaped markup could
     * still break the email's layout or inject deceptive content dressed
     * up as a legitimate platform notification. Fixed using Spring's own
     * {@link HtmlUtils#htmlEscape}, applied to both fields — no new
     * dependency, this codebase's first use of HTML escaping (nothing
     * else builds raw HTML from external strings today).
     */
    private String buildHtmlBody(NotificationRequest request) {
        final String severityColor = switch (request.severity()) {
            case CRITICAL -> "#FF0000";
            case HIGH     -> "#FF6600";
            case MEDIUM   -> "#FFAA00";
            case LOW      -> "#00AA00";
        };

        final String safeSubject = escapeHtml(request.subject());
        final String safeMessage = escapeHtml(request.message());

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
                safeSubject,
                safeMessage,
                request.incidentId(),
                severityColor,
                request.severity().name(),
                request.tenantId()
        );
    }

    /**
     * Null-safe wrapper around {@link HtmlUtils#htmlEscape(String)} —
     * {@code request.message()} is a plain, nullable field on
     * {@link NotificationRequest} (see e.g.
     * {@code SmsNotificationChannelTest}'s "should not throw when message
     * is null" case), and {@code HtmlUtils.htmlEscape} does not itself
     * accept null.
     */
    private static String escapeHtml(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}