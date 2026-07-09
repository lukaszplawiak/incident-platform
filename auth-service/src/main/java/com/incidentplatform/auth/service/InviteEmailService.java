package com.incidentplatform.auth.service;

import com.incidentplatform.auth.exception.InviteEmailException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.incidentplatform.auth.config.InviteEmailProperties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends invite emails to newly created users.
 *
 * <h2>Why auth-service has its own email service</h2>
 * Auth emails (invite, password reset) are semantically different from
 * incident notification emails sent by {@code notification-service}.
 * Invite emails are part of the auth domain — they contain auth tokens,
 * auth-specific links, and auth-specific branding. Routing them through
 * {@code notification-service} would create an artificial dependency between
 * the auth domain and the incident notification domain.
 *
 * <p>Both services share the same SMTP configuration (same env vars:
 * {@code MAIL_HOST}, {@code MAIL_PORT}, {@code MAIL_USERNAME},
 * {@code MAIL_PASSWORD}) — the duplication is at the infrastructure
 * configuration level only, not at the code level.
 *
 * <h2>Called by</h2>
 * {@code InviteEmailScheduler} — never called directly from a Kafka consumer
 * or HTTP request handler. The Outbox Pattern ensures this service runs in a
 * dedicated scheduled thread, not on any latency-sensitive thread.
 */
@Service
public class InviteEmailService {

    private static final Logger log =
            LoggerFactory.getLogger(InviteEmailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String appBaseUrl;

    public InviteEmailService(JavaMailSender mailSender,
                              InviteEmailProperties properties) {
        this.mailSender   = mailSender;
        this.fromAddress  = properties.from();
        this.appBaseUrl   = properties.appBaseUrl();
    }

    /**
     * Sends an invite email with a one-click setup link.
     *
     * <p>The link contains the raw token as a query parameter. The frontend
     * extracts the token and calls {@code POST /api/v1/auth/accept-invite}.
     *
     * @param recipientEmail the email address of the invited user
     * @param rawToken       the raw (unhashed) invite token — included in link
     * @throws InviteEmailException if sending fails (SMTP unavailable, etc.)
     */
    public void sendInviteEmail(String recipientEmail, String rawToken) {
        final String inviteLink = buildInviteLink(rawToken);

        try {
            final MimeMessage message = mailSender.createMimeMessage();
            final MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setTo(recipientEmail);
            helper.setSubject("You've been invited to Incident Platform");
            helper.setText(buildHtmlBody(recipientEmail, inviteLink), true);

            mailSender.send(message);

            log.info("Invite email sent: to={}", recipientEmail);

        } catch (Exception e) {
            throw new InviteEmailException(
                    recipientEmail,
                    "Failed to send invite email: " + e.getMessage(),
                    e);
        }
    }

    private String buildInviteLink(String rawToken) {
        return appBaseUrl + "/accept-invite?token=" + rawToken;
    }

    private String buildHtmlBody(String recipientEmail, String inviteLink) {
        return String.format("""
                <html>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #2c3e50;">Welcome to Incident Platform</h2>
                    <p>You've been invited to join Incident Platform.
                       Click the button below to set up your password and
                       activate your account.</p>
                    <p style="margin: 30px 0;">
                        <a href="%s"
                           style="background-color: #3498db; color: white;
                                  padding: 12px 24px; text-decoration: none;
                                  border-radius: 4px; display: inline-block;">
                            Accept Invitation
                        </a>
                    </p>
                    <p style="color: #7f8c8d; font-size: 14px;">
                        Or copy this link into your browser:<br/>
                        <a href="%s" style="color: #3498db;">%s</a>
                    </p>
                    <p style="color: #7f8c8d; font-size: 12px;">
                        This invitation link expires in 7 days.<br/>
                        If you did not expect this invitation, please ignore
                        this email.
                    </p>
                    <hr style="border: none; border-top: 1px solid #ecf0f1;
                               margin: 30px 0;"/>
                    <p style="color: #bdc3c7; font-size: 11px;">
                        Incident Platform — sent to %s
                    </p>
                </body>
                </html>
                """,
                inviteLink, inviteLink, inviteLink, recipientEmail);
    }
}