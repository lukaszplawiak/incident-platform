package com.incidentplatform.auth.service;

import com.incidentplatform.auth.config.InviteEmailProperties;
import com.incidentplatform.auth.exception.InviteEmailException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Sends auth-domain transactional emails: invite, password reset, and the
 * MFA change notifications (backlog #0-83).
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

    /**
     * Backlog #0-88: a password reset no longer removes a factor (it did
     * within the grace period, #0-83); an admin of the tenant resets it, and
     * only after the password has changed, or its holder could enrol again.
     */
    private static final String MFA_ENABLED_WARNING = """
            If this was not you, someone else knows your password and has set up
            their own second factor on your account. Reset your password now with
            "Forgot password", then ask an administrator of your organisation to
            reset your MFA, so you can set up your own.""";

    /** An admin's MFA reset has its own email (backlog #0-88), so this is the user's own change. */
    private static final String MFA_DISABLED_WARNING = """
            If this was not you, someone else knows your password: reset it now
            with "Forgot password" and tell your administrator.""";

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

    /**
     * Tells the account's owner that MFA was enabled or disabled (backlog
     * #0-83). No link and no token: if the change was not theirs, the owner
     * acts through the normal paths (password reset, their administrator).
     * The subject names no account, like the other auth emails.
     *
     * @param changedAt when the change was made (the outbox entry's creation)
     * @throws InviteEmailException if SMTP send fails
     */
    public void sendMfaChangeNotification(String recipientEmail, boolean enabled, Instant changedAt) {
        final String what = enabled ? "enabled" : "disabled";
        send(recipientEmail,
                "Two-factor authentication was " + what + " on your Incident Platform account",
                buildMfaChangeBody(recipientEmail, what, changedAt,
                        enabled ? MFA_ENABLED_WARNING : MFA_DISABLED_WARNING));
        log.info("MFA {} notification sent: to={}", what, recipientEmail);
    }

    /**
     * Tells the account an administrator reset its MFA (backlog #0-88): its
     * own text, so a reset the owner did not ask for stands out from them
     * disabling MFA themselves.
     */
    public void sendMfaResetNotification(String recipientEmail, Instant resetAt) {
        send(recipientEmail,
                "An administrator reset two-factor authentication on your Incident Platform account",
                buildMfaResetBody(recipientEmail, resetAt));
        log.info("MFA reset notification sent: to={}", recipientEmail);
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

    private String buildMfaChangeBody(String recipientEmail, String what, Instant changedAt,
                                      String warning) {
        return String.format("""
                <html>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #2c3e50;">Two-factor authentication %s</h2>
                    <p>Two-factor authentication (MFA) was %s on your Incident Platform
                       account at %s (UTC).</p>
                    <p style="color: #c0392b; font-weight: bold;">
                        %s
                    </p>
                    <p style="color: #7f8c8d; font-size: 12px;">
                        If you made this change, no action is needed.
                    </p>
                    <hr style="border: none; border-top: 1px solid #ecf0f1; margin: 30px 0;"/>
                    <p style="color: #bdc3c7; font-size: 11px;">
                        Incident Platform — sent to %s
                    </p>
                </body>
                </html>
                """,
                what, what, DateTimeFormatter.ISO_INSTANT.format(changedAt.truncatedTo(ChronoUnit.SECONDS)),
                warning,
                recipientEmail);
    }

    private String buildMfaResetBody(String recipientEmail, Instant resetAt) {
        return String.format("""
                <html>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #2c3e50;">Two-factor authentication reset</h2>
                    <p>An administrator reset two-factor authentication (MFA) on your Incident
                       Platform account at %s (UTC). Your second factor and backup codes were
                       removed and you were signed out everywhere (a page already open may keep
                       working for up to 15 minutes).</p>
                    <p>Log in with your password and set up MFA again with your own
                       authenticator app.</p>
                    <p style="color: #c0392b; font-weight: bold;">
                        If you did not ask for this, tell your administrator now, and reset your
                        password with "Forgot password" before you set up MFA again.
                    </p>
                    <hr style="border: none; border-top: 1px solid #ecf0f1; margin: 30px 0;"/>
                    <p style="color: #bdc3c7; font-size: 11px;">
                        Incident Platform — sent to %s
                    </p>
                </body>
                </html>
                """,
                DateTimeFormatter.ISO_INSTANT.format(resetAt.truncatedTo(ChronoUnit.SECONDS)),
                recipientEmail);
    }
}
