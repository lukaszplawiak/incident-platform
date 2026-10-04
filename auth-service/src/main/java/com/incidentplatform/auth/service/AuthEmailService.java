package com.incidentplatform.auth.service;

import com.incidentplatform.auth.config.InviteEmailProperties;
import com.incidentplatform.auth.exception.InviteEmailException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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
     *
     * @param unownedKeysToReview backlog #0-89: active tenant and integration
     *        keys this account created; the reset keeps them, so the email
     *        asks for a review when there are any
     */
    public void sendMfaResetNotification(String recipientEmail, Instant resetAt, long unownedKeysToReview) {
        send(recipientEmail,
                "An administrator reset two-factor authentication on your Incident Platform account",
                buildMfaResetBody(recipientEmail, resetAt, unownedKeysToReview));
        log.info("MFA reset notification sent: to={}", recipientEmail);
    }

    /**
     * Tells the account an API key was created with it (backlog #0-89): a
     * personal key to its owner, a tenant key to the admin who created it, one
     * email per key. Shows the key's id, which the key list shows too, so the
     * owner can tell their own key from one they did not make. Not the key's
     * prefix (review: eight characters of the secret itself), nor its name
     * (text the creator typed).
     *
     * @param createdAt when the key was created (the outbox entry's creation)
     * @param keyId     the key's id, or null for a row without one
     * @throws InviteEmailException if SMTP send fails
     */
    public void sendApiKeyCreatedNotification(String recipientEmail, Instant createdAt, UUID keyId) {
        send(recipientEmail,
                "An API key was created with your Incident Platform account",
                buildApiKeyCreatedBody(recipientEmail, createdAt, keyId));
        log.info("API key created notification sent: to={}", recipientEmail);
    }

    /**
     * Announces an operator's MFA recovery request to the account (backlog
     * #0-90): the platform will reset its second factor and password no
     * earlier than {@code notBefore}, unless the owner cancels with the link.
     * Names no operator and repeats nothing the operator wrote: the account
     * may be read by whoever took it, and the cancel link is all it needs.
     *
     * @param rawToken  the cancel token, in the link only, never logged
     * @param notBefore the earliest time the reset can run (send time + waiting period)
     * @throws InviteEmailException if SMTP send fails
     */
    public void sendMfaRecoveryRequested(String recipientEmail, String rawToken, Instant notBefore) {
        send(recipientEmail,
                "Account recovery requested for your Incident Platform account",
                buildMfaRecoveryRequestedBody(recipientEmail, buildLink("/mfa-recovery/cancel", rawToken),
                        notBefore));
        log.info("MFA recovery notice sent: to={}", recipientEmail);
    }

    /**
     * Tells the account its recovery was carried out (backlog #0-90): factor,
     * password, sessions and personal API keys are gone, and the link sets a
     * new password.
     *
     * @param rawToken a password-reset token, in the link only, never logged
     * @throws InviteEmailException if SMTP send fails
     */
    public void sendMfaRecoveryCompleted(String recipientEmail, String rawToken) {
        send(recipientEmail,
                "Your Incident Platform account was recovered: set a new password",
                buildMfaRecoveryCompletedBody(recipientEmail, buildLink("/reset-password", rawToken)));
        log.info("MFA recovery completed email sent: to={}", recipientEmail);
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

    /**
     * The recipient's address as shown in the footer, HTML-escaped (backlog
     * #0-89, found in review): it is interpolated into the HTML body, and the
     * body must not depend on what the address validation lets through.
     */
    private static String footer(String recipientEmail) {
        return HtmlUtils.htmlEscape(recipientEmail);
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
                inviteLink, inviteLink, inviteLink, footer(recipientEmail));
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
                resetLink, resetLink, resetLink, footer(recipientEmail));
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
                footer(recipientEmail));
    }

    private String buildMfaResetBody(String recipientEmail, Instant resetAt, long unownedKeysToReview) {
        final String keysToReview = unownedKeysToReview == 0 ? "" : String.format("""
                    <p style="color: #c0392b;">
                        %d organisation API key(s) created with your account still work: the reset
                        keeps tenant and integration keys. If someone else used your account, ask an
                        administrator to review and revoke them.
                    </p>
                """, unownedKeysToReview);
        return String.format("""
                <html>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #2c3e50;">Two-factor authentication reset</h2>
                    <p>An administrator reset two-factor authentication (MFA) on your Incident
                       Platform account at %s (UTC). Your second factor and backup codes were
                       removed, your personal API keys were revoked, and
                       you were signed out everywhere (a page already open may keep working for
                       up to 15 minutes).</p>
                    <p>Log in with your password and set up MFA again with your own
                       authenticator app.</p>
                    <p style="color: #c0392b; font-weight: bold;">
                        If you did not ask for this, tell your administrator now, and reset your
                        password with "Forgot password" before you set up MFA again.
                    </p>
                    %s
                    <hr style="border: none; border-top: 1px solid #ecf0f1; margin: 30px 0;"/>
                    <p style="color: #bdc3c7; font-size: 11px;">
                        Incident Platform — sent to %s
                    </p>
                </body>
                </html>
                """,
                DateTimeFormatter.ISO_INSTANT.format(resetAt.truncatedTo(ChronoUnit.SECONDS)),
                keysToReview,
                footer(recipientEmail));
    }

    private String buildMfaRecoveryRequestedBody(String recipientEmail, String cancelLink, Instant notBefore) {
        return String.format("""
                <html>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #2c3e50;">Account recovery requested</h2>
                    <p>The Incident Platform operator was asked to recover your account, because
                       you are your organisation's only administrator and cannot use your second
                       factor. The operator verified the request outside the platform.</p>
                    <p>No earlier than <b>%s (UTC)</b> the platform will remove your second factor
                       and backup codes, replace your password, revoke your personal API keys and
                       sign you out everywhere. You will then get an email to set a new password.</p>
                    <p style="color: #c0392b; font-weight: bold;">
                        If you did not ask for this, cancel it now:
                    </p>
                    <p style="text-align: center; margin: 30px 0;">
                        <a href="%s"
                           style="background-color: #c0392b; color: white; padding: 12px 24px;
                                  text-decoration: none; border-radius: 4px; font-weight: bold;">
                            Cancel the recovery
                        </a>
                    </p>
                    <p style="color: #7f8c8d; font-size: 12px;">
                        Or copy this link into your browser:<br/>
                        <a href="%s">%s</a>
                    </p>
                    <p style="color: #7f8c8d; font-size: 12px;">
                        If you asked for this, no action is needed.
                    </p>
                    <hr style="border: none; border-top: 1px solid #ecf0f1; margin: 30px 0;"/>
                    <p style="color: #bdc3c7; font-size: 11px;">
                        Incident Platform — sent to %s
                    </p>
                </body>
                </html>
                """,
                DateTimeFormatter.ISO_INSTANT.format(notBefore.truncatedTo(ChronoUnit.SECONDS)),
                cancelLink, cancelLink, cancelLink, footer(recipientEmail));
    }

    private String buildMfaRecoveryCompletedBody(String recipientEmail, String resetLink) {
        return String.format("""
                <html>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #2c3e50;">Your account was recovered</h2>
                    <p>As requested, the platform removed the second factor and backup codes of
                       your Incident Platform account, replaced its password, revoked its personal
                       API keys and signed it out everywhere (a page already open may keep working
                       for up to 15 minutes).</p>
                    <p>Set a new password, then log in and set up MFA again with your own
                       authenticator app:</p>
                    <p style="text-align: center; margin: 30px 0;">
                        <a href="%s"
                           style="background-color: #3498db; color: white; padding: 12px 24px;
                                  text-decoration: none; border-radius: 4px; font-weight: bold;">
                            Set a new password
                        </a>
                    </p>
                    <p style="color: #7f8c8d; font-size: 12px;">
                        Or copy this link into your browser:<br/>
                        <a href="%s">%s</a><br/>
                        The link works for 15 minutes; after that use "Forgot password".
                    </p>
                    <p style="color: #c0392b;">
                        Consider inviting a second administrator, so that next time someone in
                        your organisation can reset your MFA.
                    </p>
                    <hr style="border: none; border-top: 1px solid #ecf0f1; margin: 30px 0;"/>
                    <p style="color: #bdc3c7; font-size: 11px;">
                        Incident Platform — sent to %s
                    </p>
                </body>
                </html>
                """,
                resetLink, resetLink, resetLink, footer(recipientEmail));
    }

    private String buildApiKeyCreatedBody(String recipientEmail, Instant createdAt, UUID keyId) {
        final String which = keyId == null ? "An API key" : "An API key with id <b>" + keyId + "</b>";
        return String.format("""
                <html>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #2c3e50;">New API key</h2>
                    <p>%s was created with your Incident Platform account at %s (UTC).
                       Your list of API keys shows it with the same id; an administrator sees
                       every key of the organisation, who created it and when it was last used.</p>
                    <p style="color: #c0392b; font-weight: bold;">
                        If this was not you, someone else knows your password: reset it now
                        with "Forgot password", which also revokes your personal API keys, and
                        tell your administrator, who can revoke any other key.
                    </p>
                    <p style="color: #7f8c8d; font-size: 12px;">
                        If you created this key, no action is needed.
                    </p>
                    <hr style="border: none; border-top: 1px solid #ecf0f1; margin: 30px 0;"/>
                    <p style="color: #bdc3c7; font-size: 11px;">
                        Incident Platform — sent to %s
                    </p>
                </body>
                </html>
                """,
                which,
                DateTimeFormatter.ISO_INSTANT.format(createdAt.truncatedTo(ChronoUnit.SECONDS)),
                footer(recipientEmail));
    }
}
