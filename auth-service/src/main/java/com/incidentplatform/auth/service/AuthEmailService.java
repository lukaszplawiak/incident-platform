package com.incidentplatform.auth.service;

import com.incidentplatform.auth.config.InviteEmailProperties;
import com.incidentplatform.auth.exception.InviteEmailException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends auth-domain transactional emails: invite and password reset.
 *
 * <h2>Why auth-service has its own email service</h2>
 * Auth emails (invite, password reset) are semantically different from
 * incident notification emails sent by {@code notification-service}.
 * They contain auth tokens, auth-specific links, and security-critical
 * content. Routing them through {@code notification-service} would create
 * an artificial dependency between the auth domain and the incident domain.
 *
 * <h2>Called by</h2>
 * {@code AuthEmailScheduler} — never called directly from HTTP handlers.
 * The Outbox Pattern ensures SMTP calls happen in a dedicated scheduled
 * thread, not on any latency-sensitive thread.
 *
 * <h2>Security</h2>
 * Tokens are included in links only — never in email subjects or bodies
 * as plain text. Email subjects do not confirm or deny account existence
 * (user enumeration protection).
 */
@Service
public class AuthEmailService {

    private static final Logger log =
            LoggerFactory.getLogger(AuthEmailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String appBaseUrl;

    public AuthEmailService(JavaMailSender mailSender,
                            InviteEmailProperties properties) {
        this.mailSender  = mailSender;
        this.fromAddress = properties.from();
        this.appBaseUrl  = properties.appBaseUrl();
    }

    /**
     * Sends an invite email with a one-click account setup link.
     *
     * @param recipientEmail the invited user's email address
     * @param rawToken       raw invite token — included in link, never logged
     * @throws InviteEmailException if SMTP send fails
     */
    public void sendInviteEmail(String recipientEmail, String rawToken) {
        final String link = buildLink("/accept-invite", rawToken);
        send(recipientEmail,
                "You've been invited to Incident Platform",
                buildInviteBody(recipientEmail, link));
        log.info("Invite email sent: to={}", recipientEmail);
    }

    /**
     * Sends a password reset email with a one-click reset link.
     *
     * <h2>Security — subject line</h2>
     * The subject does not mention the recipient's email or confirm that
     * an account exists. This prevents user enumeration via email subjects
     * in case the message is intercepted or forwarded.
     *
     * @param recipientEmail the user's email address
     * @param rawToken       raw password reset token — included in link, never logged
     * @throws InviteEmailException if SMTP send fails
     */
    public void sendPasswordResetEmail(String recipientEmail, String rawToken) {
        final String link = buildLink("/reset-password", rawToken);
        send(recipientEmail,
                "Reset your Incident Platform password",
                buildPasswordResetBody(recipientEmail, link));
        log.info("Password reset email sent: to={}", recipientEmail);
    }

    // ── private ───────────────────────────────────────────────────────────

    private void send(String recipientEmail, String subject, String htmlBody) {
        try {
            final MimeMessage message = mailSender.createMimeMessage();
            final MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setTo(recipientEmail);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            mailSender.send(message);

        } catch (Exception e) {
            throw new InviteEmailException(
                    recipientEmail,
                    "Failed to send email (" + subject + "): " + e.getMessage(),
                    e);
        }
    }

    private String buildLink(String path, String rawToken) {
        return appBaseUrl + path + "?token=" + rawToken;
    }

    private String buildInviteBody(String recipientEmail, String inviteLink) {
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
                    <hr style="border: none; border-top: 1px solid #ecf0f1; margin: 30px 0;"/>
                    <p style="color: #bdc3c7; font-size: 11px;">
                        Incident Platform — sent to %s
                    </p>
                </body>
                </html>
                """,
                inviteLink, inviteLink, inviteLink, recipientEmail);
    }

    private String buildPasswordResetBody(String recipientEmail,
                                          String resetLink) {
        return String.format("""
                <html>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #2c3e50;">Reset your password</h2>
                    <p>We received a request to reset your Incident Platform password.
                       Click the button below to choose a new password.</p>
                    <p style="margin: 30px 0;">
                        <a href="%s"
                           style="background-color: #e74c3c; color: white;
                                  padding: 12px 24px; text-decoration: none;
                                  border-radius: 4px; display: inline-block;">
                            Reset Password
                        </a>
                    </p>
                    <p style="color: #7f8c8d; font-size: 14px;">
                        Or copy this link into your browser:<br/>
                        <a href="%s" style="color: #e74c3c;">%s</a>
                    </p>
                    <p style="color: #e74c3c; font-size: 12px; font-weight: bold;">
                        ⚠️ This link expires in 15 minutes.
                    </p>
                    <p style="color: #7f8c8d; font-size: 12px;">
                        If you did not request a password reset, please ignore
                        this email — your password has not been changed.<br/>
                        If you are concerned about your account security,
                        please contact your administrator.
                    </p>
                    <hr style="border: none; border-top: 1px solid #ecf0f1; margin: 30px 0;"/>
                    <p style="color: #bdc3c7; font-size: 11px;">
                        Incident Platform — sent to %s
                    </p>
                </body>
                </html>
                """,
                resetLink, resetLink, resetLink, recipientEmail);
    }
}